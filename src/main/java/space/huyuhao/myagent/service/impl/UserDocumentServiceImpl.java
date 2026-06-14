package space.huyuhao.myagent.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.*;
import space.huyuhao.myagent.entity.UserDocument;
import space.huyuhao.myagent.mapper.UserDocumentMapper;
import space.huyuhao.myagent.rag.DocumentChunk;
import space.huyuhao.myagent.service.*;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserDocumentServiceImpl implements UserDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(UserDocumentServiceImpl.class);
    /** 需要分块+向量化的格式 */
    private static final Set<String> VECTORIZABLE_EXTENSIONS = Set.of(".md");
    /** 仅存储到磁盘、不走向量数据库的格式 */
    private static final Set<String> STORABLE_EXTENSIONS = Set.of(".txt",".docx",".doc",".pdf",".vsdx");

    @Autowired
    private UserDocumentMapper userDocumentMapper;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private VectorStore vectorStore;

    @Value("${file.upload.path}")
    private String uploadPath;

    @PostConstruct
    public void init() {
        this.uploadPath = Paths.get(uploadPath).toAbsolutePath().normalize().toString();
    }

    @Override
    public ResponseResult<DocumentUploadResultDto> upload(MultipartFile file) {
        Long userId = UserContext.getUserId();
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            return ResponseResult.error("文件名不能为空");
        }

        // 校验扩展名
        String extension = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return ResponseResult.error("不支持的文件类型，仅支持 .txt 和 .md");
        }
        extension = originalName.substring(dotIndex).toLowerCase();
        boolean isVectorizable = VECTORIZABLE_EXTENSIONS.contains(extension);
        boolean isStorable = STORABLE_EXTENSIONS.contains(extension);
        if (!isVectorizable && !isStorable) {
            return ResponseResult.error("不支持的文件类型，仅支持 .txt 和 .md");
        }

        if (file.isEmpty()) {
            return ResponseResult.error("文件不能为空");
        }

        try {
            // 1. 创建用户目录
            Path userDir = Paths.get(uploadPath, String.valueOf(userId));
            Files.createDirectories(userDir);

            // 2. 生成唯一文件名并保存
            String uniqueName = UUID.randomUUID().toString().replace("-", "") + "_" + originalName;
            Path savedPath = userDir.resolve(uniqueName);
            file.transferTo(savedPath.toFile());

            // 3. 构建相对路径
            String relativePath = userId + "/" + uniqueName;
            String normalizedSource = relativePath.replace(File.separator, "/");

            int chunkCount = 0;

            if (isVectorizable) {
                // 向量化流程：读取 → 分块 → 构建 Document → 通过 VectorStore 入库
                String content = Files.readString(savedPath);
                if (content.isBlank()) {
                    Files.deleteIfExists(savedPath);
                    return ResponseResult.error("文件内容为空");
                }

                List<DocumentChunk> chunks = chunkService.chunkDocument(content, savedPath.toString());
                if (chunks.isEmpty()) {
                    Files.deleteIfExists(savedPath);
                    return ResponseResult.error("文件分块失败");
                }

                addChunksToVectorStore(userId, chunks, normalizedSource, originalName, extension);
                chunkCount = chunks.size();
            }

            // 4. 记录到 MySQL
            UserDocument doc = new UserDocument();
            doc.setUserId(userId);
            doc.setFileName(originalName);
            doc.setFilePath(normalizedSource);
            doc.setFileSize(file.getSize());
            doc.setFileExtension(extension);
            doc.setChunkCount(chunkCount);
            doc.setStatus(1);
            doc.setCreateTime(LocalDateTime.now());
            doc.setUpdateTime(LocalDateTime.now());
            userDocumentMapper.insert(doc);

            // 8. 构建响应
            DocumentUploadResultDto result = new DocumentUploadResultDto();
            result.setId(doc.getId());
            result.setFileName(originalName);
            result.setFileSize(file.getSize());
            result.setChunkCount(chunkCount);
            result.setCreateTime(doc.getCreateTime());

            logger.info("文档上传成功: userId={}, fileName={}, chunks={}", userId, originalName, chunkCount);
            return ResponseResult.success("上传成功", result);

        } catch (Exception e) {
            logger.error("文档上传失败", e);
            return ResponseResult.error("上传失败: " + e.getMessage());
        }
    }

    @Override
    public ResponseResult<List<DocumentInfoDto>> list() {
        Long userId = UserContext.getUserId();
        List<UserDocument> docs = userDocumentMapper.selectByUserId(userId);

        List<DocumentInfoDto> result = docs.stream().map(doc -> {
            DocumentInfoDto dto = new DocumentInfoDto();
            dto.setId(doc.getId());
            dto.setFileName(doc.getFileName());
            dto.setFileSize(doc.getFileSize());
            dto.setFileExtension(doc.getFileExtension());
            dto.setChunkCount(doc.getChunkCount());
            dto.setCreateTime(doc.getCreateTime());
            return dto;
        }).collect(Collectors.toList());

        return ResponseResult.success(result);
    }

    @Override
    public ResponseResult<List<DocumentSearchResultDto>> search(DocumentSearchRequestDto request) {
        Long userId = UserContext.getUserId();

        int topK = request.getTopK() != null ? request.getTopK() : 10;

        // 构建 Filter.Expression: metadata["userId"] == userId
        Filter.Expression filter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("userId"),
                new Filter.Value(userId)
        );

        SearchRequest searchRequest = SearchRequest.builder()
                .query(request.getQuery())
                .topK(topK)
                .filterExpression(filter)
                .build();

        List<Document> docs = vectorStore.similaritySearch(searchRequest);

        List<DocumentSearchResultDto> result = docs.stream().map(doc -> {
            DocumentSearchResultDto dto = new DocumentSearchResultDto();
            dto.setChunkId(doc.getId());
            dto.setContent(doc.getText());
            dto.setScore(((Number) doc.getMetadata().getOrDefault("distance", 0.0f)).floatValue());
            dto.setFileName((String) doc.getMetadata().get("_file_name"));
            Object chunkIndexObj = doc.getMetadata().get("chunkIndex");
            if (chunkIndexObj instanceof Number) {
                dto.setChunkIndex(((Number) chunkIndexObj).intValue());
            }
            Object totalChunksObj = doc.getMetadata().get("totalChunks");
            if (totalChunksObj instanceof Number) {
                dto.setTotalChunks(((Number) totalChunksObj).intValue());
            }
            return dto;
        }).collect(Collectors.toList());

        return ResponseResult.success(result);
    }

    @Override
    public ResponseResult<String> delete(Long documentId) {
        Long userId = UserContext.getUserId();

        // 1. 验证所有权
        UserDocument doc = userDocumentMapper.selectByUserIdAndId(userId, documentId);
        if (doc == null) {
            return ResponseResult.error(404, "文档不存在或无权操作");
        }

        try {
            // 2. 仅向量化文档需要从向量库删除
            if (doc.getChunkCount() != null && doc.getChunkCount() > 0) {
                // 构建 Filter.Expression: metadata["userId"] == xxx AND metadata["_source"] == "xxx"
                Filter.Expression filter = new Filter.Expression(
                        Filter.ExpressionType.AND,
                        new Filter.Expression(Filter.ExpressionType.EQ,
                                new Filter.Key("userId"), new Filter.Value(userId)),
                        new Filter.Expression(Filter.ExpressionType.EQ,
                                new Filter.Key("_source"), new Filter.Value(doc.getFilePath()))
                );
                vectorStore.delete(filter);
                logger.info("已从向量库删除文档相关向量: userId={}, filePath={}", userId, doc.getFilePath());
            }

            // 3. MySQL 软删除
            UserDocument update = new UserDocument();
            update.setId(doc.getId());
            update.setStatus(0);
            userDocumentMapper.updateById(update);

            // 4. 删除磁盘文件（非致命操作）
            try {
                Path filePath = Paths.get(uploadPath, doc.getFilePath()).normalize();
                Path uploadDir = Paths.get(uploadPath).normalize().toAbsolutePath();
                if (filePath.toAbsolutePath().startsWith(uploadDir)) {
                    Files.deleteIfExists(filePath);
                } else {
                    logger.warn("文件路径越权，忽略删除: {}", doc.getFilePath());
                }
            } catch (IOException e) {
                logger.warn("删除磁盘文件失败: {}", doc.getFilePath(), e);
            }

            logger.info("文档删除成功: userId={}, docId={}, fileName={}", userId, documentId, doc.getFileName());
            return ResponseResult.success("删除成功");

        } catch (Exception e) {
            logger.error("删除文档失败", e);
            return ResponseResult.error("删除失败: " + e.getMessage());
        }
    }

    @Override
    public ResponseEntity<?> download(Long documentId) {
        Long userId = UserContext.getUserId();
        UserDocument doc = userDocumentMapper.selectByUserIdAndId(userId, documentId);
        if (doc == null) {
            return ResponseEntity.status(404)
                    .body(ResponseResult.error(404, "文档不存在或无权操作"));
        }

        try {
            Path filePath = Paths.get(uploadPath, doc.getFilePath()).normalize();
            Path uploadDir = Paths.get(uploadPath).normalize().toAbsolutePath();
            if (!filePath.toAbsolutePath().startsWith(uploadDir)) {
                logger.warn("文件路径越权，禁止下载: {}", doc.getFilePath());
                return ResponseEntity.status(403)
                        .body(ResponseResult.error(403, "文件路径不合法"));
            }

            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists()) {
                return ResponseEntity.status(404)
                        .body(ResponseResult.error(404, "文件不存在"));
            }

            String encodedFileName = URLEncoder.encode(doc.getFileName(), StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encodedFileName)
                    .body(resource);

        } catch (Exception e) {
            logger.error("下载文档失败: docId={}", documentId, e);
            return ResponseEntity.status(500)
                    .body(ResponseResult.error("下载失败"));
        }
    }

    /**
     * 将已分片的文档通过 VectorStore 接口写入向量库
     */
    private void addChunksToVectorStore(Long userId, List<DocumentChunk> chunks,
                                        String source, String fileName, String extension) {
        int totalChunks = chunks.size();
        List<Document> chunkDocs = new ArrayList<>();

        for (int i = 0; i < totalChunks; i++) {
            DocumentChunk chunk = chunks.get(i);

            // 构建 metadata（会被 MilvusVectorStore.add() 复制到每个分片记录中）
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId);
            metadata.put("_source", source);
            metadata.put("_file_name", fileName);
            metadata.put("_extension", extension);
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("totalChunks", totalChunks);
            if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
                metadata.put("title", chunk.getTitle());
            }

            // 生成确定性 ID
            String sourceKey = userId + "_" + source;
            String id = UUID.nameUUIDFromBytes((sourceKey + "_" + chunk.getChunkIndex()).getBytes()).toString();

            // 构建 Spring AI Document（包含 chunkIndex 标记为已分片）
            Document doc = new Document(id, chunk.getContent(), metadata);
            chunkDocs.add(doc);
        }

        vectorStore.add(chunkDocs);
        logger.info("已通过 VectorStore 写入 {} 个分片: source={}", totalChunks, source);
    }
}
