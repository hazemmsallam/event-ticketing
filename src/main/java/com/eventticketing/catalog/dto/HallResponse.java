package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Hall;
import com.eventticketing.catalog.domain.SeatNumberingScheme;

import java.util.List;

public record HallResponse(
        Long id,
        String name,
        String address,
        int capacity,
        boolean seated,
        Integer numRows,
        Integer numColumns,
        SeatNumberingScheme numberingScheme,
        Integer layoutWidth,
        Integer layoutHeight,
        List<SeatResponse> seats,
        List<LayoutObjectResponse> layoutObjects
) {
    public static HallResponse from(Hall h, List<SeatResponse> seats,
                                    List<LayoutObjectResponse> layoutObjects) {
        return new HallResponse(h.getId(), h.getName(), h.getAddress(), h.getCapacity(),
                h.isSeated(), h.getNumRows(), h.getNumColumns(), h.getNumberingScheme(),
                h.getLayoutWidth(), h.getLayoutHeight(), seats, layoutObjects);
    }
}
