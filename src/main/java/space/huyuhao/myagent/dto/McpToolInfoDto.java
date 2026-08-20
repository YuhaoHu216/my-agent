package space.huyuhao.myagent.dto;

import lombok.Data;

import java.util.Map;

@Data
public class McpToolInfoDto {
    private String name;
    private String description;
    /** 参数 JSON Schema：{type, properties, required} */
    private Map<String, Object> inputSchema;
}
