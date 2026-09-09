package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_ticket_waitlist")
public class TicketWaitlist {

    public static final int WAITING = 0;
    public static final int ALLOCATING = 1;
    public static final int ALLOCATED = 2;
    public static final int CANCELLED = 3;
    public static final int EXPIRED = 4;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long ticketId;
    private String requestId;
    private Boolean useCredits;
    private Long price;
    private Integer status;
    private Long reservationId;
    private String failureReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
