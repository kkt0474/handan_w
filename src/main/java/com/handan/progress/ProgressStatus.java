package com.handan.progress;

/**
 * 进度状态枚举
 * 标识当前业务步骤的处理状态
 */
public enum ProgressStatus {
    /** 步骤正在执行中 */
    RUNNING,
    /** 步骤执行成功完成 */
    SUCCESS,
    /** 步骤执行出现异常或失败 */
    FAILED
}
