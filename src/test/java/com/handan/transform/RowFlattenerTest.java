package com.handan.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.handan.config.ConfigLoader;
import com.handan.config.MappingConfig;
import com.handan.extractor.ExtractedResult;
import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowFlattenerTest {

    @Test
    void flattensWithDynamicDateCustomerOrderNo() throws Exception {
        MappingConfig cfg = ConfigLoader.load(new File("mapping-config.yaml"));
        ExtractedResult r = new ExtractedResult(
                Map.of(),
                List.of(
                        // 包含表头与1行明细
                        List.of("日期", "客户", "料号", "数量(PCS)", "单价(USD)", "金额", "交期"),
                        List.of("2026/8/20", "旧客户", "ITEM-999", "100", "5.5", "550", "2026-11-01")
                )
        );

        LocalDate fixedDate = LocalDate.of(2026, 8, 26);
        List<List<Object>> out = RowFlattener.flatten(r, cfg, "本田技研", "PO-20260826-01", fixedDate);

        // 表头行应被过滤，仅剩 1 行数据
        assertEquals(1, out.size());
        List<Object> row = out.get(0);

        // 第 1 列固定为 "2026/8/26"
        assertEquals("2026/8/26", row.get(0));
        // 第 2 列固定为前端输入的客户
        assertEquals("本田技研", row.get(1));
        // 第 3 列固定为前端输入的订单号
        assertEquals("PO-20260826-01", row.get(2));
        // 后续列为 mapping 配置的列：料号 (col index 2 -> "ITEM-999")
        assertEquals("ITEM-999", row.get(3));
    }
}
