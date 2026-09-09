package asia.creat.mapper;

import asia.creat.entity.TicketWaitlist;
import asia.creat.vo.TicketWaitlistVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TicketWaitlistMapper extends BaseMapper<TicketWaitlist> {

    TicketWaitlist selectHead(@Param("ticketId") Long ticketId);

    TicketWaitlist selectUserActive(@Param("userId") Long userId, @Param("ticketId") Long ticketId);

    List<Long> selectPendingTicketIds();

    Page<TicketWaitlistVO> selectMine(Page<TicketWaitlistVO> page, @Param("userId") Long userId,
                                    @Param("timeoutSeconds") long timeoutSeconds);

    TicketWaitlistVO selectView(@Param("id") Long id, @Param("userId") Long userId,
                               @Param("timeoutSeconds") long timeoutSeconds);
}
