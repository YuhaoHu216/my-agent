package space.huyuhao.myagent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DocumentSearchRequestDto {
    @NotBlank(message = "查询内容不能为空")
    private String query;

    @Min(value = 1, message = "topK最小为1")
    @Max(value = 50, message = "topK最大为50")
    private Integer topK = 10;
}
