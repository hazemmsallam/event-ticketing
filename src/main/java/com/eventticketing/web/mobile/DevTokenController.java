package com.eventticketing.web.mobile;

import com.eventticketing.common.security.CurrentCustomer;
import com.eventticketing.common.security.CustomerId;
import com.eventticketing.common.security.TokenAuthenticator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Issues throwaway identities so the hold quota and rate limit can be exercised without a real
 * auth system.
 *
 * <p><strong>Must be disabled in production</strong> — it hands out valid credentials to anyone
 * who asks. Gated on {@code app.security.dev-tokens-enabled}; set it to {@code false} (or remove
 * this class) the moment real authentication lands.
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "app.security.dev-tokens-enabled", havingValue = "true")
public class DevTokenController {

    private final TokenAuthenticator authenticator;

    public DevTokenController(TokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    /**
     * Mints a fresh token representing a brand-new customer. Send it back as
     * {@code Authorization: Bearer <token>}. Reuse the same token to stay the same customer —
     * that is what lets you trip the one-hold quota and the 5/minute throttle on purpose.
     */
    @PostMapping("/token")
    public Map<String, String> newToken() {
        String token = authenticator.newTestToken();
        return Map.of(
                "token", token,
                "header", "Authorization: Bearer " + token,
                "customerId", authenticator.authenticate(token).ref());
    }

    /** Echoes back who the server thinks you are — useful when a quota rejection looks wrong. */
    @GetMapping("/whoami")
    public Map<String, String> whoami(@CurrentCustomer CustomerId customer) {
        return Map.of("customerId", customer.ref());
    }
}
