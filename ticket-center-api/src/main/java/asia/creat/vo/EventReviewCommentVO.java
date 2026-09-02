package asia.creat.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EventReviewCommentVO {
    private Long id;
    private Long reviewId;
    private Long userId;
    private String userName;
    private String userIcon;
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
