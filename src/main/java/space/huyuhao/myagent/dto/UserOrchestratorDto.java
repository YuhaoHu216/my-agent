package space.huyuhao.myagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserOrchestratorDto {
    private Long id;
    private String orchestratorName;
    private String systemPrompt;
    private String provider;
    private String modelName;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}