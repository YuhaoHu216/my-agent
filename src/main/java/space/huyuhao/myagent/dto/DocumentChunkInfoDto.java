package space.huyuhao.myagent.dto;

import lombok.Data;

/**
 * 文档分片详情（用于查看某文档被切分的每个 chunk 内容）
 */
@Data
public class DocumentChunkInfoDto {
    /** 向量库中该分片的记录 id */
    private String chunkId;
    /** 分片序号（从 0 起） */
    private Integer chunkIndex;
    /** 该文档分片总数 */
    private Integer totalChunks;
    /** 分片章节标题（可空） */
    private String title;
    /** 分片正文内容 */
    private String content;
}
