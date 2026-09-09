package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_reservation_task")
public class ReservationTask {

    public static final int SEND_ORDER = 0;
    public static final int RELEASE = 1;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reservationId;
    private Integer type;
    private Integer status;
    private Integer attempts;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
