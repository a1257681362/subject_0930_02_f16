package com.evops.aqua.supervision.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 生成任务每次执行（含每次失败重试）的异常链路明细：只追加。
 * result=FAIL 时保留异常类型、摘要与完整 cause 链堆栈，成功记 SUCCESS。
 */
@TableName("t_sv_task_attempt")
public class SvTaskAttempt extends BaseEntity {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";

    private Long taskId;
    private Integer attemptNo;
    private String result;
    private String operator;
    private String errorType;
    private String errorMessage;
    private String errorStack;
    private LocalDateTime runTime;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getErrorStack() { return errorStack; }
    public void setErrorStack(String errorStack) { this.errorStack = errorStack; }
    public LocalDateTime getRunTime() { return runTime; }
    public void setRunTime(LocalDateTime runTime) { this.runTime = runTime; }
}
