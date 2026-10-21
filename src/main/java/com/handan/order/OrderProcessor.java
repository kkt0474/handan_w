package com.handan.order;

import com.handan.config.ConfigLoader;
import com.handan.config.MappingConfig;
import com.handan.excel.ExcelAppendService;
import com.handan.extractor.DocumentExtractor;
import com.handan.extractor.DocumentExtractorFactory;
import com.handan.extractor.ExtractedResult;
import com.handan.extractor.PdfSniffer;
import com.handan.progress.ExecutionProgress;
import com.handan.progress.ProgressListener;
import com.handan.transform.AmountValidator;
import com.handan.transform.OrderSummaryAggregator;
import com.handan.transform.RowFlattener;
import java.io.File;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 客户订单领域核心业务处理器（Facade & Domain Service）
 * <p>
 * 统一调度客户订单的 7 步标准化流水线：
 * 1. 参数与环境校验
 * 2. 单据形态智能识别（矢量电子件 vs 扫描件/图片）
 * 3. 动态提取策略执行（Strategy Pattern 结合 Factory Pattern）
 * 4. 总金额业务规则校验
 * 5. 数据重组与按料号 Group By 汇总聚合
 * 6. 事务性安全追加写入（Template Callback 模式）
 * 7. 进度事件聚合与状态推送（Observer Pattern）
 */
@Service
public class OrderProcessor {

    private final DocumentExtractorFactory extractorFactory;
    private final ExcelAppendService excelAppendService;

    public OrderProcessor() {
        this(new DocumentExtractorFactory(), new ExcelAppendService());
    }

    public OrderProcessor(DocumentExtractorFactory extractorFactory, ExcelAppendService excelAppendService) {
        this.extractorFactory = extractorFactory != null ? extractorFactory : new DocumentExtractorFactory();
        this.excelAppendService = excelAppendService != null ? excelAppendService : new ExcelAppendService();
    }

    /**
     * 处理客户订单提取全流程
     *
     * @param sourceFile 输入的单据文件（PDF 或 PNG 图片等）
     * @param configFile 映射配置文件 mapping-config.yaml
     * @param customer 必填客户名称
     * @param orderNo 必填订单号
     * @param checkTotalAmount 选填校验总金额（为空则跳过校验）
     * @param listener 进度监听器（Observer Pattern）
     * @return 最终更新后的主文件 result.xlsx
     */
    public File process(
            File sourceFile,
            File configFile,
            String customer,
            String orderNo,
            String checkTotalAmount,
            ProgressListener listener
    ) throws Exception {
        // ---------------- 阶段 1：参数与环境校验 ----------------
        listener.onProgress(ExecutionProgress.running("CHECK_PARAMS", "参数与文件校验",
                "正在校验输入参数：客户【" + customer + "】、订单号【" + orderNo + "】，并检查目标单据文件及配置..."));

        if (customer == null || customer.isBlank()) {
            String msg = "客户名称为必填项，不可为空";
            listener.onProgress(ExecutionProgress.failed("PARAM_CUSTOMER_EMPTY", "参数与文件校验", msg));
            throw new IllegalArgumentException(msg);
        }
        if (orderNo == null || orderNo.isBlank()) {
            String msg = "订单号为必填项，不可为空";
            listener.onProgress(ExecutionProgress.failed("PARAM_ORDERNO_EMPTY", "参数与文件校验", msg));
            throw new IllegalArgumentException(msg);
        }

        if (sourceFile == null || !sourceFile.isFile()) {
            String msg = "未找到待处理的单据文件: " + (sourceFile == null ? "null" : sourceFile.getAbsolutePath());
            listener.onProgress(ExecutionProgress.failed("FILE_NOT_FOUND", "参数与文件校验", msg));
            throw new IllegalArgumentException(msg);
        }

        MappingConfig config;
        try {
            config = ConfigLoader.load(configFile);
            listener.onProgress(ExecutionProgress.success("CONFIG_LOADED", "参数与文件校验",
                    "配置文件加载成功，共读取到 " + config.columns().size() + " 个字段提取规则。"));
        } catch (Exception e) {
            listener.onProgress(ExecutionProgress.failed("CONFIG_ERROR", "参数与文件校验",
                    "配置文件格式有误，无法正确解析: " + e.getMessage()));
            throw e;
        }

        // ---------------- 阶段 2：单据形态智能识别与策略获取 ----------------
        listener.onProgress(ExecutionProgress.running("SNIFF_ROUTING", "单据形态识别",
                "正在分析单据文件格式与结构，智能匹配提取引擎..."));

        DocumentExtractor extractor;
        try {
            String fileName = sourceFile.getName().toLowerCase();
            String branchName;
            if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                branchName = "图片单据（调用内置 RapidOCR 进行字符与表格识别）";
            } else {
                boolean isVector = PdfSniffer.isVector(sourceFile);
                branchName = isVector ? "电子矢量单据（可直接提取高质量文本与表格）" : "扫描件/图片单据（需调用内置 OCR 进行字符识别）";
            }
            listener.onProgress(ExecutionProgress.success("ROUTING_DETERMINED", "单据形态识别",
                    "单据形态分析完成，匹配处理引擎为: " + branchName));
            extractor = extractorFactory.getExtractor(sourceFile);
        } catch (Exception e) {
            listener.onProgress(ExecutionProgress.failed("ROUTING_ERROR", "单据形态识别",
                    "单据形态分析失败: " + e.getMessage()));
            throw e;
        }

        // ---------------- 阶段 3：执行数据提取 ----------------
        listener.onProgress(ExecutionProgress.running("EXTRACTING", "单据数据抽取",
                "正在抽取单据中的表格与相关明细数据..."));

        ExtractedResult extractedResult;
        try {
            extractedResult = extractor.extract(sourceFile, config);

            int tableRowCount = (extractedResult.tableData() != null) ? extractedResult.tableData().size() : 0;
            listener.onProgress(ExecutionProgress.success("EXTRACT_FINISHED", "单据数据抽取",
                    "单据内容抽取成功，共解析出 " + tableRowCount + " 行表格原始行。"));
        } catch (Exception e) {
            listener.onProgress(ExecutionProgress.failed("EXTRACT_ERROR", "单据数据抽取",
                    "从单据中提取数据失败: " + e.getMessage()));
            throw e;
        }

        // ---------------- 阶段 4：执行总金额准确性校验（若用户输入） ----------------
        AmountValidator.ValidationSummary validationSummary = AmountValidator.ValidationSummary.notTriggered();
        if (checkTotalAmount != null && !checkTotalAmount.trim().isEmpty()) {
            listener.onProgress(ExecutionProgress.running("AMOUNT_VALIDATION", "总金额校验",
                    "正在根据单据明细各行【单价 * 数量】进行累加计算，并与用户输入金额【" + checkTotalAmount.trim() + "】比对..."));
            validationSummary = AmountValidator.validate(extractedResult, config, checkTotalAmount, listener);
        }

        // ---------------- 阶段 5：数据重组与汇总聚合 ----------------
        listener.onProgress(ExecutionProgress.running("DATA_FLATTENING", "明细组装与汇总聚合",
                "正在生成【客户订单】明细行，并以料号为维度 Group By 汇总【客户订单汇总】数据..."));

        List<List<Object>> detailRows;
        List<List<Object>> summaryRows;
        try {
            detailRows = RowFlattener.flatten(extractedResult, config, customer, orderNo);
            summaryRows = OrderSummaryAggregator.aggregate(extractedResult, config, customer, orderNo);

            listener.onProgress(ExecutionProgress.success("FLATTEN_FINISHED", "明细组装与汇总聚合",
                    "数据计算完毕：生成 " + detailRows.size() + " 条客户订单明细，聚合出 " + summaryRows.size() + " 条料号汇总记录。"));
        } catch (Exception e) {
            listener.onProgress(ExecutionProgress.failed("FLATTEN_ERROR", "明细组装与汇总聚合",
                    "数据组装转换失败: " + e.getMessage()));
            throw e;
        }

        // ---------------- 阶段 6：严格先备份后写入（事务性保障） ----------------
        File targetDirectory = sourceFile.getAbsoluteFile().getParentFile();
        File modifiedResultFile;
        try {
            modifiedResultFile = excelAppendService.backupAndAppendToOriginal(
                    targetDirectory,
                    detailRows,
                    summaryRows,
                    config,
                    listener
            );
        } catch (Exception e) {
            listener.onProgress(ExecutionProgress.failed("EXCEL_ERROR", "Excel操作异常",
                    "在处理 Excel 文件时发生异常中断: " + e.getMessage()));
            throw e;
        }

        // ---------------- 阶段 7：流程全部完成与状态通知 ----------------
        if (validationSummary.triggered() && !validationSummary.passed()) {
            String finalWarningMessage = "⚠️ 数据已成功直接写入主文件: " + modifiedResultFile.getName()
                    + "，但 " + validationSummary.summaryNotice();
            listener.onProgress(ExecutionProgress.failed("ALL_COMPLETED", "流程完成但存在异常", finalWarningMessage));
        } else {
            String finalSuccessMessage = "🎉 全流程执行成功！新数据已直接写入主文件: " + modifiedResultFile.getName();
            if (validationSummary.triggered()) {
                finalSuccessMessage += " ｜ " + validationSummary.summaryNotice();
            }
            listener.onProgress(ExecutionProgress.success("ALL_COMPLETED", "流程全部完成", finalSuccessMessage));
        }

        return modifiedResultFile;
    }

    public File process(
            File pdfFile,
            File configFile,
            String customer,
            String orderNo,
            ProgressListener listener
    ) throws Exception {
        return process(pdfFile, configFile, customer, orderNo, null, listener);
    }
}
