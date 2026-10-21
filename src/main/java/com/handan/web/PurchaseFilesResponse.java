package com.handan.web;

import java.util.List;

/**
 * 采购单可用 Excel 文件列表响应
 */
public record PurchaseFilesResponse(
        List<String> files,
        String recommendedSource,
        String recommendedTarget,
        String scannedDir,
        String errorMessage
) {
    public PurchaseFilesResponse(List<String> files, String recommendedSource, String recommendedTarget) {
        this(files, recommendedSource, recommendedTarget, null, null);
    }

    public PurchaseFilesResponse(List<String> files, String recommendedSource, String recommendedTarget, String scannedDir) {
        this(files, recommendedSource, recommendedTarget, scannedDir, null);
    }
}
