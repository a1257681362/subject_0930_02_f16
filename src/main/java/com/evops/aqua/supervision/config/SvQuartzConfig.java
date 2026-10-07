package com.evops.aqua.supervision.config;

import com.evops.aqua.supervision.service.SvReportGenerationService;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.MethodInvokingJobDetailFactoryBean;

/**
 * 水产监管月报定时生成：每月按 cron 扫描各海区生成当月月报（任务幂等，重复触发不多建），
 * 同一次调度随后执行到期任务（含历史失败任务重试）。
 * misfire 不补触发风暴：错过等下一周期；单条海区任务失败被隔离，不影响其他海区。
 */
@Configuration
public class SvQuartzConfig {

    @Bean
    public MethodInvokingJobDetailFactoryBean svMonthlyReportJobDetail(SvReportGenerationService service) {
        MethodInvokingJobDetailFactoryBean factory = new MethodInvokingJobDetailFactoryBean();
        factory.setTargetObject(service);
        factory.setTargetMethod("runScheduledGeneration");
        factory.setName("svMonthlyReportJob");
        factory.setGroup("aqua-supervision");
        factory.setConcurrent(false);
        return factory;
    }

    @Bean
    public CronTriggerFactoryBean svMonthlyReportTrigger(
            @Qualifier("svMonthlyReportJobDetail") JobDetail jobDetail,
            @Value("${aqua.supervision.monthly-cron:0 0 3 1 * ?}") String cron) {
        CronTriggerFactoryBean factory = new CronTriggerFactoryBean();
        factory.setJobDetail(jobDetail);
        factory.setName("svMonthlyReportTrigger");
        factory.setGroup("aqua-supervision");
        factory.setCronExpression(cron);
        factory.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING);
        return factory;
    }
}
