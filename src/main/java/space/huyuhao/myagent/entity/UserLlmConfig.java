package space.huyuhao.myagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自定义 LLM 供应商配置实体，对应表 user_llm_config（每个供应商一个 api-key）。
 * provider 存 ModelEnum.Provider 枚举名（DASHSCOPE / DEEPSEEK）。
 * 该供应商下可配置的多个模型见 user_llm_model 表。
 */
@Data
@TableName("user_llm_config")
public class UserLlmConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("provider")
    private String provider;

    @TableField("api_key")
    private String apiKey;

    @TableField("enabled")
    private Integer enabled;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
