package space.huyuhao.myagent.rag;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import space.huyuhao.myagent.constant.MilvusConstants;
import space.huyuhao.myagent.service.DocumentChunkService;
import space.huyuhao.myagent.service.MilvusSearchService;
import space.huyuhao.myagent.service.VectorEmbeddingService;

import java.util.*;
import java.util.stream.Collectors;

public class MilvusVectorStore implements VectorStore {

    private static final Logger logger = LoggerFactory.getLogger(MilvusVectorStore.class);

    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;
    private final MilvusSearchService searchService;
    private final DocumentChunkService chunkService;
    private final Gson gson = new Gson();

    public MilvusVectorStore(MilvusServiceClient milvusClient,
                             VectorEmbeddingService embeddingService,
                             MilvusSearchService searchService,
                             DocumentChunkService chunkService) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.searchService = searchService;
        this.chunkService = chunkService;
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        logger.info("向 Milvus 添加 {} 个文档", documents.size());

        for (Document document : documents) {
            try {
                List<DocumentChunk> chunks = chunkService.chunkDocument(
                        document.getText(),
                        document.getId() != null ? document.getId() : "unknown"
                );

                if (chunks.isEmpty()) {
                    chunks = List.of(new DocumentChunk(
                            document.getText(), 0, document.getText().length(), 0
                    ));
                }

                for (DocumentChunk chunk : chunks) {
                    List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());
                    insertToMilvus(document, chunk, vector);
                }
            } catch (Exception e) {
                logger.error("添加文档失败: {}", document.getId(), e);
                throw new RuntimeException("添加文档到 Milvus 失败: " + e.getMessage(), e);
            }
        }

        logger.info("成功添加 {} 个文档到 Milvus", documents.size());
    }

    private void insertToMilvus(Document doc, DocumentChunk chunk, List<Float> vector) {
        loadCollection();

        String id;
        if (doc.getId() != null && !doc.getId().isEmpty()) {
            id = chunk.getChunkIndex() == 0 ? doc.getId() : doc.getId() + "_" + chunk.getChunkIndex();
        } else {
            id = UUID.randomUUID().toString();
        }

        Map<String, Object> metadata = new HashMap<>();
        if (doc.getMetadata() != null) {
            metadata.putAll(doc.getMetadata());
        }
        metadata.put("chunkIndex", chunk.getChunkIndex());
        if (chunk.getTitle() != null) {
            metadata.put("title", chunk.getTitle());
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(chunk.getContent())));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));

        JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withFields(fields)
                .build();

        R<MutationResult> response = milvusClient.insert(insertParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("插入向量失败: " + response.getMessage());
        }

        logger.debug("文档插入成功: id={}", id);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String query = request.getQuery();
        int topK = request.getTopK() > 0 ? request.getTopK() : SearchRequest.DEFAULT_TOP_K;
        double threshold = request.getSimilarityThreshold();

        logger.debug("向量搜索: query={}, topK={}, threshold={}", query, topK, threshold);

        try {
            loadCollection();
            List<MilvusSearchService.SearchResult> results = searchService.searchSimilarDocuments(query, topK);

            List<Document> documents = results.stream()
                    .filter(r -> {
                        if (threshold <= SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL + 0.001) {
                            return true;
                        }
                        double similarity = 1.0 / (1.0 + r.getScore());
                        return similarity >= threshold;
                    })
                    .map(r -> {
                        Map<String, Object> metadata = new HashMap<>();
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> parsedMeta = gson.fromJson(r.getMetadata(), Map.class);
                            if (parsedMeta != null) {
                                metadata.putAll(parsedMeta);
                            }
                        } catch (Exception e) {
                            logger.warn("解析 metadata 失败: {}", r.getMetadata());
                        }
                        metadata.put("distance", r.getScore());
                        return new Document(r.getId(), r.getContent(), metadata);
                    })
                    .collect(Collectors.toList());

            // 打印检索到的文档片段，便于调试 RAG 效果
            logger.info("RAG 检索结果: query=\"{}\", 命中 {} 条", query, documents.size());
            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                String preview = doc.getText().length() > 200
                        ? doc.getText().substring(0, 200) + "..."
                        : doc.getText();
                logger.info("  [{}] score={}, source={}, content={}",
                        i + 1,
                        String.format("%.4f", doc.getMetadata().getOrDefault("distance", "N/A")),
                        doc.getMetadata().getOrDefault("_source", "N/A"),
                        preview);
            }

            return documents;

        } catch (Exception e) {
            logger.error("向量搜索失败", e);
            throw new RuntimeException("Milvus 向量搜索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return;
        }

        String ids = idList.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(", "));
        deleteByExpression("id in [" + ids + "]");
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        if (filterExpression == null) {
            return;
        }
        String expr = convertFilterExpression(filterExpression);
        logger.info("根据过滤器删除: {}", expr);
        deleteByExpression(expr);
    }

    private void deleteByExpression(String expr) {
        try {
            loadCollection();

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = milvusClient.delete(deleteParam);
            if (response.getStatus() != 0) {
                throw new RuntimeException("删除失败: " + response.getMessage());
            }

            logger.info("删除成功, 影响行数: {}", response.getData().getDeleteCnt());
        } catch (Exception e) {
            logger.error("删除文档失败", e);
            throw new RuntimeException("从 Milvus 删除失败: " + e.getMessage(), e);
        }
    }

    private String convertFilterExpression(Filter.Expression exp) {
        Filter.ExpressionType type = exp.type();

        return switch (type) {
            case AND -> "(" + convertOperand(exp.left()) + " and " + convertOperand(exp.right()) + ")";
            case OR  -> "(" + convertOperand(exp.left()) + " or "  + convertOperand(exp.right()) + ")";
            case EQ  -> convertKey(exp.left())  + " == "    + convertValue(exp.right());
            case NE  -> convertKey(exp.left())  + " != "    + convertValue(exp.right());
            case GT  -> convertKey(exp.left())  + " > "     + convertValue(exp.right());
            case GTE -> convertKey(exp.left())  + " >= "    + convertValue(exp.right());
            case LT  -> convertKey(exp.left())  + " < "     + convertValue(exp.right());
            case LTE -> convertKey(exp.left())  + " <= "    + convertValue(exp.right());
            case IN  -> convertKey(exp.left())  + " in "    + convertListValue(exp.right());
            case NIN -> convertKey(exp.left())  + " not in " + convertListValue(exp.right());
            case NOT -> "not (" + convertOperand(exp.left()) + ")";
        };
    }

    private String convertOperand(Filter.Operand operand) {
        if (operand instanceof Filter.Key key) {
            return convertKey(key);
        } else if (operand instanceof Filter.Value value) {
            return convertValue(value);
        } else if (operand instanceof Filter.Expression exp) {
            return convertFilterExpression(exp);
        }
        throw new IllegalArgumentException("Unknown operand: " + operand.getClass());
    }

    private String convertKey(Filter.Operand operand) {
        if (operand instanceof Filter.Key key) {
            return "metadata[\"" + key.key() + "\"]";
        }
        throw new IllegalArgumentException("Expected Key, got: " + operand.getClass());
    }

    private String convertValue(Filter.Operand operand) {
        if (operand instanceof Filter.Value value) {
            Object v = value.value();
            if (v instanceof String) {
                return "\"" + v + "\"";
            } else if (v instanceof Number || v instanceof Boolean) {
                return v.toString();
            }
            return "\"" + v + "\"";
        }
        throw new IllegalArgumentException("Expected Value, got: " + operand.getClass());
    }

    private String convertListValue(Filter.Operand operand) {
        if (operand instanceof Filter.Value value) {
            Object v = value.value();
            if (v instanceof List<?> list) {
                String items = list.stream()
                        .map(item -> item instanceof String ? "\"" + item + "\"" : item.toString())
                        .collect(Collectors.joining(", "));
                return "[" + items + "]";
            }
            return "[" + convertValue(operand) + "]";
        }
        throw new IllegalArgumentException("Expected Value for list, got: " + operand.getClass());
    }

    private void loadCollection() {
        R<RpcStatus> response = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                        .build()
        );
        if (response.getStatus() != 0 && response.getStatus() != 65535) {
            logger.warn("加载 collection 警告: {}", response.getMessage());
        }
    }
}
