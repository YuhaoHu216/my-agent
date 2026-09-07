package space.huyuhao.myagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserSkillDto {
    private Long id;
    private String skillName;
    private String skillContent;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}