package asia.creat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EventQueryDTO extends PageQuery {

    @NotNull(message = "分类id不能为空")
    private Integer categoryId;

    private Double x;
    private Double y;

    @Min(value = 1, message = "查询半径不能小于1公里")
    @Max(value = 50, message = "查询半径不能超过50公里")
    private int radius = 5;
}
