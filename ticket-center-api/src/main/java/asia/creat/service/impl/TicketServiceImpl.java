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

        // Redis 状态在事务提交后同步，避免回滚留下脏缓存。
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
