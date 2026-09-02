package asia.creat.dto;

import lombok.Data;

@Data
public class TicketOrderMessage {
    private Long id;
    private Long userId;
    private Long ticketId;
    private Boolean useCredits;

    /**
     * 预约那一刻的票价快照（分）。
     * 成交价必须在预约时定下：消费端重读 tb_ticket.price 的话，
     * 预约与落库之间（MQ 投递、重试、死信重入）的任何调价都会改变用户实付金额。
     * 与 tb_ticket_order.used_credits 是同一类问题，见 PROGRESS.md 优化记录 2。
     * <p>
     * 允许为 null：滚动发布期间队列里可能残留旧格式消息，消费端对 null 回退到当前票价。
     */
    private Long price;
}
