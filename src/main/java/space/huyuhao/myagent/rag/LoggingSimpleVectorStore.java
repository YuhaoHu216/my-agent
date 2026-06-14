package space.huyuhao.myagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

/**
 * SimpleVectorStore 日志包装器，为内存向量库操作添加与 MilvusVectorStore 一致的日志。
 * <p>
 * 所有操作委托给内部的 {@link SimpleVectorStore}，在关键节点输出 INFO/DEBUG 日志，
 * 便于排查 RAG 检索效果和文档加载问题。
 */
public class LoggingSimpleVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(LoggingSimpleVectorStore.class);

    private static final int CONTENT_PREVIEW_MAX = 200;

    private final SimpleVectorStore delegate;

    public LoggingSimpleVectorStore(SimpleVectorStore delegate) {
        this.delegate = delegate;
    }

    /** 获取被包装的原生 SimpleVectorStore（用于需要反射访问内部 store 的场景） */
    public SimpleVectorStore getDelegate() {
        return delegate;
    }

    // ==================== add ====================

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            log.debug("SimpleVectorStore: 跳过空文档列表");
            return;
        }

        // 统计各来源的文档数
        long distinctSources = documents.stream()
                .map(d -> d.getMetadata() != null ? d.getMetadata().getOrDefault("_source", "N/A") : "N/A")
                .distinct()
                .count();

        log.info("SimpleVectorStore: 添加 {} 个文档分片 ({} 个来源)", documents.size(), distinctSources);
        delegate.add(documents);
        log.info("SimpleVectorStore: 成功添加 {} 个文档分片", documents.size());
    }

    // ==================== similaritySearch ====================

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String query = request.getQuery();
        int topK = request.getTopK() > 0 ? request.getTopK() : SearchRequest.DEFAULT_TOP_K;
        double threshold = request.getSimilarityThreshold();
        Filter.Expression filterExpression = request.getFilterExpression();

        log.info("SimpleVectorStore 向量搜索: query=\"{}\", topK={}, threshold={}, filter={}",
                query, topK, threshold, filterExpression);

        try {
            List<Document> documents = delegate.similaritySearch(request);

            // 打印检索到的文档片段，便于调试 RAG 效果（与 MilvusVectorStore 格式一致）
            log.info("SimpleVectorStore RAG 检索结果: query=\"{}\", 命中 {} 条", query, documents.size());
            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                String text = doc.getText();
                String preview = (text != null && text.length() > CONTENT_PREVIEW_MAX)
                        ? text.substring(0, CONTENT_PREVIEW_MAX) + "..."
                        : text;
                log.info("  [{}] score={}, source={}, fileName={}, userId={}, content={}",
                        i + 1,
                        String.format("%.4f", doc.getMetadata().getOrDefault("distance", "N/A")),
                        doc.getMetadata().getOrDefault("_source", "N/A"),
                        doc.getMetadata().getOrDefault("_file_name", "N/A"),
                        doc.getMetadata().getOrDefault("userId", "N/A"),
                        preview);
            }

            if (documents.isEmpty()) {
                log.warn("SimpleVectorStore RAG 检索返回 0 条结果！query=\"{}\"，请检查向量库中是否有相关文档", query);
            }

            return documents;

        } catch (Exception e) {
            log.error("SimpleVectorStore 向量搜索失败: query=\"{}\"", query, e);
            throw new RuntimeException("SimpleVectorStore 向量搜索失败: " + e.getMessage(), e);
        }
    }

    // ==================== delete ====================

    @Override
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            log.debug("SimpleVectorStore: 跳过空 ID 删除列表");
            return;
        }
        log.info("SimpleVectorStore: 按 ID 列表删除 {} 个文档: {}", idList.size(), idList);
        delegate.delete(idList);
        log.info("SimpleVectorStore: 删除成功 (ID 列表模式)");
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        // SimpleVectorStore 的 delete(Filter.Expression) 默认抛出 UnsupportedOperationException
        // 实际按过滤条件删除通过 UserDocumentServiceImpl.deleteFromSimpleVectorStore() 反射操作 store Map
        log.info("SimpleVectorStore: 按过滤条件删除文档: {}", filterExpression);
        try {
            delegate.delete(filterExpression);
            log.info("SimpleVectorStore: 按过滤条件删除成功");
        } catch (UnsupportedOperationException e) {
            log.warn("SimpleVectorStore 不支持 delete(Filter.Expression)，将使用反射方式删除内部 store");
            throw e;
        }
    }
}