package asia.creat.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketWaitlistVO {
    private Long id;
    private Long ticketId;
    private String ticketTitle;
    private Integer status;
    private String statusDesc;
    private Long position;
    private Long price;
    private Boolean useCredits;
    private String failureReason;
    private Long reservationId;
    private Long orderId;
    private Integer orderStatus;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paymentDeadline;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
