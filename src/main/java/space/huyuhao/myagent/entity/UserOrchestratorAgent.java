package space.huyuhao.myagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_orchestrator_agent")
public class UserOrchestratorAgent {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("orchestrator_id")
    private Long orchestratorId;

    @TableField("agent_id")
    private Long agentId;

    @TableField("create_time")
    private LocalDateTime createTime;
}