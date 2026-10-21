package com.handan.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.handan.config.ConfigLoader;
import com.handan.config.MappingConfig;
import com.handan.extractor.ExtractedResult;
import com.handan.progress.ProgressListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AmountValidatorTest {

    @Test
    void testAmountValidationSuccessAndFailure() throws Exception {
        MappingConfig cfg = ConfigLoader.load(new File("mapping-config.yaml"));

        // 构造明细行数据：
        // 第 2 列料号，第 3 列数量，第 4 列单价
        // 行 1: 单价 0.44，数量 30,000 -> 13200.00
        // 行 2: 单价 1.50，数量 2,000 -> 3000.00
        // 累计总金额 = 16200.00
        ExtractedResult r = new ExtractedResult(
                Map.of(),
                List.of(
                        List.of("日期", "客户", "ITEM-1", "30,000", "0.44", "13200", "2026-11-01"),
                        List.of("日期", "客户", "ITEM-2", "2,000", "1.50", "3000", "2026-11-02")
                )
        );

        List<String> logList = new ArrayList<>();
        ProgressListener listener = p -> logList.add(p.message());

        // 1. 输入一致金额 16200 -> 校验通过
        var summary1 = AmountValidator.validate(r, cfg, "16,200.00", listener);
        assertTrue(summary1.triggered());
        assertTrue(summary1.passed());
        assertTrue(summary1.summaryNotice().contains("校验通过"));

        // 2. 输入不一致金额 15000 -> 校验失败（相差 1,200.00）
        var summary2 = AmountValidator.validate(r, cfg, "15000", listener);
        assertTrue(summary2.triggered());
        assertFalse(summary2.passed());
        assertTrue(summary2.summaryNotice().contains("校验未通过"));
        assertTrue(summary2.summaryNotice().contains("1,200.00"));

        // 3. 用户不输入 -> 跳过校验
        var summary3 = AmountValidator.validate(r, cfg, "", listener);
        assertFalse(summary3.triggered());
        assertTrue(summary3.passed());
    }
}
