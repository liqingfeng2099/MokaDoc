package com.kanade.backend.graph;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 图谱文档预处理器。
 * 负责从已上传的文档文件中提取纯文本并统计 token 数量，
 * 为智能分批模块提供输入数据。
 *
 * @author kanade
 */
@Slf4j
@Component
public class GraphDocumentPreprocessor {

    @Value("${file.upload.dir:./uploads/documents}")
    private String uploadDir;

    private final Encoding encoding;
    private static final int MAX_TEXT_LENGTH = 500_000; // 单文档最大读取字符数

    public GraphDocumentPreprocessor() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE); // GPT-4/DeepSeek 兼容
    }

    /**
     * 预处理结果——纯文本 + token 统计。
     */
    public record PreprocessedDoc(long docId, String docName, String text, int tokenCount) {
    }

    /**
     * 预处理单个文档：读取文件 → 提取文本 → 计数 token。
     *
     * @param docId     文档 ID
     * @param docName   文档名称
     * @param filePath  文件存储路径（相对于 uploadDir）
     * @return 预处理结果，如果文件不存在或读取失败则返回 null
     */
    public PreprocessedDoc preprocess(Long docId, String docName, String filePath) {
        try {
            Path path = Paths.get(uploadDir, filePath);
            if (!path.toFile().exists()) {
                log.warn("⚠️ [预处理] 文件不存在: docId={}, path={}", docId, path);
                return null;
            }

            String text = extractText(path, docName);
            if (StrUtil.isBlank(text)) {
                log.warn("⚠️ [预处理] 文本为空: docId={}", docId);
                return null;
            }

            // 截断过长文本
            if (text.length() > MAX_TEXT_LENGTH) {
                log.warn("⚠️ [预处理] 文本过长({}), 截断至 {} 字符: docId={}",
                    text.length(), MAX_TEXT_LENGTH, docId);
                text = text.substring(0, MAX_TEXT_LENGTH);
            }

            int tokenCount = encoding.countTokens(text);
            log.info("✅ [预处理] docId={}, name={}, chars={}, tokens={}",
                docId, docName, text.length(), tokenCount);

            return new PreprocessedDoc(docId, docName, text, tokenCount);
        } catch (Exception e) {
            log.error("❌ [预处理失败] docId={}, error={}", docId, e.getMessage());
            return null;
        }
    }

    /**
     * 从文件路径提取纯文本。
     * 根据扩展名选择解析策略：
     * - .txt/.md → 直接读取 UTF-8
     * - .pdf → 简单文本提取（使用 Hutool 或 Tika，此处先用基础方式）
     * - .doc/.docx → 同 PDF 处理
     */
    private String extractText(Path path, String docName) {
        String fileName = docName != null ? docName.toLowerCase() : path.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
            // 纯文本文件直接读取
            return FileUtil.readString(path.toFile(), StandardCharsets.UTF_8);
        }

        if (fileName.endsWith(".pdf")) {
            // PDF 简单文本提取（Hutool 不支持 PDF，读取原始字节尝试提取可读文本）
            return extractPdfText(path);
        }

        if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            // Word 文档简单文本提取
            return extractWordText(path, fileName);
        }

        // 未知格式——尝试作为文本读取
        log.warn("⚠️ [文本提取] 未知格式: {}, 尝试按UTF-8读取", fileName);
        return FileUtil.readString(path.toFile(), StandardCharsets.UTF_8);
    }

    /**
     * PDF 简单文本提取。
     * 当前使用基础方式（读取原始字节过滤可打印字符）。
     * 后续可集成 Apache PDFBox 或 Tika 以提升精度。
     */
    private String extractPdfText(Path path) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                char c = (char) (b & 0xFF);
                if (c >= 32 && c < 127 || c == '\n' || c == '\r' || c == '\t') {
                    sb.append(c);
                } else if (c >= 0x4E00 && c <= 0x9FFF) {
                    // 中文字符范围（UTF-8 多字节，此处简化处理）
                    sb.append(c);
                }
            }
            String result = sb.toString();
            // 过滤过短的行
            return result.replaceAll("(?m)^.{1,2}$\n?", "");
        } catch (Exception e) {
            log.error("❌ [PDF提取失败] path={}, error={}", path, e.getMessage());
            return "";
        }
    }

    /**
     * Word 文档文本提取。
     * - .docx：ZIP 格式，提取 word/document.xml 中的文本
     * - .doc：二进制格式，提取可打印字符
     */
    private String extractWordText(Path path, String fileName) {
        if (fileName != null && fileName.endsWith(".docx")) {
            return extractDocxText(path);
        }
        return extractLegacyDocText(path);
    }

    /**
     * .docx 文本提取（ZIP → word/document.xml → 文本）。
     */
    private String extractDocxText(Path path) {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                java.nio.file.Files.newInputStream(path))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("word/document.xml")) {
                    String xml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    return stripXmlTags(xml);
                }
            }
            log.warn("⚠️ [DOCX提取] 未找到 word/document.xml");
            return "";
        } catch (Exception e) {
            log.error("❌ [DOCX提取失败] path={}, error={}", path, e.getMessage());
            return "";
        }
    }

    /**
     * .doc 文本提取（二进制过滤，精度较低）。
     */
    private String extractLegacyDocText(Path path) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                char c = (char) (b & 0xFF);
                if (c >= 32 && c < 127 || c == '\n' || c == '\r' || c == '\t') {
                    sb.append(c);
                }
            }
            String result = sb.toString();
            // 清理 XML/二进制噪音
            result = result.replaceAll("<[^>]+>", " ");
            result = result.replaceAll("\\s+", " ");
            return result.trim();
        } catch (Exception e) {
            log.error("❌ [DOC提取失败] path={}, error={}", path, e.getMessage());
            return "";
        }
    }

    /**
     * 去除 XML 标签，提取纯文本。
     * 特别处理 <w:t> 标签（OOXML 文本元素）和段落标记。
     */
    private String stripXmlTags(String xml) {
        // 1. 提取所有 <w:t> 标签内的文本（Word 文本内容）
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<w:t[^>]*>([^<]*)</w:t>")
                .matcher(xml);
        while (m.find()) {
            String text = m.group(1);
            if (text != null && !text.isBlank()) {
                sb.append(text);
            }
        }
        String result = sb.toString();

        // 2. 如果 <w:t> 提取为空，则通用去除所有 XML 标签
        if (result.isEmpty()) {
            result = xml.replaceAll("<[^>]+>", " ");
            result = result.replaceAll("\\s+", " ");
        }

        // 3. 解码 XML 实体
        result = result.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");

        return result.trim();
    }
}
