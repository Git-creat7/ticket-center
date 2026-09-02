package asia.creat.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventReviewVO {
    private Long id;
    private Long eventId;
    private Long userId;
    private String userName;
    private String userIcon;
    private String title;
    private String images;
    private String content;
    private Integer liked;
    private Boolean isLike;
    private Integer comments;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
