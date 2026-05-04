package space.huyuhao.myagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserInfoDto {
    private Long id;
    private String username;
    private String email;
    private String phone;
    private LocalDateTime createTime;
    private Integer status;
}
