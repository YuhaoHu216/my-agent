package space.huyuhao.myagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_agent")
public class UserAgent {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("agent_name")
    private String agentName;

    @TableField("system_prompt")
    private String systemPrompt;

    @TableField("next_step_prompt")
    private String nextStepPrompt;

    @TableField("provider")
    private String provider;

    @TableField("model_name")
    private String modelName;

    @TableField("enabled")
    private Integer enabled;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}