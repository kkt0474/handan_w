package com.handan.purchase;

import com.handan.excel.ExcelAppendService;
import com.handan.progress.ExecutionProgress;
import com.handan.progress.ProgressListener;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 采购单核心处理服务
 * 1. 查找并读取 source.xlsx / source.xls
 * 2. 提取 No 为阿拉伯数字的物料行
 * 3. 生成【采购单】明细数据与【采购单汇总】Group By 聚合数据
 * 4. 执行总数量与总金额双重校验（如输入）
 * 5. 安全备份并在原 result.xlsx 中直接追加写入
 * 6. 输出执行日志（若校验不通过最终 Log 标记为“异常”）
 */
@Service
public class PurchaseProcessor {

    private final PurchaseReader purchaseReader;
    private final ExcelAppendService excelAppendService;

    public PurchaseProcessor() {
        this(new ExcelAppendService());
    }

    public PurchaseProcessor(ExcelAppendService excelAppendService) {
        this.purchaseReader = new PurchaseReader();
        this.excelAppendService = excelAppendService != null ? excelAppendService : new ExcelAppendService();
    }

    /**
     * 执行采购单全流程处理（自动扫描定位源文件与目标母表）
     */
    public File process(
            File workDir,
            String date,
            String supplier,
            String purchaseOrderNo,
            String checkTotalQuantity,
            String checkTotalAmount,
            ProgressListener listener
    ) throws Exception {
        return process(workDir, date, supplier, purchaseOrderNo, checkTotalQuantity, checkTotalAmount, null, null, listener);
    }

    /**
     * 执行采购单全流程处理（支持前端指定源文件与目标母表文件名）
     *
     * @param workDir 业务数据文件夹
     * @param date 前台输入的日期
     * @param supplier 前台输入的供应商
     * @param purchaseOrderNo 前台输入的采购单号
     * @param checkTotalQuantity 前台输入的校验总数量（可为空）
     * @param checkTotalAmount 前台输入的校验总金额（可为空）
     * @param sourceFileName 前端指定的采购源文件名（可选）
     * @param targetFileName 前端指定的目标母表文件名（可选）
     * @param listener 进度监听器
     * @return 更新后的目标母表 Excel 文件
     * @throws Exception 流程异常
     */
    public File process(
            File workDir,
            String date,
            String supplier,
            String purchaseOrderNo,
            String checkTotalQuantity,
            String checkTotalAmount,
            String sourceFileName,
            String targetFileName,
            ProgressListener listener
    ) throws Exception {

        // ---------------- 阶段 0：文件选择防呆校验 ----------------
        if (sourceFileName != null && !sourceFileName.isBlank() && targetFileName != null && !targetFileName.isBlank()) {
            if (sourceFileName.trim().equalsIgnoreCase(targetFileName.trim())) {
                String err = "【文件选择冲突 ❌】采购源文件与目标母表不能是同一个文件（均为【" + sourceFileName.trim() + "】），请分别选择正确的源单据与目标母表。";
                listener.onProgress(ExecutionProgress.failed("FILE_CONFLICT", "文件防呆校验", err));
                throw new IllegalArgumentException(err);
            }
        }

        // ---------------- 阶段 1：查找与确定采购源文件 ----------------
        File sourceFile;
        if (sourceFileName != null && !sourceFileName.isBlank()) {
            sourceFile = new File(workDir, sourceFileName.trim());
            if (!sourceFile.isFile()) {
                String err = "【文件未找到 ❌】指定的采购源文件不存在: " + sourceFile.getAbsolutePath();
                listener.onProgress(ExecutionProgress.failed("SOURCE_NOT_FOUND", "查找采购源文件", err));
                throw new IllegalArgumentException(err);
            }
        } else {
            listener.onProgress(ExecutionProgress.running("SOURCE_SEARCH", "查找采购源文件",
                    "正在工作目录【" + workDir.getAbsolutePath() + "】中查找采购源文件..."));
            sourceFile = findSourceFile(workDir);
            if (sourceFile == null) {
                String err = "未在工作目录【" + workDir.getAbsolutePath() + "】中检测到 source.xlsx 或 source.xls 采购源文件，请先放入源文件。";
                listener.onProgress(ExecutionProgress.failed("SOURCE_NOT_FOUND", "查找采购源文件", err));
                throw new IllegalArgumentException(err);
            }
        }

        listener.onProgress(ExecutionProgress.success("SOURCE_FOUND", "采购源文件就绪",
                "成功选定采购源文件: " + sourceFile.getName() + "（文件大小: " + sourceFile.length() + " 字节）。"));

        // ---------------- 阶段 1.5：确定并前置校验目标母表文件 ----------------
        File targetFile = null;
        if (targetFileName != null && !targetFileName.isBlank()) {
            targetFile = new File(workDir, targetFileName.trim());
            if (!targetFile.isFile()) {
                String err = "【文件未找到 ❌】指定的目标母表文件不存在: " + targetFile.getAbsolutePath();
                listener.onProgress(ExecutionProgress.failed("TARGET_NOT_FOUND", "查找目标母表", err));
                throw new IllegalArgumentException(err);
            }
            try {
                excelAppendService.validatePurchaseTargetWorkbook(targetFile);
            } catch (Exception e) {
                listener.onProgress(ExecutionProgress.failed("TARGET_FORMAT_ERROR", "目标母表检查", e.getMessage()));
                throw e;
            }
            listener.onProgress(ExecutionProgress.success("TARGET_FOUND", "目标母表就绪",
                    "成功选定并校验目标母表: " + targetFile.getName() + "（已验证【采购单】工作表与表头结构）。"));
        }

        // ---------------- 阶段 2：解析源文件并提取有效物料行 ----------------
        listener.onProgress(ExecutionProgress.running("SOURCE_PARSE", "读取与解析",
                "正在解析采购源文件 " + sourceFile.getName() + "，定位表头并提取 No 列为阿拉伯数字的明细条目..."));

        List<PurchaseItem> items;
        try {
            items = purchaseReader.readSourceFile(sourceFile);
        } catch (Exception e) {
            listener.onProgress(ExecutionProgress.failed("PARSE_ERROR", "读取与解析",
                    "解析采购源文件失败: " + e.getMessage()));
            throw e;
        }

        if (items.isEmpty()) {
            String err = "【采购源文件数据为空 ❌】在文件【" + sourceFile.getName() + "】中未提取到任何有效采购物料行（No 列须为阿拉伯数字）。";
            listener.onProgress(ExecutionProgress.failed("NO_ITEMS_FOUND", "读取与解析", err));
            throw new IllegalStateException(err);
        }

        listener.onProgress(ExecutionProgress.success("PARSE_SUCCESS", "数据提取完成",
                "成功从 " + sourceFile.getName() + " 抽取到 " + items.size() + " 条有效采购物料行。"));

        // ---------------- 阶段 3：组装【采购单】明细与【采购单汇总】数据 ----------------
        listener.onProgress(ExecutionProgress.running("DATA_TRANSFORM", "组装明细与汇总",
                "正在将前台参数（日期: " + date + ", 供应商: " + supplier + ", 采购单号: " + purchaseOrderNo
                        + "）与源数据映射，并按料号聚合采购汇总..."));

        List<List<Object>> detailRows = new ArrayList<>();
        for (PurchaseItem item : items) {
            // 列顺序：["日期", "供应商", "采购单号", "料号", "单价(USD)", "数量(PCS)", "金额", "交期", "备注"]
            Object qtyVal = (item.rawQuantityStr() != null && !item.rawQuantityStr().isBlank())
                    ? item.rawQuantityStr() : (item.quantity() != null ? item.quantity() : "");
            Object priceVal = (item.rawUnitPriceStr() != null && !item.rawUnitPriceStr().isBlank())
                    ? item.rawUnitPriceStr() : (item.unitPrice() != null ? item.unitPrice() : "");
            BigDecimal calculatedAmount = item.calculateAmount();
            Object amountVal = calculatedAmount != null ? calculatedAmount : (item.amount() != null ? item.amount() : "");

            detailRows.add(List.of(
                    date != null ? date : "",
                    supplier != null ? supplier : "",
                    purchaseOrderNo != null ? purchaseOrderNo : "",
                    item.pn() != null ? item.pn() : "",
                    priceVal,
                    qtyVal,
                    amountVal,
                    item.deliveryDate() != null ? item.deliveryDate() : "",
                    item.note() != null ? item.note() : ""
            ));
        }

        List<List<Object>> summaryRows = PurchaseSummaryAggregator.aggregate(items, supplier, purchaseOrderNo);

        listener.onProgress(ExecutionProgress.success("TRANSFORM_SUCCESS", "数据转换完成",
                "数据组装就绪：生成 " + detailRows.size() + " 条采购明细行，按料号聚合出 " + summaryRows.size() + " 条采购汇总记录。"));

        // ---------------- 阶段 4：执行校验（总数量与总金额） ----------------
        PurchaseValidator.PurchaseValidationSummary validationSummary = PurchaseValidator.validate(
                items,
                summaryRows,
                checkTotalQuantity,
                checkTotalAmount,
                listener
        );

        // ---------------- 阶段 5：安全备份并追加到原 Excel 母表 ----------------
        File modifiedResultFile;
        try {
            if (targetFile != null) {
                modifiedResultFile = excelAppendService.backupAndAppendPurchaseToSpecificFile(
                        targetFile,
                        detailRows,
                        summaryRows,
                        items,
                        listener
                );
            } else {
                modifiedResultFile = excelAppendService.backupAndAppendPurchaseToOriginal(
                        workDir,
                        sourceFile,
                        detailRows,
                        summaryRows,
                        items,
                        listener
                );
            }
        } catch (Exception e) {
            listener.onProgress(ExecutionProgress.failed("EXCEL_ERROR", "Excel操作异常",
                    "在处理 Excel 文件时发生异常中断: " + e.getMessage()));
            throw e;
        }

        // ---------------- 阶段 6：最终状态确认与提示 ----------------
        if (validationSummary.triggered() && !validationSummary.passed()) {
            String finalWarningMessage = "⚠️ 采购单数据已成功直接写入主文件: " + modifiedResultFile.getName()
                    + "，但 " + validationSummary.summaryNotice();
            listener.onProgress(ExecutionProgress.failed("ALL_COMPLETED", "流程完成但存在异常", finalWarningMessage));
        } else {
            String finalSuccessMessage = "🎉 采购单全流程执行成功！新数据已直接写入主文件: " + modifiedResultFile.getName();
            if (validationSummary.triggered()) {
                finalSuccessMessage += " ｜ " + validationSummary.summaryNotice();
            }
            listener.onProgress(ExecutionProgress.success("ALL_COMPLETED", "流程全部完成", finalSuccessMessage));
        }

        return modifiedResultFile;
    }

    /**
     * 优先查找 source.xlsx，若不存在则查找 source.xls
     */
    private File findSourceFile(File workDir) {
        File xlsx = new File(workDir, "source.xlsx");
        if (xlsx.isFile()) return xlsx;

        File xls = new File(workDir, "source.xls");
        if (xls.isFile()) return xls;

        // 大小写宽松匹配
        File[] candidates = workDir.listFiles((dir, name) ->
                name.equalsIgnoreCase("source.xlsx") || name.equalsIgnoreCase("source.xls")
        );
        if (candidates != null && candidates.length > 0) {
            return candidates[0];
        }

        return null;
    }
}
