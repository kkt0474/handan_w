package com.handan.transform;

import com.handan.config.ColumnMapping;
import com.handan.config.MappingConfig;
import com.handan.extractor.ExtractedResult;
import com.handan.progress.ExecutionProgress;
import com.handan.progress.ProgressListener;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.List;

/**
 * 订单金额校验器
 * 将 PDF 提取的数据逐行计算【单价 * 数量】并累加总额
 * 与前台用户输入的【总金额(校验)】进行精确到分（小数点后 2 位）的对比校验
 */
public final class AmountValidator {

    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("#,##0.00");

    public record ValidationSummary(
            boolean triggered,
            boolean passed,
            String summaryNotice
    ) {
        public static ValidationSummary notTriggered() {
            return new ValidationSummary(false, true, "");
        }

        public static ValidationSummary pass(String notice) {
            return new ValidationSummary(true, true, notice);
        }

        public static ValidationSummary fail(String notice) {
            return new ValidationSummary(true, false, notice);
        }
    }

    private AmountValidator() {}

    /**
     * 校验从 PDF 抽取的明细累计总金额是否与用户输入的校验总金额一致
     *
     * @param result PDF 提取结果
     * @param config 字段映射配置
     * @param userInputAmountStr 用户前台输入的总金额字符串（为空则跳过校验）
     * @param listener 进度监听器（用于在 Log 中记录校验结果）
     * @return 校验综合状态与醒目总结文案
     */
    public static ValidationSummary validate(
            ExtractedResult result,
            MappingConfig config,
            String userInputAmountStr,
            ProgressListener listener
    ) {
        if (userInputAmountStr == null || userInputAmountStr.trim().isEmpty()) {
            return ValidationSummary.notTriggered();
        }

        String rawInput = userInputAmountStr.trim().replaceAll("[,，\\s$￥USD]", "");
        BigDecimal expectedAmount;
        try {
            expectedAmount = new BigDecimal(rawInput).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            String notice = "【⚠️ 校验异常】用户输入的金额格式无法识别: " + userInputAmountStr;
            listener.onProgress(ExecutionProgress.failed("AMOUNT_VALIDATION", "总金额校验", notice));
            return ValidationSummary.fail(notice);
        }

        if (result == null || result.tableData() == null || result.tableData().isEmpty()) {
            String notice = "【⚠️ 校验异常】未抽取到任何表格数据，无法进行金额校验";
            listener.onProgress(ExecutionProgress.failed("AMOUNT_VALIDATION", "总金额校验", notice));
            return ValidationSummary.fail(notice);
        }

        // 1. 定位【单价】与【数量】在表格中的列索引
        int priceColIndex = -1;
        int qtyColIndex = -1;

        for (ColumnMapping col : config.columns()) {
            String header = col.excelHeader();
            if (header == null) continue;
            String h = header.toLowerCase();
            if (h.contains("单价") || h.contains("price")) {
                priceColIndex = col.tableColumnIndex() != null ? col.tableColumnIndex() : -1;
            } else if (h.contains("数量") || h.contains("qty")) {
                qtyColIndex = col.tableColumnIndex() != null ? col.tableColumnIndex() : -1;
            }
        }

        if (priceColIndex < 0 || qtyColIndex < 0) {
            String notice = "【⚠️ 校验异常】配置缺少【单价】或【数量】列映射，无法执行乘积核验";
            listener.onProgress(ExecutionProgress.failed("AMOUNT_VALIDATION", "总金额校验", notice));
            return ValidationSummary.fail(notice);
        }

        // 2. 逐行计算 [单价 * 数量] 并累加
        BigDecimal calculatedTotal = BigDecimal.ZERO;
        int validRows = 0;

        for (List<String> rawRow : result.tableData()) {
            if (MetadataParser.isHeaderRow(rawRow, config)) {
                continue;
            }
            boolean isAllBlank = rawRow.stream().allMatch(c -> c == null || c.isBlank());
            if (isAllBlank) {
                continue;
            }

            String priceStr = (priceColIndex < rawRow.size() && rawRow.get(priceColIndex) != null)
                    ? rawRow.get(priceColIndex).trim() : "";
            String qtyStr = (qtyColIndex < rawRow.size() && rawRow.get(qtyColIndex) != null)
                    ? rawRow.get(qtyColIndex).trim() : "";

            BigDecimal rowPrice = parseNumber(priceStr, false);
            BigDecimal rowQty = parseNumber(qtyStr, true);

            if (rowPrice.compareTo(BigDecimal.ZERO) > 0 && rowQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal rowAmount = rowPrice.multiply(rowQty);
                calculatedTotal = calculatedTotal.add(rowAmount);
                validRows++;
            }
        }

        BigDecimal calculatedFinal = calculatedTotal.setScale(2, RoundingMode.HALF_UP);
        String calculatedStr = AMOUNT_FORMAT.format(calculatedFinal);
        String expectedStr = AMOUNT_FORMAT.format(expectedAmount);

        // 3. 精确到分进行比对
        if (calculatedFinal.compareTo(expectedAmount) == 0) {
            String detail = "校验通过！PDF 提取明细累计计算总金额【" + calculatedStr
                    + "】与用户输入校验金额【" + expectedStr + "】完全一致（共校验 " + validRows + " 行数据）。";
            listener.onProgress(ExecutionProgress.success("AMOUNT_VALIDATION", "总金额校验", detail));

            String finalSummary = "【总金额校验: 校验通过 ✅】计算总额 " + calculatedStr + " 与输入完全吻合。";
            return ValidationSummary.pass(finalSummary);
        } else {
            BigDecimal diff = calculatedFinal.subtract(expectedAmount).abs();
            String diffStr = AMOUNT_FORMAT.format(diff);

            String detail = "校验未通过！PDF 提取明细累计计算总金额【" + calculatedStr
                    + "】，而用户输入校验金额为【" + expectedStr + "】，相差【" + diffStr + "】，请核对单据！";
            listener.onProgress(ExecutionProgress.failed("AMOUNT_VALIDATION", "总金额校验", detail));

            String finalSummary = "【总金额校验: 校验未通过 ❌】计算总额 " + calculatedStr + " 与输入 " + expectedStr + " 相差 " + diffStr + "，请仔细核对单据！";
            return ValidationSummary.fail(finalSummary);
        }
    }

    private static BigDecimal parseNumber(String text, boolean isQuantity) {
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }
        String clean = text.replaceAll("[,，\\s$￥USDusd]", "");
        if (isQuantity) {
            clean = clean.replaceAll("(?i)(pcs|pc|个|件|箱|套|支|只)$", "").trim();
            if (clean.matches("^-?\\d+(\\.\\d{3})+$")) {
                clean = clean.replace(".", "");
            }
        }
        try {
            return new BigDecimal(clean);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
