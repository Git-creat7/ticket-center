package asia.creat.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketReservationVO {
    private Long id;
    private Long ticketId;
    private String ticketTitle;
    private Long orderId;
    private Integer status;
    private String statusDesc;
    private String failureReason;
    private Boolean releasePending;
    private Integer orderStatus;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime paymentDeadline;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
