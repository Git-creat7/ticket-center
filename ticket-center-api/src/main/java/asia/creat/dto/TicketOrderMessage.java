package asia.creat.dto;

import lombok.Data;

@Data
public class TicketOrderMessage {
    private Long id;
    private Long userId;
    private Long ticketId;
    private Boolean useCredits;
    private Long price;
}
