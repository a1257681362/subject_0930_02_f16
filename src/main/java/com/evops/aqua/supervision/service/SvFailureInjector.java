package com.evops.aqua.supervision.service;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 月报生成故障注入演练开关（仅用于验收/演练，非生产逻辑）。
 * 参照履约模块 sim 网关的 up/down 演练端点：对指定 报告期+海区 注入一次生成异常，
 * 以验证失败任务保留完整异常链路（含根因 cause）、attempts/retry_count 累加与 Quartz 重试恢复。
 * 关闭后下一次执行即恢复成功。
 */
@Component
public class SvFailureInjector {

    private final Set<String> injected = new HashSet<>();

    private static String key(String reportMonth, String seaArea) {
        return reportMonth + "|" + seaArea;
    }

    public void enable(String reportMonth, String seaArea) {
        injected.add(key(reportMonth, seaArea));
    }

    public void disable(String reportMonth, String seaArea) {
        injected.remove(key(reportMonth, seaArea));
    }

    public boolean isInjected(String reportMonth, String seaArea) {
        return injected.contains(key(reportMonth, seaArea));
    }
}
