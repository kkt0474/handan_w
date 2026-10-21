package com.handan.purchase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购单汇总聚合器
 * 表头结构依次为：["供应商", "采购单号", "料号", "总数量", "入库数量", "未交数量"]
 * 聚合逻辑：按料号 P/N 分组（保持原始出现顺序），对 Q'TY 求和
 */
public final class PurchaseSummaryAggregator {

    public static final List<String> SUMMARY_HEADERS = List.of(
            "供应商",
            "采购单号",
            "料号",
            "总数量",
            "入库数量",
            "未交数量"
    );

    private PurchaseSummaryAggregator() {}

    /**
     * 将解析出的物料条目按料号 P/N 分组聚合
     *
     * @param items 采购明细项列表
     * @param supplier 前台供应商
     * @param purchaseOrderNo 前台采购单号
     * @return 采购单汇总行列表（每行 6 列）
     */
    public static List<List<Object>> aggregate(List<PurchaseItem> items, String supplier, String purchaseOrderNo) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        // 使用 LinkedHashMap 保持料号在源文件中的首次出现顺序
        Map<String, BigDecimal> pnTotalQtyMap = new LinkedHashMap<>();

        for (PurchaseItem item : items) {
            String pn = (item.pn() != null) ? item.pn().trim() : "";
            if (pn.isEmpty()) {
                continue;
            }
            BigDecimal qty = (item.quantity() != null) ? item.quantity() : BigDecimal.ZERO;
            pnTotalQtyMap.merge(pn, qty, BigDecimal::add);
        }

        List<List<Object>> summaryRows = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : pnTotalQtyMap.entrySet()) {
            String pn = entry.getKey();
            BigDecimal totalQty = entry.getValue();

            // 若数量是整数，存为 Long 以便于 POI 写入数字单元格并应用千分位
            Object qtyVal;
            if (totalQty.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
                qtyVal = totalQty.longValue();
            } else {
                qtyVal = totalQty.doubleValue();
            }

            // 列 0: 供应商
            // 列 1: 采购单号
            // 列 2: 料号
            // 列 3: 总数量
            // 列 4: 入库数量（统一默认值 "-"）
            // 列 5: 未交数量（等于“总数量”的值）
            summaryRows.add(List.of(
                    supplier != null ? supplier : "",
                    purchaseOrderNo != null ? purchaseOrderNo : "",
                    pn,
                    qtyVal,
                    "-",
                    qtyVal
            ));
        }

        return summaryRows;
    }
}
