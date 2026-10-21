package com.handan.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handan.order.OrderProcessor;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

class OrderControllerTest {

    @Test
    void executeOrderFailsWhenPngNotFoundInPngMode() throws Exception {
        File tempDir = Files.createTempDirectory("ctrl-png-test-").toFile();
        File pdfFile = new File(tempDir, "only_pdf.pdf");
        Files.write(pdfFile.toPath(), new byte[]{1, 2, 3});

        File configFile = new File(tempDir, "mapping-config.yaml");
        Files.write(configFile.toPath(), "version: 1.0\n".getBytes());

        OrderController controller = new OrderController(new OrderProcessor());
        ReflectionTestUtils.setField(controller, "defaultDataDir", tempDir.getAbsolutePath());
        ReflectionTestUtils.setField(controller, "configPath", configFile.getAbsolutePath());

        OrderExecuteRequest req = new OrderExecuteRequest(
                "客户A",
                "ORD-001",
                null,
                tempDir.getAbsolutePath(),
                "PNG"
        );

        ResponseEntity<OrderExecuteResponse> resp = controller.executeOrder(req);
        assertFalse(resp.getBody().success());
        assertEquals("未找到 PNG 图片文件", resp.getBody().message());
    }

    @Test
    void executeOrderFailsWhenPdfNotFoundInPdfMode() throws Exception {
        File tempDir = Files.createTempDirectory("ctrl-pdf-test-").toFile();
        File pngFile = new File(tempDir, "only_png.png");
        Files.write(pngFile.toPath(), new byte[]{1, 2, 3});

        File configFile = new File(tempDir, "mapping-config.yaml");
        Files.write(configFile.toPath(), "version: 1.0\n".getBytes());

        OrderController controller = new OrderController(new OrderProcessor());
        ReflectionTestUtils.setField(controller, "defaultDataDir", tempDir.getAbsolutePath());
        ReflectionTestUtils.setField(controller, "configPath", configFile.getAbsolutePath());

        OrderExecuteRequest req = new OrderExecuteRequest(
                "客户B",
                "ORD-002",
                null,
                tempDir.getAbsolutePath(),
                "PDF"
        );

        ResponseEntity<OrderExecuteResponse> resp = controller.executeOrder(req);
        assertFalse(resp.getBody().success());
        assertEquals("未找到 PDF 文件", resp.getBody().message());
    }
}
