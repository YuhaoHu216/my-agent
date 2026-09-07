package space.huyuhao.myagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserSkillRequest {
    private Long id;
    @NotBlank(message = "技能名称不能为空")
    private String skillName;
    @NotBlank(message = "技能内容不能为空")
    private String skillContent;
    private Integer enabled;
}