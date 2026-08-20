package space.huyuhao.myagent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户自定义 LLM 供应商配置视图对象，apiKey 为脱敏值。
 * models 为该供应商下可用的全部模型名。
 */
@Data
public class UserLlmConfigDto {
    private Long id;
    private String provider;
    private String providerLabel;
    private String apiKey;
    private Integer enabled;
    private List<String> models;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
