package space.huyuhao.myagent.service.impl;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import space.huyuhao.myagent.constant.MilvusConstants;
import space.huyuhao.myagent.context.UserContext;
import space.huyuhao.myagent.dto.*;
import space.huyuhao.myagent.entity.UserDocument;
import space.huyuhao.myagent.mapper.UserDocumentMapper;
import space.huyuhao.myagent.rag.DocumentChunk;
import space.huyuhao.myagent.service.*;
import space.huyuhao.myagent.service.MilvusSearchService.SearchResult;

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
    private static final Gson gson = new Gson();
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".txt", ".md");

    @Autowired
    private UserDocumentMapper userDocumentMapper;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private MilvusSearchService searchService;

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
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
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

            // 3. 读取文件内容
            String content = Files.readString(savedPath);
            if (content.isBlank()) {
                Files.deleteIfExists(savedPath);
                return ResponseResult.error("文件内容为空");
            }

            // 4. 分块
            List<DocumentChunk> chunks = chunkService.chunkDocument(content, savedPath.toString());
            if (chunks.isEmpty()) {
                Files.deleteIfExists(savedPath);
                return ResponseResult.error("文件分块失败");
            }

            // 5. 构建相对路径（用于存储和 Milvus 标识）
            String relativePath = userId + "/" + uniqueName;
            String normalizedSource = relativePath.replace(File.separator, "/");

            // 6. 向量化并插入 Milvus
            insertChunksToMilvus(userId, chunks, normalizedSource, originalName, extension);

            // 7. 记录到 MySQL
            UserDocument doc = new UserDocument();
            doc.setUserId(userId);
            doc.setFileName(originalName);
            doc.setFilePath(normalizedSource);
            doc.setFileSize(file.getSize());
            doc.setFileExtension(extension);
            doc.setChunkCount(chunks.size());
            doc.setStatus(1);
            doc.setCreateTime(LocalDateTime.now());
            doc.setUpdateTime(LocalDateTime.now());
            userDocumentMapper.insert(doc);

            // 8. 构建响应
            DocumentUploadResultDto result = new DocumentUploadResultDto();
            result.setId(doc.getId());
            result.setFileName(originalName);
            result.setFileSize(file.getSize());
            result.setChunkCount(chunks.size());
            result.setCreateTime(doc.getCreateTime());

            logger.info("文档上传成功: userId={}, fileName={}, chunks={}", userId, originalName, chunks.size());
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
        String filterExpr = "metadata[\"userId\"] == " + userId;

        List<SearchResult> searchResults = searchService.searchSimilarDocuments(request.getQuery(), topK, filterExpr);

        List<DocumentSearchResultDto> result = searchResults.stream().map(sr -> {
            DocumentSearchResultDto dto = new DocumentSearchResultDto();
            dto.setChunkId(sr.getId());
            dto.setContent(sr.getContent());
            dto.setScore(sr.getScore());

            // 解析 metadata JSON
            try {
                JsonObject meta = gson.fromJson(sr.getMetadata(), JsonObject.class);
                if (meta.has("_file_name")) {
                    dto.setFileName(meta.get("_file_name").getAsString());
                }
                if (meta.has("chunkIndex")) {
                    dto.setChunkIndex(meta.get("chunkIndex").getAsInt());
                }
                if (meta.has("totalChunks")) {
                    dto.setTotalChunks(meta.get("totalChunks").getAsInt());
                }
            } catch (Exception e) {
                logger.warn("解析搜索结果 metadata 失败: {}", sr.getMetadata());
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
            // 2. 从 Milvus 删除
            loadCollection();
            String expr = String.format("metadata[\"userId\"] == %d && metadata[\"_source\"] == \"%s\"",
                    userId, doc.getFilePath());
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();
            R<MutationResult> deleteResponse = milvusClient.delete(deleteParam);
            if (deleteResponse.getStatus() != 0) {
                logger.warn("Milvus 删除警告: {}", deleteResponse.getMessage());
            } else {
                logger.info("已从 Milvus 删除 {} 条向量记录", deleteResponse.getData().getDeleteCnt());
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

    private void insertChunksToMilvus(Long userId, List<DocumentChunk> chunks,
                                       String source, String fileName, String extension) {
        loadCollection();

        int totalChunks = chunks.size();
        for (int i = 0; i < totalChunks; i++) {
            DocumentChunk chunk = chunks.get(i);

            // 向量化
            List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());

            // 构建 metadata
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

            // 插入 Milvus
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
            fields.add(new InsertParam.Field("content", Collections.singletonList(chunk.getContent())));
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            fields.add(new InsertParam.Field("metadata", Collections.singletonList(gson.toJsonTree(metadata).getAsJsonObject())));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(fields)
                    .build();

            R<MutationResult> insertResponse = milvusClient.insert(insertParam);
            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入 Milvus 失败(chunk " + chunk.getChunkIndex() + "): " + insertResponse.getMessage());
            }

            logger.debug("已插入 chunk {}/{}, id={}", chunk.getChunkIndex() + 1, totalChunks, id);
        }
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
