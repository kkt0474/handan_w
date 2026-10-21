package com.handan.extractor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.handan.config.ConfigLoader;
import com.handan.config.MappingConfig;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageDocumentExtractorTest {

    @Test
    void extractsTableRowsWithProperHorizontalSortingAndColumnAlignment() throws Exception {
        File tempDir = Files.createTempDirectory("debug-ocr-").toFile();
        File imageFile = new File(tempDir, "test_order.png");

        // 绘制高分辨率测试图片 (300 DPI 级别尺寸: 2480 x 1200)
        int width = 2000;
        int height = 800;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, width, height);

        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.PLAIN, 32));

        // 顶部元数据行
        g2d.drawString("客户订单销售单", 800, 80);
        g2d.drawString("订单编号: SO-20261020-001    日期: 2026/10/20", 200, 160);

        // 表格行
        int y = 260;
        // 表头
        int[] xs = {100, 350, 600, 900, 1200, 1450, 1700};
        String[] headers = {"日期", "客户", "料号", "数量(PCS)", "单价(USD)", "金额", "交期"};
        for (int i = 0; i < headers.length; i++) {
            g2d.drawString(headers[i], xs[i], y);
        }

        // 数据行 1
        y += 80;
        String[] row1 = {"2026/8/20", "旧客户A", "ITEM-1", "30,000", "0.44", "13200", "2026-11-01"};
        for (int i = 0; i < row1.length; i++) {
            g2d.drawString(row1[i], xs[i], y);
        }

        // 数据行 2
        y += 80;
        String[] row2 = {"2026/8/20", "旧客户A", "ITEM-2", "2,000", "1.50", "3000", "2026-11-02"};
        for (int i = 0; i < row2.length; i++) {
            g2d.drawString(row2[i], xs[i], y);
        }

        g2d.dispose();
        ImageIO.write(img, "png", imageFile);

        MappingConfig config = ConfigLoader.load(new File("mapping-config.yaml"));
        ImageDocumentExtractor extractor = new ImageDocumentExtractor();
        ExtractedResult result = extractor.extract(imageFile, config);

        System.out.println("========== Extracted Table Data ==========");
        for (int r = 0; r < result.tableData().size(); r++) {
            System.out.println("Row " + r + " (size=" + result.tableData().get(r).size() + "): " + result.tableData().get(r));
        }
        System.out.println("==========================================");

        assertNotNull(result);

        // 验证 RowFlattener 输出：料号绝对不能是日期！
        var flattened = com.handan.transform.RowFlattener.flatten(result, config, "索尼电子", "SO-20261020-001");
        org.junit.jupiter.api.Assertions.assertEquals(2, flattened.size(), "必须精确解析出 2 条数据行（表头及顶部元数据被过滤）");

        // row 0: 日期, 客户, 订单号, 料号, 单价, 数量, 金额, 交期
        var r0 = flattened.get(0);
        org.junit.jupiter.api.Assertions.assertEquals("ITEM-1", r0.get(3), "Excel 料号字段必须是 ITEM-1，严禁被错乱填入日期！");
        org.junit.jupiter.api.Assertions.assertEquals("0.44", r0.get(4), "单价列必须正确对应");
        org.junit.jupiter.api.Assertions.assertEquals("30,000", r0.get(5), "数量列必须正确对应");
        org.junit.jupiter.api.Assertions.assertEquals("13200", r0.get(6), "金额列必须正确对应");
        org.junit.jupiter.api.Assertions.assertEquals("2026-11-01", r0.get(7), "交期列必须正确对应");

        // 验证 AmountValidator：总金额校验必须通过
        var validation = com.handan.transform.AmountValidator.validate(result, config, "16,200.00", p -> {});
        org.junit.jupiter.api.Assertions.assertTrue(validation.passed(), "金额校验必须通过！");
    }
}
