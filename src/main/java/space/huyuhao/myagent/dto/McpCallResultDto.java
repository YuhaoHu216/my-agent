package space.huyuhao.myagent.dto;

import lombok.Data;

@Data
public class McpCallResultDto {
    private boolean success;
    private String content;

    public McpCallResultDto() {
    }

    public McpCallResultDto(boolean success, String content) {
        this.success = success;
        this.content = content;
    }
}
