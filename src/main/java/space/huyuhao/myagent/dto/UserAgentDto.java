package space.huyuhao.myagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserAgentDto {
    private Long id;
    private String agentName;
    private String systemPrompt;
    private String nextStepPrompt;
    private String provider;
    private String modelName;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}