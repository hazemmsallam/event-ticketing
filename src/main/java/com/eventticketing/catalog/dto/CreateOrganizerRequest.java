package com.eventticketing.catalog.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateOrganizerRequest(
        @NotBlank String name,
        @Email String email,
        String phone
) {
}
