package asia.creat.mapper;

import asia.creat.entity.TicketOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TicketOrderMapper extends BaseMapper<TicketOrder> {

    int pay(@Param("id") Long id, @Param("userId") Long userId, @Param("timeoutSeconds") long timeoutSeconds);

    int cancelExpired(@Param("id") Long id, @Param("timeoutSeconds") long timeoutSeconds);

    List<TicketOrder> findExpired(@Param("timeoutSeconds") long timeoutSeconds, @Param("limit") int limit);
}
