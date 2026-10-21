package com.handan.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.handan.config.ConfigLoader;
import com.handan.config.MappingConfig;
import com.handan.extractor.ExtractedResult;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderSummaryAggregatorTest {

    @Test
    void aggregatesByPartNoCorrectly() throws Exception {
        MappingConfig cfg = ConfigLoader.load(new File("mapping-config.yaml"));

        // 构造测试数据：
        // 料号在 col index 2，数量在 col index 3
        ExtractedResult r = new ExtractedResult(
                Map.of(),
                List.of(
                        // 表头（将被过滤）
                        List.of("日期", "客户", "料号", "数量(PCS)", "单价(USD)", "金额", "交期"),
                        // 料号 ITEM-A，数量 100
                        List.of("2026/8/20", "旧客户", "ITEM-A", "100", "5.5", "550", "2026-11-01"),
                        // 料号 ITEM-B，数量 50
                        List.of("2026/8/20", "旧客户", "ITEM-B", "50", "10.0", "500", "2026-11-02"),
                        // 料号 ITEM-A，数量 200（带千分位或空格），求和后 ITEM-A 总数量应为 300
                        List.of("2026/8/20", "旧客户", "ITEM-A", "200", "5.5", "1100", "2026-11-03")
                )
        );

        List<List<Object>> summary = OrderSummaryAggregator.aggregate(r, cfg, "本田技研", "PO-20261013-01");

        // 期望聚合为 2 条记录：ITEM-A 与 ITEM-B
        assertEquals(2, summary.size());

        // 第 1 条记录：ITEM-A
        List<Object> rowA = summary.get(0);
        assertEquals("本田技研", rowA.get(0));       // 客户
        assertEquals("PO-20261013-01", rowA.get(1)); // 订单号
        assertEquals("ITEM-A", rowA.get(2));         // 料号
        assertEquals(300L, rowA.get(3));             // 总数量 (100 + 200)
        assertEquals("-", rowA.get(4));              // 出货数量
        assertEquals(300L, rowA.get(5));             // 未出货数量

        // 第 2 条记录：ITEM-B
        List<Object> rowB = summary.get(1);
        assertEquals("本田技研", rowB.get(0));
        assertEquals("PO-20261013-01", rowB.get(1));
        assertEquals("ITEM-B", rowB.get(2));
        assertEquals(50L, rowB.get(3));              // 总数量 (50)
        assertEquals("-", rowB.get(4));
        assertEquals(50L, rowB.get(5));
    }
}
