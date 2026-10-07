package com.evops.aqua.supervision.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.aqua.entity.DailyRecord;
import com.evops.aqua.entity.HarvestRecord;
import com.evops.aqua.entity.InspectionRecord;
import com.evops.aqua.entity.MedicationRecord;
import com.evops.aqua.entity.Pond;
import com.evops.aqua.entity.SeedlingBatch;
import com.evops.aqua.mapper.DailyRecordMapper;
import com.evops.aqua.mapper.HarvestRecordMapper;
import com.evops.aqua.mapper.InspectionRecordMapper;
import com.evops.aqua.mapper.MedicationRecordMapper;
import com.evops.aqua.mapper.PondMapper;
import com.evops.aqua.mapper.SeedlingBatchMapper;
import com.evops.aqua.supervision.entity.SvCaliberIndicator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 月报五指标聚合：从台账源表按报告期 + 海区取数。
 * 关键口径：某指标在报告期内「无任何源采样记录」时不产出数值，交上层标 PENDING_SUPPLEMENT 待补，
 * 绝不以 0 填充——0 是真实统计值，待补是数据缺失，二者不得混淆。
 */
@Service
public class SvReportAggregator {

    private final PondMapper pondMapper;
    private final SeedlingBatchMapper batchMapper;
    private final DailyRecordMapper dailyMapper;
    private final MedicationRecordMapper medicationMapper;
    private final HarvestRecordMapper harvestMapper;
    private final InspectionRecordMapper inspectionMapper;

    public SvReportAggregator(PondMapper pondMapper,
                              SeedlingBatchMapper batchMapper,
                              DailyRecordMapper dailyMapper,
                              MedicationRecordMapper medicationMapper,
                              HarvestRecordMapper harvestMapper,
                              InspectionRecordMapper inspectionMapper) {
        this.pondMapper = pondMapper;
        this.batchMapper = batchMapper;
        this.dailyMapper = dailyMapper;
        this.medicationMapper = medicationMapper;
        this.harvestMapper = harvestMapper;
        this.inspectionMapper = inspectionMapper;
    }

    /** 单指标聚合结果。 */
    public static class Metric {
        String indicatorCode;
        String value;
        int recordCount;
        boolean missing;
        String pendingReason;
    }

    /**
     * 按口径版本中的指标顺序聚合指定报告期+海区（仅 ACTIVE 池塘的台账记录）。
     */
    public Map<String, Metric> aggregate(String reportMonth, String seaArea) {
        YearMonth ym = YearMonth.parse(reportMonth);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.plusMonths(1).atDay(1); // 半开区间 [start, end)

        List<Pond> ponds = pondMapper.selectList(new QueryWrapper<Pond>()
                .eq("sea_area", seaArea).eq("status", Pond.STATUS_ACTIVE));
        Map<Long, Pond> pondById = new HashMap<>();
        for (Pond pond : ponds) {
            pondById.put(pond.getId(), pond);
        }

        Map<String, Metric> result = new HashMap<>();
        result.put(SvCaliberIndicator.CODE_SEEDLING_QTY, seedlingMetric(start, end, pondById));
        result.put(SvCaliberIndicator.CODE_MEDICATION_QTY, medicationMetric(start, end, pondById));
        result.put(SvCaliberIndicator.CODE_MORTALITY_RATE,
                mortalityMetric(start, end, pondById, result.get(SvCaliberIndicator.CODE_SEEDLING_QTY)));
        result.put(SvCaliberIndicator.CODE_HARVEST_QTY, harvestMetric(start, end, pondById));
        result.put(SvCaliberIndicator.CODE_INSPECTION_CONCLUSION, inspectionMetric(start, end, pondById));
        return result;
    }

    private boolean inPeriod(LocalDate date, LocalDate start, LocalDate end) {
        return date != null && !date.isBefore(start) && date.isBefore(end);
    }

    private Metric seedlingMetric(LocalDate start, LocalDate end, Map<Long, Pond> pondById) {
        Metric m = new Metric();
        m.indicatorCode = SvCaliberIndicator.CODE_SEEDLING_QTY;
        long sum = 0L;
        List<SeedlingBatch> batches = batchMapper.selectList(new QueryWrapper<SeedlingBatch>());
        for (SeedlingBatch batch : batches) {
            if (pondById.containsKey(batch.getPondId()) && inPeriod(batch.getStockDate(), start, end)) {
                sum += batch.getQuantity() == null ? 0 : batch.getQuantity();
                m.recordCount++;
            }
        }
        if (m.recordCount == 0) {
            m.missing = true;
            m.pendingReason = "报告期内无投苗记录（t_seedling_batch 无命中行），标待补而非0";
        } else {
            m.value = String.valueOf(sum);
        }
        return m;
    }

    private Metric medicationMetric(LocalDate start, LocalDate end, Map<Long, Pond> pondById) {
        Metric m = new Metric();
        m.indicatorCode = SvCaliberIndicator.CODE_MEDICATION_QTY;
        List<MedicationRecord> records = medicationMapper.selectList(new QueryWrapper<MedicationRecord>());
        for (MedicationRecord record : records) {
            if (pondById.containsKey(record.getPondId()) && inPeriod(record.getUseDate(), start, end)) {
                m.recordCount++;
            }
        }
        if (m.recordCount == 0) {
            m.missing = true;
            m.pendingReason = "报告期内无用药记录（t_medication_record 无命中行），标待补而非0";
        } else {
            // 台账用药记录无用量数值列，按口径以用药次数（记录条数）计
            m.value = String.valueOf(m.recordCount);
        }
        return m;
    }

    private Metric mortalityMetric(LocalDate start, LocalDate end, Map<Long, Pond> pondById,
                                   Metric seedling) {
        Metric m = new Metric();
        m.indicatorCode = SvCaliberIndicator.CODE_MORTALITY_RATE;
        long deathSum = 0L;
        List<DailyRecord> records = dailyMapper.selectList(new QueryWrapper<DailyRecord>());
        for (DailyRecord record : records) {
            if (pondById.containsKey(record.getPondId()) && inPeriod(record.getRecordDate(), start, end)) {
                deathSum += record.getMortalityCount() == null ? 0 : record.getMortalityCount();
                m.recordCount++;
            }
        }
        if (m.recordCount == 0) {
            m.missing = true;
            m.pendingReason = "报告期内无日常死亡采样记录（t_daily_record 无命中行），死亡率标待补而非0";
            return m;
        }
        // 分母取当月投苗量；无投苗量（0 或待补）则死亡率无法计算，按待补处理而非伪造 0%
        long denominator = (seedling != null && seedling.value != null)
                ? Long.parseLong(seedling.value) : 0L;
        if (denominator <= 0L) {
            m.missing = true;
            m.pendingReason = "有死亡采样但报告期投苗量分母为0/待补，死亡率无法计算，标待补";
            return m;
        }
        double rate = deathSum * 100.0d / denominator;
        m.value = String.format(java.util.Locale.ROOT, "%.2f", rate);
        return m;
    }

    private Metric harvestMetric(LocalDate start, LocalDate end, Map<Long, Pond> pondById) {
        Metric m = new Metric();
        m.indicatorCode = SvCaliberIndicator.CODE_HARVEST_QTY;
        long sum = 0L;
        // 出池记录挂批次，经批次定位池塘（兼容换池后的历史批次）
        Map<Long, Long> batchPond = new HashMap<>();
        List<SeedlingBatch> batches = batchMapper.selectList(new QueryWrapper<SeedlingBatch>());
        for (SeedlingBatch batch : batches) {
            batchPond.put(batch.getId(), batch.getPondId());
        }
        List<HarvestRecord> records = harvestMapper.selectList(new QueryWrapper<HarvestRecord>());
        for (HarvestRecord record : records) {
            Long pondId = record.getPondId() != null ? record.getPondId()
                    : batchPond.get(record.getBatchId());
            if (pondId != null && pondById.containsKey(pondId)
                    && inPeriod(record.getHarvestDate(), start, end)) {
                sum += record.getQuantity() == null ? 0 : record.getQuantity();
                m.recordCount++;
            }
        }
        if (m.recordCount == 0) {
            m.missing = true;
            m.pendingReason = "报告期内无出池记录（t_harvest_record 无命中行），标待补而非0";
        } else {
            m.value = String.valueOf(sum);
        }
        return m;
    }

    private Metric inspectionMetric(LocalDate start, LocalDate end, Map<Long, Pond> pondById) {
        Metric m = new Metric();
        m.indicatorCode = SvCaliberIndicator.CODE_INSPECTION_CONCLUSION;
        int qualified = 0;
        List<InspectionRecord> records = inspectionMapper.selectList(new QueryWrapper<InspectionRecord>());
        for (InspectionRecord record : records) {
            if (pondById.containsKey(record.getPondId()) && inPeriod(record.getInspectDate(), start, end)) {
                m.recordCount++;
                if (isQualified(record.getResult())) {
                    qualified++;
                }
            }
        }
        if (m.recordCount == 0) {
            m.missing = true;
            m.pendingReason = "报告期内无抽检记录（t_inspection_record 无命中行），检验结论标待补而非0";
        } else {
            // 结论口径：合格条数/抽检总条数（result 含“不合格/异常/FAIL”判为不合格，其余判合格）
            m.value = qualified + "/" + m.recordCount;
        }
        return m;
    }

    private boolean isQualified(String result) {
        if (result == null || result.trim().isEmpty()) {
            return true;
        }
        String r = result.trim();
        return !(r.contains("不合格") || r.contains("异常") || r.toUpperCase().contains("FAIL"));
    }
}
