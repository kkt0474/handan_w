package com.handan.web;

import com.handan.config.ExcelFilterProperties;
import com.handan.excel.ExcelAppendService;
import com.handan.progress.ExecutionProgress;
import com.handan.purchase.PurchaseProcessor;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采购单模块控制器
 */
@RestController
@RequestMapping("/api/purchase")
@CrossOrigin(origins = "*") // 允许前端本地跨域调用
public class PurchaseController {

    private static final Logger log = LoggerFactory.getLogger(PurchaseController.class);

    private final PurchaseProcessor purchaseProcessor;
    private final ExcelFilterProperties filterProperties;

    @Value("${handan.workspace.data-dir:./data}")
    private String defaultDataDir;

    public PurchaseController(PurchaseProcessor purchaseProcessor) {
        this(purchaseProcessor, new ExcelFilterProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PurchaseController(PurchaseProcessor purchaseProcessor, ExcelFilterProperties filterProperties) {
        this.purchaseProcessor = purchaseProcessor;
        this.filterProperties = filterProperties != null ? filterProperties : new ExcelFilterProperties();
    }

    /**
     * 获取指定工作目录下的可用 Excel 文件列表，并提供智能推荐选项
     * 具备深度权限探测与异常透传能力，杜绝静默失败
     */
    @GetMapping("/files")
    public ResponseEntity<PurchaseFilesResponse> listPurchaseFiles(@RequestParam(value = "customDir", required = false) String customDir) {
        String dirPath = (customDir != null && !customDir.isBlank()) ? customDir.trim() : defaultDataDir;
        File workDir = new File(dirPath);
        Path path = workDir.toPath();

        System.out.printf("[文件扫描] 收到采购单文件列表请求，目标工作目录: %s%n", workDir.getAbsolutePath());

        // 1. 物理存在性校验
        if (!Files.exists(path)) {
            String err = "【目录不存在 ⚠️】指定的工作目录不存在: " + workDir.getAbsolutePath() + "，请检查路径配置或在电脑中创建该文件夹。";
            System.err.println(err);
            log.warn(err);
            return ResponseEntity.ok(new PurchaseFilesResponse(Collections.emptyList(), null, null, workDir.getAbsolutePath(), err));
        }

        // 2. 文件夹类型校验
        if (!Files.isDirectory(path)) {
            String err = "【路径类型错误 ⚠️】配置的路径不是文件夹: " + workDir.getAbsolutePath();
            System.err.println(err);
            log.warn(err);
            return ResponseEntity.ok(new PurchaseFilesResponse(Collections.emptyList(), null, null, workDir.getAbsolutePath(), err));
        }

        // 3. 操作系统读权限前置探测
        if (!Files.isReadable(path)) {
            String err = "【权限拒绝 ❌】当前系统用户对目录【" + workDir.getAbsolutePath() + "】无读取权限 (isReadable = false)！请检查 macOS 文件夹权限设置（如 chmod +rx）。";
            System.err.println(err);
            log.error(err);
            return ResponseEntity.ok(new PurchaseFilesResponse(Collections.emptyList(), null, null, workDir.getAbsolutePath(), err));
        }

        // 4. 使用 NIO DirectoryStream 安全遍历，能主动捕获 OS 级的 AccessDeniedException
        List<File> rawFileList = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path p : stream) {
                rawFileList.add(p.toFile());
            }
        } catch (AccessDeniedException e) {
            String err = "【系统权限拒绝 ❌】访问目录【" + workDir.getAbsolutePath() + "】被操作系统拒绝: " + e.getMessage()
                    + "。请检查文件夹的操作系统属主与读写执行权限（例如终端运行: chmod -R 755 " + workDir.getAbsolutePath() + "）。";
            System.err.println(err);
            log.error(err, e);
            return ResponseEntity.ok(new PurchaseFilesResponse(Collections.emptyList(), null, null, workDir.getAbsolutePath(), err));
        } catch (Exception e) {
            String err = "【读取目录异常 ❌】扫描目录【" + workDir.getAbsolutePath() + "】发生 I/O 错误: " + e.getMessage();
            System.err.println(err);
            log.error(err, e);
            return ResponseEntity.ok(new PurchaseFilesResponse(Collections.emptyList(), null, null, workDir.getAbsolutePath(), err));
        }

        System.out.printf("[文件扫描] 目录【%s】物理文件与子项总数: %d%n", workDir.getAbsolutePath(), rawFileList.size());

        List<String> fileNames = new ArrayList<>();
        List<String> excludedDetails = new ArrayList<>();

        for (File f : rawFileList) {
            if (!f.isFile()) {
                continue;
            }
            String name = f.getName();
            String lower = name.trim().toLowerCase();
            // 必须为 Excel 格式文件
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls") && !lower.endsWith(".xlsm")) {
                excludedDetails.add(name + " (非Excel后缀)");
                continue;
            }
            // 应用配置文件中定义的过滤规则（排除时间戳备份、_bak、_temp_、~$ 等）
            if (filterProperties.shouldExclude(name)) {
                excludedDetails.add(name + " (命中配置过滤规则)");
                continue;
            }
            fileNames.add(name);
        }
        Collections.sort(fileNames);

        System.out.printf("[文件扫描] 目录【%s】有效 Excel 数量: %d, 候选列表: %s%n",
                workDir.getAbsolutePath(), fileNames.size(), fileNames);
        if (!excludedDetails.isEmpty()) {
            System.out.printf("[文件扫描] 目录【%s】被过滤排除的文件 (%d个): %s%n",
                    workDir.getAbsolutePath(), excludedDetails.size(), excludedDetails);
            log.info("[文件扫描] 被过滤排除项: {}", excludedDetails);
        }

        if (fileNames.isEmpty()) {
            String emptyNotice = "目录【" + workDir.getAbsolutePath() + "】未检测到符合条件的 Excel 文件（目录内共 "
                    + rawFileList.size() + " 个文件，已按配置过滤排除 " + excludedDetails.size() + " 个）。";
            return ResponseEntity.ok(new PurchaseFilesResponse(Collections.emptyList(), null, null, workDir.getAbsolutePath(), emptyNotice));
        }

        String recommendedSource = null;
        String recommendedTarget = null;

        for (String fileName : fileNames) {
            File f = new File(workDir, fileName);
            try (FileInputStream fis = new FileInputStream(f);
                 Workbook wb = WorkbookFactory.create(fis)) {
                if (wb.getSheet(ExcelAppendService.PURCHASE_DETAIL_SHEET_NAME) != null) {
                    if (recommendedTarget == null) recommendedTarget = f.getName();
                } else if (wb.getNumberOfSheets() > 0) {
                    Sheet s0 = wb.getSheetAt(0);
                    for (int r = 0; r <= Math.min(15, s0.getLastRowNum()); r++) {
                        Row row = s0.getRow(r);
                        if (row == null) continue;
                        boolean hasNo = false;
                        boolean hasPn = false;
                        for (int c = 0; c < row.getLastCellNum(); c++) {
                            Cell cell = row.getCell(c);
                            if (cell == null) continue;
                            String text = cell.toString().trim().toLowerCase();
                            if (text.equals("no") || text.equals("no.") || text.equals("item")) hasNo = true;
                            if (text.contains("p/n") || text.contains("part") || text.equals("pn") || text.contains("料号")) hasPn = true;
                        }
                        if (hasNo && hasPn) {
                            if (recommendedSource == null) recommendedSource = f.getName();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.printf("[智能识别预警 ⚠️] 预读文件【%s】解析结构失败: %s%n", f.getName(), e.getMessage());
                log.warn("[智能识别预警] 预读文件【{}】解析结构失败: {}", f.getName(), e.getMessage());
            }
        }

        return ResponseEntity.ok(new PurchaseFilesResponse(fileNames, recommendedSource, recommendedTarget, workDir.getAbsolutePath(), null));
    }

    /**
     * 执行采购单数据读取、汇总计算、双重校验与追加写入
     */
    @PostMapping("/execute")
    public ResponseEntity<OrderExecuteResponse> executePurchase(@RequestBody PurchaseExecuteRequest request) {
        List<ExecutionProgress> logHistory = new ArrayList<>();

        if (request.date() == null || request.date().trim().isEmpty()) {
            var p = ExecutionProgress.failed("VALIDATION", "参数校验", "日期为必填项，请输入后再执行。");
            logHistory.add(p);
            return ResponseEntity.badRequest().body(OrderExecuteResponse.failure("日期必填", logHistory));
        }

        if (request.supplier() == null || request.supplier().trim().isEmpty()) {
            var p = ExecutionProgress.failed("VALIDATION", "参数校验", "供应商为必填项，请输入后再执行。");
            logHistory.add(p);
            return ResponseEntity.badRequest().body(OrderExecuteResponse.failure("供应商必填", logHistory));
        }

        if (request.purchaseOrderNo() == null || request.purchaseOrderNo().trim().isEmpty()) {
            var p = ExecutionProgress.failed("VALIDATION", "参数校验", "采购单号为必填项，请输入后再执行。");
            logHistory.add(p);
            return ResponseEntity.badRequest().body(OrderExecuteResponse.failure("采购单号必填", logHistory));
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

        try {
            File resultFile = purchaseProcessor.process(
                    workDir,
                    request.date().trim(),
                    request.supplier().trim(),
                    request.purchaseOrderNo().trim(),
                    request.checkTotalQuantity(),
                    request.checkTotalAmount(),
                    request.sourceFileName(),
                    request.targetFileName(),
                    progress -> {
                        logHistory.add(progress);
                        String tag = switch (progress.status()) {
                            case RUNNING -> "[进行中]";
                            case SUCCESS -> "[成功]";
                            case FAILED -> "[异常]";
                        };
                        System.out.printf("[%s] %s 【%s】: %s%n",
                                progress.timestamp(), tag, progress.stageName(), progress.message());
                    }
            );

            return ResponseEntity.ok(OrderExecuteResponse.success(
                    "采购单处理完成！成功追加数据并更新主文件。",
                    resultFile.getName(),
                    logHistory
            ));
        } catch (Exception e) {
            System.err.println("================================================================================");
            System.err.println("【采购单执行异常报错】: " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.println("================================================================================");

            log.error("采购单执行发生异常: {}", e.getMessage(), e);

            return ResponseEntity.ok(OrderExecuteResponse.failure(
                    "执行过程中发生异常: " + e.getMessage(),
                    logHistory
            ));
        }
    }
}
