-- Composite indexes for the booking hot paths. Each one matches a specific query's full
-- predicate, so the optimiser can satisfy it from the index instead of filtering rows.
--
-- Note what is deliberately NOT here: booking(event_id) and booking(status, expires_at) already
-- exist from V2, and booking(section_id) is covered by the index InnoDB creates for its foreign
-- key. These add the *composite* forms the aggregates actually filter on.

-- ReservationService.sumConfirmedQuantity / sumReservedQuantity and releaseExpiredForEvent:
-- filter by event, then status, then expiry.
CREATE INDEX idx_booking_event_status_expiry
    ON booking (event_id, status, expires_at);

-- The per-section general-admission capacity aggregates
-- (sumConfirmedQuantityBySection / sumReservedQuantityBySection).
CREATE INDEX idx_booking_section_status_expiry
    ON booking (section_id, status, expires_at);

-- Booking history: findByCustomerRefOrderByCreatedAtDescIdDesc. The DESC key parts let MySQL 8
-- read the index backwards-free and skip a filesort.
CREATE INDEX idx_booking_customer_created
    ON booking (customer_ref, created_at DESC, id DESC);

-- PaymentReconciliationJob.findPaymentsToReconcile: status = INITIATED AND updated_at < threshold.
-- Supersedes the single-column idx_payment_status from V3.
CREATE INDEX idx_payment_status_updated
    ON payment (status, updated_at);
DROP INDEX idx_payment_status ON payment;
