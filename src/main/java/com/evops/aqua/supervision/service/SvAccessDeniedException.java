package com.evops.aqua.supervision.service;

/**
 * 海水养殖管理用户对监管月报功能/数据无权时抛出。
 * 与参数非法区分：这是入口授权拒绝（HTTP 语义 403）。
 */
public class SvAccessDeniedException extends RuntimeException {
    public SvAccessDeniedException(String message) {
        super(message);
    }
}
