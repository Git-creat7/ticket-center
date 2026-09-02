package asia.creat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单超时关单延时消息 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderCancelMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单 ID
     */
    private Long orderId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 票档 ID
     */
    private Long ticketId;

    /**
     * 消息创建时间戳 (毫秒)
     */
    private Long timestamp;
}
