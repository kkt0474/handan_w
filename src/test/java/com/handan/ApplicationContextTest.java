package com.handan;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.handan.excel.ExcelAppendService;
import com.handan.purchase.PurchaseProcessor;
import com.handan.web.PurchaseController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ApplicationContextTest {

    @Test
    void contextLoadsAndBeansInjected() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.scan("com.handan");
            ctx.refresh();

            ExcelAppendService excelAppendService = ctx.getBean(ExcelAppendService.class);
            PurchaseProcessor purchaseProcessor = ctx.getBean(PurchaseProcessor.class);
            PurchaseController purchaseController = ctx.getBean(PurchaseController.class);

            assertNotNull(excelAppendService, "ExcelAppendService 应被 Spring 容器成功注册为 Bean");
            assertNotNull(purchaseProcessor, "PurchaseProcessor 应被 Spring 容器成功依赖注入并初始化");
            assertNotNull(purchaseController, "PurchaseController 应被 Spring 容器成功初始化");
        }
    }
}
