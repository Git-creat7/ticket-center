package asia.creat.mapper;

import asia.creat.entity.TicketStock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface TicketStockMapper extends BaseMapper<TicketStock> {
    TicketStock selectForUpdate(@Param("ticketId") Long ticketId);
}
