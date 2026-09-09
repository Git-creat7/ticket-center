package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_ticket_reservation")
public class TicketReservation {

    public static final int PROCESSING = 0;
    public static final int SUCCESS = 1;
    public static final int FAILED = 2;
    public static final int CANCELLED = 3;

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long ticketId;
    private Long orderId;
    private String requestId;
    private Boolean useCredits;
    private Long price;
    private Integer status;
    private String failureReason;
    private Boolean releasePending;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
