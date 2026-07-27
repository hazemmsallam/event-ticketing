package com.eventticketing.reservation.dto;

import com.eventticketing.catalog.dto.LayoutObjectResponse;

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
        List<SeatAvailabilityResponse> seats,
        /**
         * Non-bookable layout objects (tables, …) so the customer 3D view can render the physical
         * layout. These carry geometry only — no availability or price — and cannot be selected.
         */
        List<LayoutObjectResponse> layoutObjects,
        /**
         * The hall's sections with live availability and geometry. Seated sections contain the
         * seats above; general-admission sections are selected as a whole with a quantity.
         */
        List<SectionAvailabilityResponse> sections
) {
}
