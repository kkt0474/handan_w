package com.handan.web;

/**
 * 订单执行请求参数
 */
public record OrderExecuteRequest(
        String customer,
        String orderNo,
        String checkTotalAmount, // 前端输入的“总金额(校验)”，非必填
        String customDir,
        String fileType // 选填单据类型："AUTO" | "PDF" | "PNG"，默认 AUTO
) {
    public OrderExecuteRequest(String customer, String orderNo, String checkTotalAmount, String customDir) {
        this(customer, orderNo, checkTotalAmount, customDir, "AUTO");
    }
}
