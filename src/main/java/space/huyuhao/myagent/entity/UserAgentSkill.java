package space.huyuhao.myagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_agent_skill")
public class UserAgentSkill {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("agent_id")
    private Long agentId;

    @TableField("skill_id")
    private Long skillId;

    @TableField("create_time")
    private LocalDateTime createTime;
}