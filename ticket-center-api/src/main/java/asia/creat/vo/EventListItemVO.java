package asia.creat.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventListItemVO {
    private Long id;
    private String name;
    private Long categoryId;
    private String categoryName;
    private String venue;
    private String address;
    private String mainImage;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    private Integer durationMin;
    private Long hot;
    private Integer comments;
    private Double distance;
}
