-- Idempotency keys move from booking-scoped to attempt-scoped.
--
-- A payment provider treats an idempotency key as "replay the stored response". With a key of
-- 'booking-{id}' that never changed, a customer whose card was declined could never pay: the
-- retry replayed the original decline instead of charging their new card. The key now carries an
-- attempt number, which advances only once the previous attempt is terminal.
--
-- Existing rows are attempt 1 and are re-keyed to the new format so the two never collide.
ALTER TABLE payment ADD COLUMN attempt INT NOT NULL DEFAULT 1;

UPDATE payment
SET idempotency_key = CONCAT('booking-', booking_id, '-1')
WHERE idempotency_key = CONCAT('booking-', booking_id);
