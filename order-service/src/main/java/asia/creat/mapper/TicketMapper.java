package asia.creat.mapper;

import asia.creat.entity.Ticket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

public interface TicketMapper extends BaseMapper<Ticket> {

    List<Ticket> queryTicketOfEvent(Long eventId);
}
