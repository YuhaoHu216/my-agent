package space.huyuhao.myagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UserMcpServerRequest {
    private Long id;
    @NotBlank(message = "服务名称不能为空")
    private String serverName;
    @NotBlank(message = "服务地址不能为空")
    @Pattern(regexp = "^https?://.+", message = "服务地址必须以 http:// 或 https:// 开头")
    private String url;
    private Integer enabled;
}
