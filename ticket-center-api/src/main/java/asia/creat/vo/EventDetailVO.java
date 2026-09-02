package asia.creat.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventDetailVO {
    private Long id;
    private String name;
    private Long categoryId;
    private String categoryName;
    private String venue;
    private String address;
    private Double x;
    private Double y;
    private String mainImage;
    private String images;
    private String intro;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    private Integer durationMin;
    private Long hot;
    private Integer comments;
    private Integer status;
    private List<TicketVO> tickets;
}
