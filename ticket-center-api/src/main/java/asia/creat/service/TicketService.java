package asia.creat.service;

import asia.creat.dto.TicketCreateDTO;
import asia.creat.entity.Ticket;
import asia.creat.vo.TicketVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface TicketService extends IService<Ticket> {

    List<TicketVO> queryTicketOfEvent(Long eventId);

    /** 新增票档并初始化 Redis 库存（开票） */
    Long addTicket(TicketCreateDTO createDTO);
}
