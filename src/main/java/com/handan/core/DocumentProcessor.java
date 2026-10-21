package com.handan.core;

import com.handan.excel.ExcelAppendService;
import com.handan.extractor.DocumentExtractorFactory;
import com.handan.order.OrderProcessor;

/**
 * 历史门面调度器兼容层（Facade Pattern）
 * <p>
 * 为保证向后兼容性保留此类，所有业务逻辑已下沉至领域服务 {@link com.handan.order.OrderProcessor}。
 *
 * @deprecated 请直接使用 {@link com.handan.order.OrderProcessor}
 */
@Deprecated
public class DocumentProcessor extends OrderProcessor {

    public DocumentProcessor() {
        super();
    }

    public DocumentProcessor(DocumentExtractorFactory extractorFactory, ExcelAppendService excelAppendService) {
        super(extractorFactory, excelAppendService);
    }
}
