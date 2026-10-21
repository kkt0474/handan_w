package com.handan.web;

import com.handan.order.OrderProcessor;
import com.handan.progress.ExecutionProgress;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单模块控制器
 */
@RestController
@RequestMapping("/api/order")
@CrossOrigin(origins = "*") // 允许前端本地跨域调用
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderProcessor orderProcessor;

    @Value("${handan.workspace.data-dir:./data}")
    private String defaultDataDir;

    @Value("${handan.workspace.config-path:./mapping-config.yaml}")
    private String configPath;

    public OrderController(OrderProcessor orderProcessor) {
        this.orderProcessor = orderProcessor;
    }

    /**
     * 获取当前系统配置的默认数据文件夹路径
     */
    @GetMapping("/config")
    public ResponseEntity<?> getConfig() {
        File dataDir = new File(defaultDataDir);
        File configFile = new File(configPath);
        return ResponseEntity.ok(java.util.Map.of(
                "dataDir", dataDir.getAbsolutePath(),
                "configPath", configFile.getAbsolutePath(),
                "existsDataDir", dataDir.exists(),
                "existsConfigFile", configFile.exists()));
    }

    /**
     * 执行订单数据抽取与追加写入
     */
    @PostMapping("/execute")
    public ResponseEntity<OrderExecuteResponse> executeOrder(@RequestBody OrderExecuteRequest request) {
        List<ExecutionProgress> logHistory = new ArrayList<>();

        if (request.customer() == null || request.customer().trim().isEmpty()) {
            var p = ExecutionProgress.failed("VALIDATION", "参数校验", "客户名称为必填项，请输入后再执行。");
            logHistory.add(p);
            return ResponseEntity.badRequest().body(OrderExecuteResponse.failure("客户名称必填", logHistory));
        }

        if (request.orderNo() == null || request.orderNo().trim().isEmpty()) {
            var p = ExecutionProgress.failed("VALIDATION", "参数校验", "订单号为必填项，请输入后再执行。");
            logHistory.add(p);
            return ResponseEntity.badRequest().body(OrderExecuteResponse.failure("订单号必填", logHistory));
        }

        // 确定工作目录：支持优先使用前端传来的自定义目录，否则取 application.yml 默认目录
        String dirPath = (request.customDir() != null && !request.customDir().isBlank())
                ? request.customDir().trim()
                : defaultDataDir;
        File workDir = new File(dirPath);

        if (!workDir.exists() || !workDir.isDirectory()) {
            var p = ExecutionProgress.failed("DIR_NOT_FOUND", "文件夹检查",
                    "指定的工作文件夹不存在: " + workDir.getAbsolutePath() + "，请确认路径配置。");
            logHistory.add(p);
            return ResponseEntity.ok(OrderExecuteResponse.failure("目标文件夹不存在", logHistory));
        }

        // 确定单据文件查找策略（AUTO / PDF / PNG）
        String requestedType = (request.fileType() != null && !request.fileType().isBlank())
                ? request.fileType().trim().toUpperCase()
                : "AUTO";

        File[] matchingFiles;
        if ("PNG".equals(requestedType)) {
            matchingFiles = workDir.listFiles((dir, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
            });
            if (matchingFiles == null || matchingFiles.length == 0) {
                var p = ExecutionProgress.failed("PNG_NOT_FOUND", "文件检查",
                        "在工作目录【" + workDir.getAbsolutePath() + "】下未检测到任何 .png 单据图片，请先放入图片。");
                logHistory.add(p);
                return ResponseEntity.ok(OrderExecuteResponse.failure("未找到 PNG 图片文件", logHistory));
            }
        } else if ("PDF".equals(requestedType)) {
            matchingFiles = workDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
            if (matchingFiles == null || matchingFiles.length == 0) {
                var p = ExecutionProgress.failed("PDF_NOT_FOUND", "文件检查",
                        "在工作目录【" + workDir.getAbsolutePath() + "】下未检测到任何 .pdf 单据文件，请先放入 PDF。");
                logHistory.add(p);
                return ResponseEntity.ok(OrderExecuteResponse.failure("未找到 PDF 文件", logHistory));
            }
        } else {
            // AUTO 模式：同时检索 .pdf 和 .png / .jpg，优先取最新修改的文件
            matchingFiles = workDir.listFiles((dir, name) -> {
                String n = name.toLowerCase();
                return n.endsWith(".pdf") || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
            });
            if (matchingFiles == null || matchingFiles.length == 0) {
                var p = ExecutionProgress.failed("FILE_NOT_FOUND", "文件检查",
                        "在工作目录【" + workDir.getAbsolutePath() + "】下未检测到任何 .pdf 或 .png 单据文件，请先放入单据。");
                logHistory.add(p);
                return ResponseEntity.ok(OrderExecuteResponse.failure("未找到单据文件(PDF/PNG)", logHistory));
            }
        }

        // 取最新修改的单据文件
        Arrays.sort(matchingFiles, Comparator.comparingLong(File::lastModified).reversed());
        File targetFile = matchingFiles[0];

        // 检查 mapping 配置文件
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            var p = ExecutionProgress.failed("CONFIG_NOT_FOUND", "配置文件检查",
                    "未找到映射配置文件: " + configFile.getAbsolutePath() + "，请确认文件是否存在。");
            logHistory.add(p);
            return ResponseEntity.ok(OrderExecuteResponse.failure("配置文件不存在", logHistory));
        }

        try {
            // 执行处理并记录进度日志（包含总金额校验）
            File resultFile = orderProcessor.process(
                    targetFile,
                    configFile,
                    request.customer().trim(),
                    request.orderNo().trim(),
                    request.checkTotalAmount(),
                    progress -> {
                        // 同时输出到 IDE 控制台与前端响应日志
                        logHistory.add(progress);
                        String tag = switch (progress.status()) {
                            case RUNNING -> "[进行中]";
                            case SUCCESS -> "[成功]";
                            case FAILED -> "[异常]";
                        };
                        System.out.printf("[%s] %s 【%s】: %s%n",
                                progress.timestamp(), tag, progress.stageName(), progress.message());
                    });

            return ResponseEntity.ok(OrderExecuteResponse.success(
                    "处理完成！成功追加数据并生成新文件。",
                    resultFile.getName(),
                    logHistory));
        } catch (Exception e) {
            // 在 IDE 控制台打印完整的异常堆栈信息，便于调试
            System.err.println("================================================================================");
            System.err.println("【执行异常报错】: " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.println("================================================================================");

            // 同时记入 slf4j 日志
            log.error("订单执行发生异常: {}", e.getMessage(), e);

            return ResponseEntity.ok(OrderExecuteResponse.failure(
                    "执行过程中发生异常: " + e.getMessage(),
                    logHistory));
        }
    }
}
