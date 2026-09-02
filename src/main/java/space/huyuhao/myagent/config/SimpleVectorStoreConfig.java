package space.huyuhao.myagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import space.huyuhao.myagent.rag.DocumentChunk;
import space.huyuhao.myagent.rag.LoggingSimpleVectorStore;
import space.huyuhao.myagent.service.DocumentChunkService;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Configuration
@ConditionalOnProperty(name = "vector.store.type", havingValue = "simple")
public class SimpleVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(SimpleVectorStoreConfig.class);

    @Value("${file.upload.path}")
    private String uploadPath;

    @Bean
    VectorStore vectorStore(EmbeddingModel dashscopeEmbeddingModel, DocumentChunkService chunkService,
                            DocumentProperties documentProperties) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)
                .build();
        // 从 uploads 目录加载用户已上传的规定格式文档（与上传链路共用同一扩展名集合，保持 metadata 一致）
        List<Document> documents = loadUploadedDocuments(chunkService, documentProperties.vectorizableExtensionSet());
        if (!documents.isEmpty()) {
            simpleVectorStore.add(documents);
            log.info("SimpleVectorStore: 从 uploads 目录加载了 {} 个文档分片", documents.size());
        }
        return new LoggingSimpleVectorStore(simpleVectorStore);
    }

    /**
     * 扫描 uploads 目录，加载所有用户已上传的可向量化文档。
     * <p>
     * 目录结构：uploads/{userId}/{uuid}_{originalFileName}.md
     * metadata 结构与 UserDocumentServiceImpl.addChunksToVectorStore 保持一致。
     */
    private List<Document> loadUploadedDocuments(DocumentChunkService chunkService, Set<String> vectorizableExtensions) {
        List<Document> allDocuments = new ArrayList<>();
        Path uploadDir = Paths.get(uploadPath).toAbsolutePath().normalize();

        if (!Files.exists(uploadDir) || !Files.isDirectory(uploadDir)) {
            log.warn("SimpleVectorStore: 上传目录不存在，跳过文档加载: {}", uploadDir);
            return allDocuments;
        }

        // 遍历 uploads/{userId}/ 目录
        try (DirectoryStream<Path> userDirs = Files.newDirectoryStream(uploadDir, Files::isDirectory)) {
            for (Path userDir : userDirs) {
                String userIdStr = userDir.getFileName().toString();
                Long userId;
                try {
                    userId = Long.parseLong(userIdStr);
                } catch (NumberFormatException e) {
                    log.debug("SimpleVectorStore: 跳过非用户目录: {}", userDir);
                    continue;
                }

                // 遍历用户目录下可向量化的文件
                try (DirectoryStream<Path> files = Files.newDirectoryStream(userDir, entry -> {
                    String name = entry.getFileName().toString().toLowerCase();
                    return vectorizableExtensions.stream().anyMatch(name::endsWith);
                })) {
                    for (Path file : files) {
                        try {
                            List<Document> chunks = loadSingleFile(file, userId, chunkService);
                            allDocuments.addAll(chunks);
                        } catch (Exception e) {
                            log.error("SimpleVectorStore: 加载文件失败: {}", file, e);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("SimpleVectorStore: 扫描上传目录失败", e);
        }

        log.info("SimpleVectorStore: uploads 目录扫描完成，共加载 {} 个文档分片", allDocuments.size());
        return allDocuments;
    }

    /**
     * 加载单个上传文件，分块后构建带完整 metadata 的 Document 列表。
     *
     * @param file         上传文件路径
     * @param userId       用户 ID（从父目录名解析）
     * @param chunkService 文档分块服务
     * @return 该文件的所有分块 Document
     */
    private List<Document> loadSingleFile(Path file, Long userId, DocumentChunkService chunkService) throws IOException {
        String storedFileName = file.getFileName().toString();
        String content = Files.readString(file);

        if (content.isBlank()) {
            log.debug("SimpleVectorStore: 跳过空文件: {}", file);
            return List.of();
        }

        // 确定扩展名
        String extension = "";
        int dotIndex = storedFileName.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = storedFileName.substring(dotIndex).toLowerCase();
        }

        // 从存储文件名中还原原始文件名（格式: {uuid}_{originalFileName}）
        String originalFileName = storedFileName;
        int underscoreIndex = storedFileName.indexOf('_');
        if (underscoreIndex > 0 && underscoreIndex < storedFileName.length() - 1) {
            originalFileName = storedFileName.substring(underscoreIndex + 1);
        }

        // 构建 _source（相对路径，如 "1/abc123_高中阶段.md"）
        Path uploadDir = Paths.get(uploadPath).toAbsolutePath().normalize();
        Path relativePath = uploadDir.relativize(file);
        String source = relativePath.toString().replace(File.separator, "/");

        // 分块
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, file.toString());

        // 为每个分块构建 Spring AI Document（metadata 与 UserDocumentServiceImpl.addChunksToVectorStore 一致）
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId);
            metadata.put("_source", source);
            metadata.put("_file_name", originalFileName);
            metadata.put("_extension", extension);
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("totalChunks", chunks.size());
            if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
                metadata.put("title", chunk.getTitle());
            }

            // 生成确定性 ID（与上传服务一致）
            String sourceKey = userId + "_" + source;
            String id = UUID.nameUUIDFromBytes((sourceKey + "_" + chunk.getChunkIndex()).getBytes()).toString();

            Document doc = new Document(id, chunk.getContent(), metadata);
            documents.add(doc);
        }

        log.debug("SimpleVectorStore: 加载文件 userId={}, source={}, chunks={}", userId, source, chunks.size());
        return documents;
    }
}