-- Supports the single-active-hold quota checked on every booking attempt:
--   customer_ref = ? AND status = 'PENDING_PAYMENT' AND expires_at > NOW()
--
-- This runs before any inventory work, so it sits on the hot path of the most abuse-prone
-- endpoint. The existing idx_booking_customer_created is ordered for history listing and cannot
-- serve this predicate.
CREATE INDEX idx_booking_customer_status_expiry
    ON booking (customer_ref, status, expires_at);
