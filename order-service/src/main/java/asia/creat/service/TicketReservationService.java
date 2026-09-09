package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import asia.creat.entity.TicketReservation;
import asia.creat.vo.TicketReservationVO;

public interface TicketReservationService {
    Long reserveTicket(Long ticketId, Boolean useCredits, String requestId);

    Long reserveStock(TicketReservation reservation);

    TicketReservationVO getReservation(Long id);

    PageResult<TicketReservationVO> myReservations(PageQuery query);

    void failReservation(Long id, String reason);

    void releaseReservation(Long id);
}
