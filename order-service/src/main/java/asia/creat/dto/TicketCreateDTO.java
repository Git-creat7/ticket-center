package asia.creat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketCreateDTO {

    @NotNull(message = "演出id不能为空")
    private Long eventId;

    @NotBlank(message = "票档名称不能为空")
    @Size(max = 64, message = "票档名称不能超过64字符")
    private String title;

    @NotNull(message = "票价不能为空")
    @Min(value = 1, message = "票价必须大于0")
    private Long price;

    private Long originalPrice;

    private Integer type = 1;

    @NotNull(message = "库存不能为空")
    @Min(value = 1, message = "库存必须大于0")
    private Integer stock;

    @NotNull(message = "开售时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    @NotNull(message = "停售时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
