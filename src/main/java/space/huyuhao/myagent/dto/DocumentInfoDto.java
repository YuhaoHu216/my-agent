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
    private LocalDateTime createTime;
}
