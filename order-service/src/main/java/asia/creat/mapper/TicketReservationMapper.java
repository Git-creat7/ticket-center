package asia.creat.mapper;

import asia.creat.entity.TicketReservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TicketReservationMapper extends BaseMapper<TicketReservation> {

    TicketReservation selectForUpdate(@Param("id") Long id);

    TicketReservation selectByOrderIdForUpdate(@Param("orderId") Long orderId);

    List<TicketReservation> selectActiveOrders(@Param("ticketId") Long ticketId);
}
