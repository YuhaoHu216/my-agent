package space.huyuhao.myagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserOrchestratorRequest {
    private Long id;
    @NotBlank(message = "编排器名称不能为空")
    private String orchestratorName;
    @NotBlank(message = "系统提示词不能为空")
    private String systemPrompt;
    @NotBlank(message = "模型提供商不能为空")
    private String provider;
    @NotBlank(message = "模型名称不能为空")
    private String modelName;
    private Integer enabled;
}