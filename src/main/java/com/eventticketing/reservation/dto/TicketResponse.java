package com.eventticketing.reservation.dto;

import com.eventticketing.reservation.domain.Ticket;

/** One generated admission ticket. */
public record TicketResponse(
        Long id,
        String ticketNumber,
        Long sectionId,
        String sectionName,
        Long seatId,
        String seatLabel
) {
    public static TicketResponse from(Ticket t) {
        return new TicketResponse(t.getId(), t.getTicketNumber(), t.getSectionId(),
                t.getSectionName(), t.getSeatId(), t.getSeatLabel());
    }
}
