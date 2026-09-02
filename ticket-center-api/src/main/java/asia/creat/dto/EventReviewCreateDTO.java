package asia.creat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventReviewCreateDTO {

    @NotNull(message = "演出id不能为空")
    private Long eventId;

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题不能超过255字符")
    private String title;

    @NotBlank(message = "内容不能为空")
    @Size(max = 2048, message = "内容不能超过2048字符")
    private String content;

    @Size(max = 2048, message = "图片地址过长")
    private String images = "";
}
