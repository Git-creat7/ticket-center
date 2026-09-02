package asia.creat.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
public class EventCreateDTO {

    @NotBlank(message = "演出名称不能为空")
    @Size(max = 128, message = "演出名称不能超过128字符")
    private String name;

    @NotNull(message = "分类id不能为空")
    private Long categoryId;

    @NotBlank(message = "场馆名称不能为空")
    @Size(max = 128, message = "场馆名称不能超过128字符")
    private String venue;

    @Size(max = 255, message = "详细地址不能超过255字符")
    private String address;

    private Double x;
    private Double y;

    @Size(max = 1024, message = "主图地址过长")
    private String mainImage;

    @Size(max = 2048, message = "图集地址过长")
    private String images;

    private String intro;

    @NotNull(message = "开演时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    private Integer durationMin = 120;
}
