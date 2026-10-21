package com.handan.web;

/**
 * 采购单执行请求体
 */
public record PurchaseExecuteRequest(
        String date,
        String supplier,
        String purchaseOrderNo,
        String checkTotalQuantity,
        String checkTotalAmount,
        String customDir,
        String sourceFileName,
        String targetFileName
) {
    public PurchaseExecuteRequest(
            String date,
            String supplier,
            String purchaseOrderNo,
            String checkTotalQuantity,
            String checkTotalAmount,
            String customDir
    ) {
        this(date, supplier, purchaseOrderNo, checkTotalQuantity, checkTotalAmount, customDir, null, null);
    }
}
