package space.huyuhao.myagent.dto;

import lombok.Data;

import java.util.List;

@Data
public class UserAgentBindSkillRequest {
    private Long agentId;
    private List<Long> skillIds;
}