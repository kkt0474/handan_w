package com.handan.progress;

/**
 * 进度监听器接口
 * 供控制台、WebSocket 或前端界面消费业务进度日志
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * 当任务进度产生变更时被回调
     *
     * @param progress 结构化的进度事件
     */
    void onProgress(ExecutionProgress progress);

    /**
     * 默认控制台打印监听器
     */
    static ProgressListener console() {
        return progress -> {
            String tag = switch (progress.status()) {
                case RUNNING -> "[进行中]";
                case SUCCESS -> "[成功]";
                case FAILED -> "[异常]";
            };
            System.out.printf("[%s] %s 【%s】: %s%n",
                    progress.timestamp(), tag, progress.stageName(), progress.message());
        };
    }
}
