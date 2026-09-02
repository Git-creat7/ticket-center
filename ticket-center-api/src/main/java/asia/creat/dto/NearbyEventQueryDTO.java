package asia.creat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 查询全部分类附近活动的参数，坐标为经度 x、纬度 y。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NearbyEventQueryDTO extends PageQuery {

    @NotNull(message = "经度不能为空")
    private Double x;

    @NotNull(message = "纬度不能为空")
    private Double y;

    @Min(value = 1, message = "查询半径不能小于1公里")
    @Max(value = 50, message = "查询半径不能超过50公里")
    private int radius = 5;
}
