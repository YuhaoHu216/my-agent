package space.huyuhao.myagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserMcpServerDto {
    private Long id;
    private String serverName;
    private String url;
    private Integer enabled;
    private Integer connectStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
