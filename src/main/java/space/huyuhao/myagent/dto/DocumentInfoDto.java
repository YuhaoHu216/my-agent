package space.huyuhao.myagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentInfoDto {
    private Long id;
    private String fileName;
    private Long fileSize;
    private String fileExtension;
    private Integer chunkCount;
    /** 向量嵌入模型名（仅已向量化文档有值） */
    private String vectorModel;
    /** 是否已向量化入库（chunkCount > 0） */
    private boolean vectorized;
    private LocalDateTime createTime;
}
