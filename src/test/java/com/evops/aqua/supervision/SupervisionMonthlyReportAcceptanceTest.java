package com.evops.aqua.supervision;

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
import com.evops.aqua.supervision.entity.SvGenerateTask;
import com.evops.aqua.supervision.entity.SvReport;
import com.evops.aqua.supervision.entity.SvReportIndicator;
import com.evops.aqua.supervision.entity.SvTaskAttempt;
import com.evops.aqua.supervision.mapper.SvGenerateTaskMapper;
import com.evops.aqua.supervision.mapper.SvReportIndicatorMapper;
import com.evops.aqua.supervision.mapper.SvReportMapper;
import com.evops.aqua.supervision.mapper.SvTaskAttemptMapper;
import com.evops.aqua.supervision.service.SvReportGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 水产监管月报验收（aqua-16）：
 * 五指标口径版本化、Quartz/人工生成固化快照、缺采样标待补不填零、重新生成只产生新版本、
 * 导出按海区权限过滤、失败任务保留异常链路与重试次数；
 * 海水养殖管理缺权用户访问口径管理在服务层被拒，换调用地址同样不放行。
 * 任一检查点失败即停，报告给出停点与原因。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupervisionMonthlyReportAcceptanceTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PondMapper pondMapper;
    @Autowired private SeedlingBatchMapper batchMapper;
    @Autowired private DailyRecordMapper dailyMapper;
    @Autowired private MedicationRecordMapper medicationMapper;
    @Autowired private HarvestRecordMapper harvestMapper;
    @Autowired private InspectionRecordMapper inspectionMapper;
    @Autowired private SvReportMapper reportMapper;
    @Autowired private SvReportIndicatorMapper indicatorMapper;
    @Autowired private SvGenerateTaskMapper taskMapper;
    @Autowired private SvTaskAttemptMapper attemptMapper;
    @Autowired private SvReportGenerationService generationService;

    private final List<String> report = new ArrayList<>();
    private String adminCookie;
    private String marineCookie;

    private static final String MONTH = "2026-09";
    private static final String AREA_DS = "监管东山湾海区";
    private static final String AREA_SS = "三沙湾海区";
    private static final String AREA_LY = "监管罗源湾海区";

    private Long dsPondId;
    private Long ssPondId;
    private Long lyPondId;
    private Long caliberV2Id;
    private Long dsReportId;
    private String dsSnapshotBeforeV3;

    @Test
    void acceptanceSupervisionMonthlyReport() {
        checkpoint("CP0", "登录：管理员 bootstrap 与海水养殖受限用户 marine", () -> {
            adminCookie = login("bootstrap", "bootstrap");
            marineCookie = login("marine", "marine");
            check(adminCookie != null && marineCookie != null, "两个用户会话均应建立");
        });

        checkpoint("CP1", "管理员发布口径新版本 v2：五指标口径齐全，旧 v1 自动停用", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/supervision/calibers",
                    caliberPublishBody("v2", "监管月报口径第二版"), adminCookie));
            assertSuccess(resp, "发布口径v2");
            caliberV2Id = resp.path("data").path("id").asLong();

            JsonNode inds = json(call(HttpMethod.GET,
                    "/api/aqua/supervision/calibers/" + caliberV2Id + "/indicators", null, adminCookie));
            assertSuccess(inds, "查询v2指标");
            check(inds.path("data").size() == 5, "口径版本应含5个指标，实际=" + inds.path("data").size());

            JsonNode versions = json(call(HttpMethod.GET, "/api/aqua/supervision/calibers", null, adminCookie));
            String v1status = null;
            String v2status = null;
            for (JsonNode v : versions.path("data")) {
                if ("v1".equals(v.path("versionNo").asText())) v1status = v.path("status").asText();
                if ("v2".equals(v.path("versionNo").asText())) v2status = v.path("status").asText();
            }
            check("SUPERSEDED".equals(v1status), "发布v2后旧v1应停用，实际=" + v1status);
            check("ACTIVE".equals(v2status), "v2应为生效版本，实际=" + v2status);
        });

        checkpoint("CP2", "缺权用户访问口径管理被入口拒绝，换调用地址也不放行，且留 DENY 证据", () -> {
            // 正式入口
            JsonNode main = json(call(HttpMethod.POST, "/api/aqua/supervision/calibers",
                    caliberPublishBody("v9-by-marine", "越权发布"), marineCookie));
            check(!main.path("success").asBoolean(true), "marine 从正式入口发布口径未被拒绝");
            check(main.path("message").asText().contains("水产监管月报口径管理"),
                    "拒绝提示应指向口径管理权限：" + main.path("message").asText());
            // 别名入口（换地址）
            JsonNode alias = json(call(HttpMethod.POST, "/api/aqua/supervision/caliber-manage/versions",
                    caliberPublishBody("v9-by-marine-alias", "越权发布换地址"), marineCookie));
            check(!alias.path("success").asBoolean(true), "marine 换调用地址仍被放行了");
            check(alias.path("message").asText().contains("更换调用地址同样不放行"),
                    "别名入口拒绝提示应声明换地址不放行：" + alias.path("message").asText());
            // 读入口也拒绝
            JsonNode read = json(call(HttpMethod.GET, "/api/aqua/supervision/calibers", null, marineCookie));
            check(!read.path("success").asBoolean(true), "marine 不应查看口径配置");
            // 越权发布未产生新版本（仍只有 v1/v2）
            JsonNode versionsAfter = json(call(HttpMethod.GET,
                    "/api/aqua/supervision/calibers", null, adminCookie));
            int versionCount = versionsAfter.path("data").size();
            check(versionCount == 2, "越权发布不得产生口径版本，当前版本数=" + versionCount);
            JsonNode denyEvidence = json(call(HttpMethod.GET,
                    "/api/aqua/supervision/evidence?entityType=ACCESS_GRANT", null, adminCookie));
            assertSuccess(denyEvidence, "查询拒绝证据");
            int denyCount = 0;
            for (JsonNode e : denyEvidence.path("data")) {
                if ("DENY".equals(e.path("action").asText())
                        && "marine".equals(e.path("operator").asText())) {
                    denyCount++;
                }
            }
            check(denyCount >= 3, "marine 至少应有3条DENY证据（正式/别名/读取），实际=" + denyCount);
        });

        checkpoint("CP3", "台账落数后生成月报：五指标取数正确，值非伪造", () -> {
            seedLedgerData();

            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/supervision/reports/generate",
                    generateBody(MONTH, AREA_DS, "REQ-DS-09-1"), adminCookie));
            assertSuccess(resp, "生成东山湾9月月报");
            check("SUCCESS".equals(resp.path("data").path("status").asText()),
                    "任务应成功，实际=" + resp.path("data").path("status").asText());

            SvReport report = currentReport(MONTH, AREA_DS);
            check(report != null, "东山湾9月当前版本月报应存在");
            dsReportId = report.getId();
            Map<String, SvReportIndicator> byCode = indicators(dsReportId);
            assertMetric(byCode, "SEEDLING_QTY", "100000", "OK");
            assertMetric(byCode, "MEDICATION_QTY", "1", "OK");
            assertMetric(byCode, "MORTALITY_RATE", "0.50", "OK");
            assertMetric(byCode, "HARVEST_QTY", "90000", "OK");
            assertMetric(byCode, "INSPECTION_CONCLUSION", "1/2", "OK");
        });

        checkpoint("CP4", "Quartz 生成后固化快照：逐指标冻结口径版本与口径 JSON，不随后续版本改变", () -> {
            Map<String, SvReportIndicator> byCode = indicators(dsReportId);
            for (String code : new String[]{"SEEDLING_QTY", "MEDICATION_QTY", "MORTALITY_RATE",
                    "HARVEST_QTY", "INSPECTION_CONCLUSION"}) {
                SvReportIndicator ind = byCode.get(code);
                check(ind.getCaliberVersionId().equals(caliberV2Id),
                        code + " 应冻结口径版本v2(id=" + caliberV2Id + ")，实际=" + ind.getCaliberVersionId());
                check("v2".equals(ind.getCaliberVersionNo()), code + " 口径版本号应为v2");
                check(ind.getCaliberSnapshotJson() != null && ind.getCaliberSnapshotJson().contains("aggExpression"),
                        code + " 应固化口径JSON快照");
            }
            SvReport head = reportMapper.selectById(dsReportId);
            check(head.getSnapshotJson() != null && head.getSnapshotJson().contains("\"caliberVersionNo\":\"v2\""),
                    "月报头应固化整版快照（含v2口径）");
            check(head.getFrozenTime() != null, "应有固化时间");
            dsSnapshotBeforeV3 = head.getSnapshotJson();
        });

        checkpoint("CP5", "缺采样数据标待补而非填零：三沙湾仅有投苗与出池，其余三指标待补且值为空", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/supervision/reports/generate",
                    generateBody(MONTH, AREA_SS, "REQ-SS-09-1"), adminCookie));
            assertSuccess(resp, "生成三沙湾9月月报");
            SvReport ss = currentReport(MONTH, AREA_SS);
            check(ss != null, "三沙湾9月月报应存在");
            check(Integer.valueOf(3).equals(ss.getPendingCount()),
                    "待补指标数应为3，实际=" + ss.getPendingCount());
            Map<String, SvReportIndicator> byCode = indicators(ss.getId());
            assertMetric(byCode, "SEEDLING_QTY", "50000", "OK");
            assertMetric(byCode, "HARVEST_QTY", "48000", "OK");
            for (String code : new String[]{"MEDICATION_QTY", "MORTALITY_RATE", "INSPECTION_CONCLUSION"}) {
                SvReportIndicator ind = byCode.get(code);
                check("PENDING_SUPPLEMENT".equals(ind.getDataStatus()),
                        code + " 应为待补，实际=" + ind.getDataStatus());
                check(ind.getIndicatorValue() == null,
                        code + " 待补时值必须为空（不得填0），实际=" + ind.getIndicatorValue());
                check(ind.getPendingReason() != null && !ind.getPendingReason().isEmpty(),
                        code + " 待补应说明原因");
            }
        });

        checkpoint("CP6", "重新生成只产生新版本：旧版去当前标记但原样保留，历史版本数递增", () -> {
            JsonNode resp = json(call(HttpMethod.POST, "/api/aqua/supervision/reports/generate",
                    generateBody(MONTH, AREA_DS, "REQ-DS-09-2"), adminCookie));
            assertSuccess(resp, "重新生成东山湾9月月报");
            List<SvReport> all = reportMapper.selectList(new QueryWrapper<SvReport>()
                    .eq("report_month", MONTH).eq("sea_area", AREA_DS).orderByAsc("version_no"));
            check(all.size() == 2, "同报告期同海区应有2个版本，实际=" + all.size());
            check(Integer.valueOf(1).equals(all.get(0).getVersionNo())
                            && Boolean.FALSE.equals(all.get(0).getCurrentFlag()),
                    "旧版应保留且取消当前标记");
            check(Integer.valueOf(2).equals(all.get(1).getVersionNo())
                            && Boolean.TRUE.equals(all.get(1).getCurrentFlag()),
                    "新版应为版本2且当前");
            dsReportId = all.get(1).getId();
            // 旧版指标行仍在且未被改写
            Long oldIndCount = indicatorMapper.selectCount(new QueryWrapper<SvReportIndicator>()
                    .eq("report_id", all.get(0).getId()));
            check(oldIndCount == 5L, "旧版5条指标行应原样保留，实际=" + oldIndCount);
            // 刷新快照基线为当前（第2版），供 CP7 验证 v3 发布后当前月报快照不变
            dsSnapshotBeforeV3 = reportMapper.selectById(dsReportId).getSnapshotJson();
        });

        checkpoint("CP7", "再发布口径 v3 不回改已固化月报：历史月报仍引用 v2 快照", () -> {
            JsonNode pub = json(call(HttpMethod.POST, "/api/aqua/supervision/calibers",
                    caliberPublishBody("v3", "监管月报口径第三版"), adminCookie));
            assertSuccess(pub, "发布口径v3");
            SvReport head = reportMapper.selectById(dsReportId);
            check("v2".equals(/* 旧版仍冻结v2 */ indicatorOf(dsReportId, "SEEDLING_QTY").getCaliberVersionNo()),
                    "已生成月报口径不应被v3回改");
            check(head.getSnapshotJson().equals(dsSnapshotBeforeV3),
                    "v3发布后月报整版快照必须保持不变");
        });

        checkpoint("CP8", "导出/查询按海区权限过滤：marine 只见三沙湾，越权详情被拒，CSV 与列表同口径", () -> {
            // 罗源湾（无任何用户可见之外的第三海区，bootstrap 可见）先生成
            JsonNode ly = json(call(HttpMethod.POST, "/api/aqua/supervision/reports/generate",
                    generateBody(MONTH, AREA_LY, "REQ-LY-09-1"), adminCookie));
            assertSuccess(ly, "生成罗源湾月报");

            JsonNode marineList = json(call(HttpMethod.GET,
                    "/api/aqua/supervision/reports?currentOnly=true", null, marineCookie));
            assertSuccess(marineList, "marine 列表");
            List<String> marineAreas = new ArrayList<>();
            for (JsonNode r : marineList.path("data")) {
                marineAreas.add(r.path("seaArea").asText());
            }
            check(marineAreas.contains(AREA_SS), "marine 应可见三沙湾");
            check(!marineAreas.contains(AREA_DS), "marine 不应见到东山湾");
            check(!marineAreas.contains(AREA_LY), "marine 不应见到罗源湾");

            ResponseEntity<String> csvResp = call(HttpMethod.GET,
                    "/api/aqua/supervision/reports/export?currentOnly=true", null, marineCookie);
            String csv = csvResp.getBody();
            check(csv.contains(AREA_SS), "CSV 应含三沙湾数据");
            check(!csv.contains(AREA_DS), "CSV 不应含东山湾数据");
            check(!csv.contains(AREA_LY), "CSV 不应含罗源湾数据");
            check(csv.contains("PENDING_SUPPLEMENT"), "CSV 应显式标注待补状态而非0");

            // 越权查看东山湾月报详情
            JsonNode denied = json(call(HttpMethod.GET,
                    "/api/aqua/supervision/reports/" + dsReportId, null, marineCookie));
            check(!denied.path("success").asBoolean(true), "marine 越权查看东山湾详情未被拒绝");

            // 管理员可见全部三个海区
            JsonNode adminList = json(call(HttpMethod.GET,
                    "/api/aqua/supervision/reports?currentOnly=true", null, adminCookie));
            int adminAreaCount;
            boolean dsVisible = false, lyVisible = false;
            for (JsonNode r : adminList.path("data")) {
                if (AREA_DS.equals(r.path("seaArea").asText())) dsVisible = true;
                if (AREA_LY.equals(r.path("seaArea").asText())) lyVisible = true;
            }
            check(dsVisible && lyVisible, "管理员应可见东山湾与罗源湾");
            adminAreaCount = adminList.path("data").size();
            check(adminAreaCount >= 3, "管理员至少可见3个海区当前月报，实际=" + adminAreaCount);
        });

        checkpoint("CP9", "失败任务保留完整异常链路与重试次数，解除故障后重试成功且无半成品版本", () -> {
            String failMonth = "2026-08";
            // 注入故障
            JsonNode inj = json(call(HttpMethod.POST, "/api/aqua/supervision/failure-inject",
                    injectBody(failMonth, AREA_DS, true), adminCookie));
            assertSuccess(inj, "注入故障");

            JsonNode gen = json(call(HttpMethod.POST, "/api/aqua/supervision/reports/generate",
                    generateBody(failMonth, AREA_DS, "REQ-DS-08-FAIL"), adminCookie));
            assertSuccess(gen, "触发生成（预期业务失败但任务返回）");
            Long taskId = gen.path("data").path("id").asLong();
            check("FAILED".equals(gen.path("data").path("status").asText()),
                    "任务状态应为FAILED，实际=" + gen.path("data").path("status").asText());
            check(gen.path("data").path("attempts").asInt() == 1, "首次执行 attempts 应为1");
            check(gen.path("data").path("retryCount").asInt() == 0, "首次失败重试次数应为0");
            check(gen.path("data").path("lastError").asText().contains("根因"),
                    "lastError 应保留异常根因链：" + gen.path("data").path("lastError").asText());
            String stack0 = gen.path("data").path("errorStack").asText();
            check(stack0.contains("IOException"),
                    "errorStack 应含完整异常堆栈链（IOException根因），实际=[" + stack0 + "]");
            // 失败时刻无半成品月报
            Long reportsAtFail = reportMapper.selectCount(new QueryWrapper<SvReport>()
                    .eq("report_month", failMonth).eq("sea_area", AREA_DS));
            check(reportsAtFail == 0L, "失败事务应回滚，不留半成品月报版本，实际=" + reportsAtFail);

            JsonNode detail = json(call(HttpMethod.GET,
                    "/api/aqua/supervision/tasks/" + taskId, null, adminCookie));
            JsonNode attemptNodes = detail.path("data").path("attempts");
            check(attemptNodes.size() == 1, "应有1条执行明细，实际=" + attemptNodes.size());
            check("FAIL".equals(attemptNodes.get(0).path("result").asText()),
                    "明细应记录FAIL");
            check(attemptNodes.get(0).path("errorStack").asText().contains("IOException"),
                    "执行明细应保留异常堆栈");

            // 未解除故障直接重试：再次失败，attempts=2 retryCount=1
            JsonNode retryStillFail = json(call(HttpMethod.POST,
                    "/api/aqua/supervision/reports/retry-scan", null, adminCookie));
            assertSuccess(retryStillFail, "重试扫描（故障仍在）");
            SvGenerateTask t1 = taskMapper.selectById(taskId);
            check(Integer.valueOf(2).equals(t1.getAttempts()), "第二次执行 attempts 应为2，实际=" + t1.getAttempts());
            check(Integer.valueOf(1).equals(t1.getRetryCount()), "retryCount 应为1，实际=" + t1.getRetryCount());

            // 解除故障后重试成功
            assertSuccess(json(call(HttpMethod.POST, "/api/aqua/supervision/failure-inject",
                    injectBody(failMonth, AREA_DS, false), adminCookie)), "解除故障");
            JsonNode retryOk = json(call(HttpMethod.POST,
                    "/api/aqua/supervision/reports/retry-scan", null, adminCookie));
            assertSuccess(retryOk, "重试扫描（故障解除）");
            SvGenerateTask t2 = taskMapper.selectById(taskId);
            check("SUCCESS".equals(t2.getStatus()), "重试后任务应成功，实际=" + t2.getStatus());
            check(t2.getReportId() != null, "成功任务应关联月报");
            List<SvTaskAttempt> attempts = attemptMapper.selectList(
                    new QueryWrapper<SvTaskAttempt>().eq("task_id", taskId).orderByAsc("attempt_no"));
            check(attempts.size() == 3, "应保留3次执行明细（2失败+1成功），实际=" + attempts.size());
            check("SUCCESS".equals(attempts.get(2).getResult()), "第3次应成功");
            Long reportsOk = reportMapper.selectCount(new QueryWrapper<SvReport>()
                    .eq("report_month", failMonth).eq("sea_area", AREA_DS));
            check(reportsOk == 1L, "最终应只有1个成功版本（失败不留半版），实际=" + reportsOk);
        });

        checkpoint("CP10", "Quartz 定时扫描幂等：重复调度同海区不重复建任务、不重复出版本", () -> {
            String currentMonth = YearMonth.now().toString();
            generationService.runScheduledGeneration();
            generationService.runScheduledGeneration();
            List<SvGenerateTask> scheduled = taskMapper.selectList(new QueryWrapper<SvGenerateTask>()
                    .eq("report_month", currentMonth).eq("sea_area", AREA_DS)
                    .eq("trigger_source", "SCHEDULED"));
            check(scheduled.size() == 1, "同海区当月定时任务应只有1条，实际=" + scheduled.size());
            List<SvReport> reports = reportMapper.selectList(new QueryWrapper<SvReport>()
                    .eq("report_month", currentMonth).eq("sea_area", AREA_DS));
            check(reports.size() == 1, "重复调度不应产生重复月报版本，实际=" + reports.size());
        });

        report.add("验收结论：全部检查点通过，水产监管月报口径版本化/快照固化/待补/版本递增/海区过滤/失败重试链路均成立");
        printReport();
    }

    // ----------------- 台账造数 -----------------

    private void seedLedgerData() {
        dsPondId = insertPond("SV-POND-DS", AREA_DS);
        ssPondId = insertPond("SV-POND-SS", AREA_SS);
        lyPondId = insertPond("SV-POND-LY", AREA_LY);

        Long dsBatch = insertBatch("SV-B-DS", dsPondId, 100000, LocalDate.of(2026, 9, 5));
        Long ssBatch = insertBatch("SV-B-SS", ssPondId, 50000, LocalDate.of(2026, 9, 3));
        insertBatch("SV-B-LY", lyPondId, 8000, LocalDate.of(2026, 9, 9));

        // 东山湾：死亡 500、用药 1 次、出池 90000、抽检 2 次（1 合格 1 不合格）
        insertDaily(dsPondId, dsBatch, LocalDate.of(2026, 9, 10), 500);
        insertMedication(dsPondId, dsBatch, LocalDate.of(2026, 9, 8));
        insertHarvest(dsPondId, dsBatch, LocalDate.of(2026, 9, 20), 90000);
        insertInspection(dsPondId, dsBatch, LocalDate.of(2026, 9, 12), "合格");
        insertInspection(dsPondId, dsBatch, LocalDate.of(2026, 9, 15), "不合格");
        // 三沙湾：仅出池 48000（投苗已有），无日常/用药/抽检 → 三指标待补
        insertHarvest(ssPondId, ssBatch, LocalDate.of(2026, 9, 25), 48000);
    }

    private Long insertPond(String code, String area) {
        Pond pond = new Pond();
        pond.setPondCode(code);
        pond.setPondName(code + "-名称");
        pond.setSeaArea(area);
        pond.setWaterDepthM(new java.math.BigDecimal("2.00"));
        pond.setSalinityPpt(new java.math.BigDecimal("28.0"));
        pond.setStatus(Pond.STATUS_ACTIVE);
        pondMapper.insert(pond);
        return pond.getId();
    }

    private Long insertBatch(String no, Long pondId, int qty, LocalDate stockDate) {
        SeedlingBatch b = new SeedlingBatch();
        b.setBatchNo(no);
        b.setPondId(pondId);
        b.setSpecies("南美白对虾");
        b.setSeedSource("监管验收育苗场");
        b.setQuantity(qty);
        b.setStockDate(stockDate);
        b.setPlannedHarvestDate(stockDate.plusMonths(4));
        b.setStatus(SeedlingBatch.STATUS_STOCKED);
        batchMapper.insert(b);
        return b.getId();
    }

    private void insertDaily(Long pondId, Long batchId, LocalDate date, int mortality) {
        DailyRecord d = new DailyRecord();
        d.setPondId(pondId);
        d.setBatchId(batchId);
        d.setRecordDate(date);
        d.setMortalityCount(mortality);
        dailyMapper.insert(d);
    }

    private void insertMedication(Long pondId, Long batchId, LocalDate date) {
        MedicationRecord m = new MedicationRecord();
        m.setPondId(pondId);
        m.setBatchId(batchId);
        m.setUseDate(date);
        m.setMedicineName("聚维酮碘");
        m.setDosage("0.5ppm");
        medicationMapper.insert(m);
    }

    private void insertHarvest(Long pondId, Long batchId, LocalDate date, int qty) {
        HarvestRecord h = new HarvestRecord();
        h.setPondId(pondId);
        h.setBatchId(batchId);
        h.setHarvestDate(date);
        h.setQuantity(qty);
        harvestMapper.insert(h);
    }

    private void insertInspection(Long pondId, Long batchId, LocalDate date, String result) {
        InspectionRecord i = new InspectionRecord();
        i.setPondId(pondId);
        i.setBatchId(batchId);
        i.setInspectDate(date);
        i.setSampleCount(30);
        i.setResult(result);
        inspectionMapper.insert(i);
    }

    // ----------------- 断言/工具 -----------------

    private SvReport currentReport(String month, String area) {
        List<SvReport> list = reportMapper.selectList(new QueryWrapper<SvReport>()
                .eq("report_month", month).eq("sea_area", area).eq("current_flag", true)
                .orderByDesc("version_no").last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, SvReportIndicator> indicators(Long reportId) {
        Map<String, SvReportIndicator> map = new HashMap<>();
        for (SvReportIndicator ind : indicatorMapper.selectList(
                new QueryWrapper<SvReportIndicator>().eq("report_id", reportId))) {
            map.put(ind.getIndicatorCode(), ind);
        }
        return map;
    }

    private SvReportIndicator indicatorOf(Long reportId, String code) {
        return indicators(reportId).get(code);
    }

    private void assertMetric(Map<String, SvReportIndicator> byCode, String code,
                              String expectedValue, String expectedStatus) {
        SvReportIndicator ind = byCode.get(code);
        check(ind != null, "缺少指标行：" + code);
        check(expectedStatus.equals(ind.getDataStatus()),
                code + " 状态应为" + expectedStatus + "，实际=" + ind.getDataStatus());
        if (expectedValue != null) {
            check(expectedValue.equals(ind.getIndicatorValue()),
                    code + " 值应为" + expectedValue + "，实际=" + ind.getIndicatorValue());
        }
    }

    private Map<String, Object> caliberPublishBody(String versionNo, String remark) {
        Map<String, Object> body = new HashMap<>();
        body.put("versionNo", versionNo);
        body.put("remark", remark);
        List<Map<String, Object>> inds = new ArrayList<>();
        inds.add(caliber("SEEDLING_QTY", "投苗量", "t_seedling_batch", "stock_date", "SUM(quantity)", "尾", 1));
        inds.add(caliber("MEDICATION_QTY", "用药量", "t_medication_record", "use_date", "COUNT(DISTINCT id)", "次", 2));
        inds.add(caliber("MORTALITY_RATE", "死亡率", "t_daily_record", "record_date", "SUM(mortality_count)/SUM(投苗量)*100", "%", 3));
        inds.add(caliber("HARVEST_QTY", "出池量", "t_harvest_record", "harvest_date", "SUM(quantity)", "尾", 4));
        inds.add(caliber("INSPECTION_CONCLUSION", "检验结论", "t_inspection_record", "inspect_date", "合格数/抽检次数", "条", 5));
        body.put("indicators", inds);
        return body;
    }

    private Map<String, Object> caliber(String code, String name, String table, String dateCol,
                                        String agg, String unit, int sort) {
        Map<String, Object> m = new HashMap<>();
        m.put("indicatorCode", code);
        m.put("indicatorName", name);
        m.put("sourceTable", table);
        m.put("dateColumn", dateCol);
        m.put("aggExpression", agg);
        m.put("unit", unit);
        m.put("missingRule", "当月无源记录则待补");
        m.put("sortNo", sort);
        return m;
    }

    private Map<String, Object> generateBody(String month, String area, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("reportMonth", month);
        body.put("seaArea", area);
        body.put("requestId", requestId);
        return body;
    }

    private Map<String, Object> injectBody(String month, String area, boolean enabled) {
        Map<String, Object> body = new HashMap<>();
        body.put("reportMonth", month);
        body.put("seaArea", area);
        body.put("enabled", enabled);
        return body;
    }

    private String login(String username, String password) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        ResponseEntity<String> resp = call(HttpMethod.POST, "/api/auth/login", body, null);
        JsonNode node = json(resp);
        if (!node.path("success").asBoolean(false)) {
            throw new IllegalStateException("登录失败：" + username);
        }
        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        return cookies == null || cookies.isEmpty() ? null : cookies.get(0).split(";")[0];
    }

    private void checkpoint(String id, String name, CheckpointBody body) {
        try {
            body.run();
            String line = "通过 " + id + " " + name;
            report.add(line);
            System.out.println("[验收] " + line);
        } catch (Throwable t) {
            String stop = "停点 " + id + "「" + name + "」失败，原因：" + t.getMessage();
            report.add(stop);
            report.add("验收结论：未通过，后续检查点未执行");
            printReport();
            fail("验收" + stop);
        }
    }

    private void printReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================ 水产监管月报验收报告 ================\n");
        for (String line : report) {
            sb.append(line).append('\n');
        }
        sb.append("=====================================================\n");
        System.out.println(sb);
    }

    private ResponseEntity<String> call(HttpMethod method, String url, Object body, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private void assertSuccess(JsonNode node, String what) {
        if (!node.path("success").asBoolean(false)) {
            throw new IllegalStateException(what + "接口返回失败：" + node.path("message").asText());
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private interface CheckpointBody {
        void run() throws Exception;
    }
}
