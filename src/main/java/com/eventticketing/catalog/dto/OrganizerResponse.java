package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.Organizer;

public record OrganizerResponse(
        Long id,
        String name,
        String email,
        String phone
) {
    public static OrganizerResponse from(Organizer o) {
        return new OrganizerResponse(o.getId(), o.getName(), o.getEmail(), o.getPhone());
    }
}
