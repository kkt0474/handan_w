package com.handan.purchase;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 采购单单行条目数据模型
 */
public record PurchaseItem(
        int no,
        String pn,
        String brand,
        BigDecimal quantity,
        String rawQuantityStr,
        String unit,
        BigDecimal unitPrice,
        String rawUnitPriceStr,
        BigDecimal amount,
        String rawAmountStr,
        String deliveryDate,
        String note
) {
    /**
     * 计算金额 = 单价 * 数量（按 2 位小数四舍五入）
     */
    public BigDecimal calculateAmount() {
        BigDecimal q = quantity != null ? quantity : BigDecimal.ZERO;
        BigDecimal p = unitPrice != null ? unitPrice : BigDecimal.ZERO;
        return q.multiply(p).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 校验计算金额是否与 source 文件中的 Amount(USD) 一致（均按 2 位小数对比）
     * 若 source 的 Amount(USD) 为空或无法解析，则视为不同（返回 false）
     */
    public boolean isAmountMatchingSource() {
        if (amount == null) {
            return false;
        }
        return calculateAmount().compareTo(amount.setScale(2, RoundingMode.HALF_UP)) == 0;
    }
}
