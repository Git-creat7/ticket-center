package asia.creat.service.impl;

import asia.creat.dto.TicketCreateDTO;
import asia.creat.entity.Ticket;
import asia.creat.entity.TicketStock;
import asia.creat.mapper.TicketMapper;
import asia.creat.mapper.TicketStockMapper;
import asia.creat.service.TicketService;
import asia.creat.vo.TicketVO;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static asia.creat.utils.RedisConstants.CACHE_EVENT_DETAIL_KEY;
import static asia.creat.utils.RedisConstants.ticketStockKey;

@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket> implements TicketService {

    private final TicketStockMapper ticketStockMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public List<TicketVO> queryTicketOfEvent(Long eventId) {
        List<Ticket> tickets = getBaseMapper().queryTicketOfEvent(eventId);
        return tickets.stream()
                .map(ticket -> BeanUtil.copyProperties(ticket, TicketVO.class))
                .toList();
    }

    @Override
    @Transactional
    public Long addTicket(TicketCreateDTO createDTO) {
        Ticket ticket = BeanUtil.copyProperties(createDTO, Ticket.class);
        ticket.setStatus(1);
        save(ticket);

        TicketStock ticketStock = new TicketStock();
        ticketStock.setTicketId(ticket.getId());
        ticketStock.setStock(createDTO.getStock());
        ticketStock.setBeginTime(createDTO.getBeginTime());
        ticketStock.setEndTime(createDTO.getEndTime());
        ticketStockMapper.insert(ticketStock);

        Long ticketId = ticket.getId();
        Long eventId = createDTO.getEventId();
        String stock = createDTO.getStock().toString();

        // 两个 Redis 操作都必须等事务提交后再做：
        // 库存 key 若在事务内写入，事务回滚后 key 仍留在 Redis，
        // 成为一个数据库里并不存在的票档的幽灵库存，可以被预约脚本扣减；
        // 详情缓存若在事务内删除，删除到提交之间的并发读会把旧值重新写回，
        // 反而留下一份能存活整个 TTL 的陈旧数据。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stringRedisTemplate.opsForValue().set(ticketStockKey(ticketId), stock);
                if (eventId != null) {
                    stringRedisTemplate.delete(CACHE_EVENT_DETAIL_KEY + eventId);
                }
            }
        });

        return ticketId;
    }
}
