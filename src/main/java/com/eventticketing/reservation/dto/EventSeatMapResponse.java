package com.eventticketing.reservation.dto;

import java.util.List;

/**
 * Live seat map for a seated event. The mobile client polls this to render which seats are
 * available, reserved or booked in near real time.
 */
public record EventSeatMapResponse(
        Long eventId,
        Long hallId,
        String hallName,
        boolean seated,
        Integer layoutWidth,
        Integer layoutHeight,
        int totalSeats,
        long availableCount,
        long reservedCount,
        long bookedCount,
        List<SeatAvailabilityResponse> seats
) {
}
