package com.eventticketing.common.security;

import com.eventticketing.common.exception.UnauthorizedException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Resolves the demo client's persisted bearer token to a stable booking owner. */
@Component
public class TokenAuthenticator {

    public CustomerId authenticate(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("Send an Authorization: Bearer header.");
        }
        String token = header.substring(7).trim();
        if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new UnauthorizedException("Send a valid bearer token.");
        }
        try {
            return new CustomerId(UUID.fromString(token));
        } catch (IllegalArgumentException notUuid) {
            // Preserve ownership for clients that already use a device token.
            return new CustomerId(UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
