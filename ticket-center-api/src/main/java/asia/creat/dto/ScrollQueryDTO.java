package asia.creat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScrollQueryDTO {

    @NotNull(message = "时间戳游标不能为空")
    private Long max;

    @Min(value = 0, message = "偏移量不能小于0")
    private Integer offset = 0;

    @Min(value = 1, message = "单页数量不能小于1")
    @Max(value = 50, message = "单页数量不能超过50")
    private Integer size = 5;
}
