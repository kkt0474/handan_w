package com.handan.extractor;

import java.io.File;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF 格式嗅探器：判定是否包含有效字符文本层
 * - 纯文字密度高、有效字符占比高 -> 矢量电子单据 (VECTOR)
 * - 几乎无文字或纯乱码图片 -> 扫描件/纯图片单据 (SCANNED)
 */
public final class PdfSniffer {

    private static final int MIN_CHARS = 20;
    private static final double MIN_VALID_RATIO = 0.6;

    private PdfSniffer() {}

    /**
     * 判断指定 PDF 第一页是否为电子矢量件
     */
    public static boolean isVector(File pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(doc);

            int total = 0;
            int valid = 0;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isWhitespace(cp)) {
                    continue;
                }
                total++;
                boolean garbage = (cp == 0xFFFD)
                        || (cp >= 0xE000 && cp <= 0xF8FF)
                        || Character.isISOControl(cp)
                        || !Character.isDefined(cp);
                if (!garbage) {
                    valid++;
                }
            }
            return total >= MIN_CHARS && ((double) valid / total >= MIN_VALID_RATIO);
        }
    }
}
