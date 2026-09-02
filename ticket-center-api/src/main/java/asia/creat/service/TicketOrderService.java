package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import asia.creat.dto.TicketOrderMessage;
import asia.creat.entity.TicketOrder;
import asia.creat.vo.TicketOrderVO;
import com.baomidou.mybatisplus.extension.service.IService;

public interface TicketOrderService extends IService<TicketOrder> {

    /** 预约票档（支持选择积分抵扣） */
    Long reserveTicket(Long ticketId, Boolean useCredits);

    /** 创建订单（事务，由消费者调用） */
    void createTicketOrder(TicketOrderMessage message);

    /** 支付（模拟）：待支付 → 已出票 */
    void pay(Long orderId);

    /** 取消订单并释放库存与退还积分 */
    void cancel(Long orderId);

    /** 我的订单列表 */
    PageResult<TicketOrderVO> myOrders(PageQuery query, Integer status);

    /** 超时关单（延时队列消费者调用：状态机转为已取消并释放 MySQL 与 Redis 库存及退还积分） */
    void cancelTimeoutOrder(Long orderId);

    /** 定时扫描超时未支付订单并释放库存（作为延时队列的二次兜底保障） */
    void releaseTimeoutOrders();
}
