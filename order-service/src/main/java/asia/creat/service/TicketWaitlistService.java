package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import asia.creat.vo.TicketWaitlistVO;

public interface TicketWaitlistService {
    Long join(Long ticketId, Boolean useCredits, String requestId);

    void cancel(Long id);

    TicketWaitlistVO getWaitlist(Long id);

    PageResult<TicketWaitlistVO> myWaitlists(PageQuery query);

    void processWaitlists();
}
