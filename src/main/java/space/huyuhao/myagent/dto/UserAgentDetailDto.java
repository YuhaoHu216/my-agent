package space.huyuhao.myagent.dto;

import lombok.Data;

import java.util.List;

/**
 * Agent 详情：基础信息 + 绑定的技能/模型供应商等聚合信息。
 */
@Data
public class UserAgentDetailDto {
    private Long id;
    private String agentName;
    private String systemPrompt;
    private String nextStepPrompt;
    private String provider;
    private String modelName;
    private Integer enabled;
    private List<UserSkillDto> skills;
    private List<UserMcpServerDto> mcps;
}