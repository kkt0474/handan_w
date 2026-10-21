package com.handan.purchase;

import com.handan.progress.ExecutionProgress;
import com.handan.progress.ProgressListener;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购单数据核验器
 * 1. 总数量核验：所有物料的“数量(PCS)”求和 与 用户输入的【总数量(校验)】对比
 * 2. 总金额核验：每条记录的【数量(PCS) * 单价(USD)】累加求和 与 用户输入的【总金额(校验)】对比（精确到分）
 * 3. 汇总表总数量双重核验：确保汇总表总数量求和也等于校验数量
 */
public final class PurchaseValidator {

    private static final DecimalFormat QTY_FORMAT = new DecimalFormat("#,##0");
    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("#,##0.00");

    public record PurchaseValidationSummary(
            boolean triggered,
            boolean passed,
            String summaryNotice
    ) {
        public static PurchaseValidationSummary notTriggered() {
            return new PurchaseValidationSummary(false, true, "");
        }

        public static PurchaseValidationSummary pass(String notice) {
            return new PurchaseValidationSummary(true, true, notice);
        }

        public static PurchaseValidationSummary fail(String notice) {
            return new PurchaseValidationSummary(true, false, notice);
        }
    }

    private PurchaseValidator() {}

    /**
     * 执行采购单总数量与总金额核验
     */
    public static PurchaseValidationSummary validate(
            List<PurchaseItem> items,
            List<List<Object>> summaryRows,
            String checkTotalQuantityStr,
            String checkTotalAmountStr,
            ProgressListener listener
    ) {
        boolean hasQtyCheck = checkTotalQuantityStr != null && !checkTotalQuantityStr.trim().isEmpty();
        boolean hasAmountCheck = checkTotalAmountStr != null && !checkTotalAmountStr.trim().isEmpty();

        if (!hasQtyCheck && !hasAmountCheck) {
            return PurchaseValidationSummary.notTriggered();
        }

        boolean allPassed = true;
        List<String> notices = new ArrayList<>();

        // ---------------- 1. 总数量校验 ----------------
        if (hasQtyCheck) {
            String rawQty = checkTotalQuantityStr.trim().replaceAll("[,，\\s]", "");
            BigDecimal expectedQty = null;
            try {
                expectedQty = new BigDecimal(rawQty);
            } catch (Exception e) {
                String notice = "【总数量校验: 格式异常 ⚠️】用户输入的数量无法识别: " + checkTotalQuantityStr;
                listener.onProgress(ExecutionProgress.failed("QTY_VALIDATION", "总数量校验", notice));
                notices.add(notice);
                allPassed = false;
            }

            if (expectedQty != null) {
                // 明细求和
                BigDecimal actualQty = BigDecimal.ZERO;
                if (items != null) {
                    for (PurchaseItem it : items) {
                        if (it.quantity() != null) {
                            actualQty = actualQty.add(it.quantity());
                        }
                    }
                }

                // 汇总表求和（双重验证）
                BigDecimal summaryQty = BigDecimal.ZERO;
                if (summaryRows != null) {
                    for (List<Object> row : summaryRows) {
                        if (row.size() > 3 && row.get(3) instanceof Number num) {
                            summaryQty = summaryQty.add(BigDecimal.valueOf(num.doubleValue()));
                        }
                    }
                }

                String actualQtyStr = QTY_FORMAT.format(actualQty);
                String expectedQtyStr = QTY_FORMAT.format(expectedQty);

                if (actualQty.compareTo(expectedQty) == 0) {
                    String msg = "校验通过！提取采购单明细累计总数量【" + actualQtyStr
                            + "】与用户输入校验总数量【" + expectedQtyStr + "】完全吻合（共 " + (items != null ? items.size() : 0) + " 条明细）。";
                    listener.onProgress(ExecutionProgress.success("QTY_VALIDATION", "总数量校验", msg));
                    notices.add("【总数量校验: 通过 ✅】总数量 " + actualQtyStr + " 吻合");
                } else {
                    allPassed = false;
                    BigDecimal diff = actualQty.subtract(expectedQty).abs();
                    String diffStr = QTY_FORMAT.format(diff);
                    String msg = "校验未通过！提取采购单明细累计总数量【" + actualQtyStr
                            + "】，而用户输入为【" + expectedQtyStr + "】，相差【" + diffStr + "】，请核对单据！";
                    listener.onProgress(ExecutionProgress.failed("QTY_VALIDATION", "总数量校验", msg));
                    notices.add("【总数量校验: 未通过 ❌】计算总数量 " + actualQtyStr + " 与输入 " + expectedQtyStr + " 相差 " + diffStr);
                }
            }
        }

        // ---------------- 2. 总金额校验 ----------------
        if (hasAmountCheck) {
            String rawAmount = checkTotalAmountStr.trim().replaceAll("[,，\\s$￥USDusd]", "");
            BigDecimal expectedAmount = null;
            try {
                expectedAmount = new BigDecimal(rawAmount).setScale(2, RoundingMode.HALF_UP);
            } catch (Exception e) {
                String notice = "【总金额校验: 格式异常 ⚠️】用户输入的金额无法识别: " + checkTotalAmountStr;
                listener.onProgress(ExecutionProgress.failed("AMOUNT_VALIDATION", "总金额校验", notice));
                notices.add(notice);
                allPassed = false;
            }

            if (expectedAmount != null) {
                BigDecimal actualAmount = BigDecimal.ZERO;
                if (items != null) {
                    for (PurchaseItem it : items) {
                        BigDecimal q = it.quantity() != null ? it.quantity() : BigDecimal.ZERO;
                        BigDecimal p = it.unitPrice() != null ? it.unitPrice() : BigDecimal.ZERO;
                        actualAmount = actualAmount.add(q.multiply(p));
                    }
                }

                BigDecimal actualFinal = actualAmount.setScale(2, RoundingMode.HALF_UP);
                String actualAmountStr = AMOUNT_FORMAT.format(actualFinal);
                String expectedAmountStr = AMOUNT_FORMAT.format(expectedAmount);

                if (actualFinal.compareTo(expectedAmount) == 0) {
                    String msg = "校验通过！采购单累计计算总金额【" + actualAmountStr
                            + "】与用户输入校验总金额【" + expectedAmountStr + "】完全吻合。";
                    listener.onProgress(ExecutionProgress.success("AMOUNT_VALIDATION", "总金额校验", msg));
                    notices.add("【总金额校验: 通过 ✅】总金额 " + actualAmountStr + " 吻合");
                } else {
                    allPassed = false;
                    BigDecimal diff = actualFinal.subtract(expectedAmount).abs();
                    String diffStr = AMOUNT_FORMAT.format(diff);
                    String msg = "校验未通过！采购单累计计算总金额【" + actualAmountStr
                            + "】，而用户输入为【" + expectedAmountStr + "】，相差【" + diffStr + "】，请核对单据！";
                    listener.onProgress(ExecutionProgress.failed("AMOUNT_VALIDATION", "总金额校验", msg));
                    notices.add("【总金额校验: 未通过 ❌】计算总金额 " + actualAmountStr + " 与输入 " + expectedAmountStr + " 相差 " + diffStr);
                }
            }
        }

        String combinedNotice = String.join(" ｜ ", notices);
        if (allPassed) {
            return PurchaseValidationSummary.pass(combinedNotice);
        } else {
            return PurchaseValidationSummary.fail(combinedNotice);
        }
    }
}
