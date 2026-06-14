package space.huyuhao.myagent.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量库调试控制器（仅 simple 模式可用）
 * 用于诊断 SimpleVectorStore 中文档的上传、检索是否正常
 */
@RestController
@RequestMapping("/simple-vector-store")
@ConditionalOnProperty(name = "vector.store.type", havingValue = "simple")
public class SimpleVectorStoreController {

    private static final Logger log = LoggerFactory.getLogger(SimpleVectorStoreController.class);

    @Autowired
    private VectorStore vectorStore;

    /**
     * 检查 VectorStore Bean 类型和状态
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vectorStoreType", vectorStore.getClass().getName());
        result.put("isSimpleVectorStore", vectorStore instanceof SimpleVectorStore);

        if (vectorStore instanceof SimpleVectorStore svs) {
            try {
                // 通过反射获取 store Map，避免受 protected 限制
                var field = SimpleVectorStore.class.getDeclaredField("store");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, ?> store = (Map<String, ?>) field.get(svs);
                result.put("totalDocumentsInStore", store.size());

                // 按来源分组统计
                Map<String, Long> sourceCounts = new LinkedHashMap<>();
                for (var entry : store.entrySet()) {
                    try {
                        var content = entry.getValue();
                        var getMetadata = content.getClass().getMethod("getMetadata");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> metadata = (Map<String, Object>) getMetadata.invoke(content);
                        String source = String.valueOf(metadata.getOrDefault("_source", "unknown"));
                        sourceCounts.merge(source, 1L, Long::sum);
                    } catch (Exception ignored) {
                        sourceCounts.merge("unknown", 1L, Long::sum);
                    }
                }
                result.put("documentsBySource", sourceCounts);
            } catch (Exception e) {
                result.put("error", "无法读取 store 内部状态: " + e.getMessage());
            }
        }
        return result;
    }

    /**
     * 直接搜索向量库，返回原始结果（不经过 RAG 上下文注入）
     * 用于对比：如果这里能搜到但 AI 对话不行，说明是 RAG 注入的问题
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String query, @RequestParam(defaultValue = "5") int topK) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("topK", topK);

        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);

            result.put("hitCount", docs.size());
            List<Map<String, Object>> hits = new ArrayList<>();
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("rank", i + 1);
                hit.put("id", doc.getId());
                hit.put("score", doc.getMetadata().getOrDefault("distance", "N/A"));
                hit.put("source", doc.getMetadata().getOrDefault("_source", "N/A"));
                hit.put("fileName", doc.getMetadata().getOrDefault("_file_name", "N/A"));
                hit.put("userId", doc.getMetadata().getOrDefault("userId", "N/A"));
                // 截断内容，避免响应过大
                String text = doc.getText();
                hit.put("contentPreview", text != null ? text.substring(0, Math.min(200, text.length())) : "");
                hits.add(hit);
            }
            result.put("hits", hits);

            if (docs.isEmpty()) {
                result.put("suggestion", "向量库中未找到匹配文档。请检查：1) 是否成功上传了 .md 文件 2) EmbeddingModel 是否正常工作");
            }
        } catch (Exception e) {
            log.error("向量搜索调试失败", e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 列出向量库中所有文档的基本信息（分页，避免过大）
     */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (!(vectorStore instanceof SimpleVectorStore svs)) {
            result.put("error", "当前 VectorStore 不是 SimpleVectorStore，无法列出文档");
            return result;
        }

        try {
            var field = SimpleVectorStore.class.getDeclaredField("store");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ?> store = (Map<String, ?>) field.get(svs);

            List<Map<String, Object>> docs = new ArrayList<>();
            int total = store.size();

            var entries = store.entrySet().stream()
                    .skip((long) page * size)
                    .limit(size)
                    .collect(Collectors.toList());

            for (var entry : entries) {
                try {
                    var content = entry.getValue();
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", content.getClass().getMethod("getId").invoke(content));
                    String text = (String) content.getClass().getMethod("getText").invoke(content);
                    info.put("textPreview", text != null ? text.substring(0, Math.min(150, text.length())) : "");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = (Map<String, Object>) content.getClass().getMethod("getMetadata").invoke(content);
                    info.put("source", metadata.getOrDefault("_source", "N/A"));
                    info.put("fileName", metadata.getOrDefault("_file_name", metadata.getOrDefault("filename", "N/A")));
                    info.put("userId", metadata.getOrDefault("userId", "N/A"));
                    Object embeddingObj = content.getClass().getMethod("getEmbedding").invoke(content);
                    if (embeddingObj instanceof float[] embedding) {
                        info.put("embeddingLength", embedding.length);
                        // 检查是否为零向量
                        boolean allZero = true;
                        for (float v : embedding) {
                            if (v != 0.0f) { allZero = false; break; }
                        }
                        if (allZero) {
                            info.put("warning", "嵌入向量全为零！可能存在 EmbeddingModel 调用失败的问题");
                        }
                    } else {
                        info.put("embedding", "null（文档未被嵌入！）");
                    }
                    docs.add(info);
                } catch (Exception ignored) {
                    // 跳过无法解析的条目
                }
            }

            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("documents", docs);
        } catch (Exception e) {
            result.put("error", "无法读取 store: " + e.getMessage());
        }

        return result;
    }
}
