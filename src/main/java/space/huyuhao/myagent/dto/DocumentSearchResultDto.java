package space.huyuhao.myagent.dto;

import lombok.Data;

@Data
public class DocumentSearchResultDto {
    private String chunkId;
    private String content;
    private float score;
    private String fileName;
    private int chunkIndex;
    private int totalChunks;
}
