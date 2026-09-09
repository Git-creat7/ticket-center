package asia.creat.service.impl;

import asia.creat.client.CoreClient;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.EventSummaryDTO;
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

import static asia.creat.utils.RedisConstants.ticketStockKey;

@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket> implements TicketService {

    private final TicketStockMapper ticketStockMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final CoreClient coreClient;

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
        EventSummaryDTO event = coreClient.getEvent(createDTO.getEventId());
        if (event == null || !Integer.valueOf(1).equals(event.getStatus())) {
            throw new BusinessException("演出不存在或已下架");
        }
        Ticket ticket = BeanUtil.copyProperties(createDTO, Ticket.class);
        ticket.setEventName(event.getName());
        ticket.setStatus(1);
        save(ticket);

        TicketStock ticketStock = new TicketStock();
        ticketStock.setTicketId(ticket.getId());
        ticketStock.setStock(createDTO.getStock());
        ticketStock.setBeginTime(createDTO.getBeginTime());
        ticketStock.setEndTime(createDTO.getEndTime());
        ticketStockMapper.insert(ticketStock);

        Long ticketId = ticket.getId();
        String stock = createDTO.getStock().toString();

        // Redis 状态在事务提交后同步，避免回滚留下脏缓存。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stringRedisTemplate.opsForValue().set(ticketStockKey(ticketId), stock);
            }
        });

        return ticketId;
    }
}
