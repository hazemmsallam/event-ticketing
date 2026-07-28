package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Seat;

public record SeatResponse(
        Long id,
        String label,
        String rowLabel,
        int rowIndex,
        int seatNumber,
        Integer layoutX,
        Integer layoutY,
        Integer rotationDegrees,
        Integer layoutWidth,
        Integer layoutHeight,
        String sectionName,
        Long sectionId
) {
    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getLabel(), s.getRowLabel(), s.getRowIndex(),
                s.getSeatNumber(), s.getLayoutX(), s.getLayoutY(),
                s.getRotationDegrees(), s.getLayoutWidth(), s.getLayoutHeight(), s.getSectionName(),
                s.getSection() != null ? s.getSection().getId() : null);
    }
}
