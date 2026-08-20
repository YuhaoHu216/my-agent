package space.huyuhao.myagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 用户自定义 LLM 供应商配置请求对象。
 * apiKey 编辑时允许留空（表示保留库中旧值），新增时必填。
 * modelNames 为该供应商下全部模型名（全量替换），新增时必填至少一个。
 */
@Data
public class UserLlmConfigRequest {
    @NotBlank(message = "提供商不能为空")
    private String provider;
    private String apiKey;
    private Integer enabled;
    private List<String> modelNames;
}
