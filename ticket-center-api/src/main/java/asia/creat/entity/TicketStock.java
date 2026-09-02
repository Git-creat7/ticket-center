package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket_stock")
public class TicketStock implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "ticket_id", type = IdType.INPUT)
    private Long ticketId;

    private Integer stock;

    private LocalDateTime createTime;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    private LocalDateTime updateTime;
}
