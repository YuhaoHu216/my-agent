package space.huyuhao.myagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentUploadResultDto {
    private Long id;
    private String fileName;
    private Long fileSize;
    private Integer chunkCount;
    private LocalDateTime createTime;
}
