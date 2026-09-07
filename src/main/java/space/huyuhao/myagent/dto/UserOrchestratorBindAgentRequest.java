package space.huyuhao.myagent.dto;

import lombok.Data;

import java.util.List;

@Data
public class UserOrchestratorBindAgentRequest {
    private Long orchestratorId;
    private List<Long> agentIds;
}