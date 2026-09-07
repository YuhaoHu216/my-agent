package space.huyuhao.myagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserAgentRequest {
    private Long id;
    @NotBlank(message = "Agent名称不能为空")
    private String agentName;
    @NotBlank(message = "系统提示词不能为空")
    private String systemPrompt;
    private String nextStepPrompt;
    @NotBlank(message = "模型提供商不能为空")
    private String provider;
    @NotBlank(message = "模型名称不能为空")
    private String modelName;
    private Integer enabled;
}