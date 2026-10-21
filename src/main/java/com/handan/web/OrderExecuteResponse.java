package com.handan.web;

import com.handan.progress.ExecutionProgress;
import java.util.List;

/**
 * 订单执行响应结果
 */
public record OrderExecuteResponse(
        boolean success,
        String message,
        String generatedFile,
        List<ExecutionProgress> logs
) {
    public static OrderExecuteResponse success(String message, String generatedFile, List<ExecutionProgress> logs) {
        return new OrderExecuteResponse(true, message, generatedFile, logs);
    }

    public static OrderExecuteResponse failure(String message, List<ExecutionProgress> logs) {
        return new OrderExecuteResponse(false, message, null, logs);
    }
}
