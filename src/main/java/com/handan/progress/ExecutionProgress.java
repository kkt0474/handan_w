package com.handan.progress;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户友好进度事件
 * 专为前端界面展示设计，用直白通俗的业务语言描述程序当前行为与结果
 *
 * @param stageCode 步骤代码标识（如：CONFIG_CHECK, ROUTE_DETECT, PDF_EXTRACT, EXCEL_APPEND）
 * @param stageName 步骤中文名称（如：配置检查、单据形态分析、数据抽取、表格追加）
 * @param message 通俗易懂的用户友好提示文案（给非技术人员直接阅读）
 * @param status 状态（RUNNING, SUCCESS, FAILED）
 * @param timestamp 事件发生的时间戳
 */
public record ExecutionProgress(
        String stageCode,
        String stageName,
        String message,
        ProgressStatus status,
        String timestamp
) {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static ExecutionProgress running(String stageCode, String stageName, String message) {
        return new ExecutionProgress(stageCode, stageName, message, ProgressStatus.RUNNING, LocalDateTime.now().format(FORMATTER));
    }

    public static ExecutionProgress success(String stageCode, String stageName, String message) {
        return new ExecutionProgress(stageCode, stageName, message, ProgressStatus.SUCCESS, LocalDateTime.now().format(FORMATTER));
    }

    public static ExecutionProgress failed(String stageCode, String stageName, String message) {
        return new ExecutionProgress(stageCode, stageName, message, ProgressStatus.FAILED, LocalDateTime.now().format(FORMATTER));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 建造者模式（Builder Pattern），用于灵活构建复杂的进度事件
     */
    public static class Builder {
        private String stageCode;
        private String stageName;
        private String message;
        private ProgressStatus status = ProgressStatus.RUNNING;
        private String timestamp;

        public Builder stageCode(String stageCode) {
            this.stageCode = stageCode;
            return this;
        }

        public Builder stageName(String stageName) {
            this.stageName = stageName;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder status(ProgressStatus status) {
            this.status = status;
            return this;
        }

        public Builder timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public ExecutionProgress build() {
            String ts = (timestamp != null && !timestamp.isBlank())
                    ? timestamp
                    : LocalDateTime.now().format(FORMATTER);
            return new ExecutionProgress(stageCode, stageName, message, status, ts);
        }
    }
}
