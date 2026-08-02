package com.eventticketing.common.security;

import com.eventticketing.common.exception.UnauthorizedException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turns an {@code Authorization} header into a stable {@link CustomerId}.
 *
 * <p><strong>This is a stand-in for real authentication.</strong> It performs no signature check
 * and no expiry check, so any caller who invents a token becomes a valid user. It exists so the
 * quota and rate-limit controls can be built and tested against a real per-user identity now,
 * and so the rest of the system never again reads an identity out of a request body.
 *
 * <p>The mapping is deliberately <em>deterministic</em>: the same token always yields the same
 * UUID. That is what makes the controls testable — hammer the endpoint with one token and you
 * should see 429s; switch tokens and you are a different user. A random UUID per request would
 * silently defeat every limit it is meant to prove.
 *
 * <p>Replacing this with real auth means one change: resolve the UUID from a verified JWT subject
 * (or session) instead of hashing the raw token. Nothing downstream needs to know.
 */
@Component
public class TokenAuthenticator {

    private static final String BEARER = "Bearer ";

    /**
     * @param authorizationHeader raw header value, with or without a {@code Bearer } prefix
     * @return the caller's stable id
     * @throws UnauthorizedException when the header is absent or blank
     */
    public CustomerId authenticate(String authorizationHeader) {
        String token = strip(authorizationHeader);
        if (token.isEmpty()) {
            throw new UnauthorizedException(
                    "Missing Authorization header. Send 'Authorization: Bearer <token>'.");
        }
        return new CustomerId(toStableUuid(token));
    }

    /**
     * A token that is already a UUID is honoured as-is, so a client can keep an identity across
     * restarts; anything else is hashed into the UUID space. Both paths are stable for a given
     * input, which is the only property the quota and rate limiter depend on.
     */
    private UUID toStableUuid(String token) {
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String strip(String header) {
        if (header == null) {
            return "";
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            value = value.substring(BEARER.length()).trim();
        }
        return value;
    }

    /** Mints a fresh token for testing — see {@code DevTokenController}. */
    public String newTestToken() {
        return UUID.randomUUID().toString();
    }
}
