-- Separate the GA line from the combined order quantity and total.
ALTER TABLE booking
    ADD COLUMN general_admission_quantity INT NOT NULL DEFAULT 0,
    ADD COLUMN general_admission_unit_price DECIMAL(12, 2) NULL,
    ADD COLUMN general_admission_section_name VARCHAR(120) NULL,
    ADD COLUMN general_admission_currency VARCHAR(8) NULL;

-- Old orders were exclusively seated or GA. Released seat rows still identify seated orders.
UPDATE booking b
LEFT JOIN (SELECT DISTINCT booking_id FROM booking_seat) seated ON seated.booking_id = b.id
SET b.general_admission_quantity = b.quantity,
    b.general_admission_unit_price = b.total_amount / b.quantity
WHERE seated.booking_id IS NULL AND b.quantity > 0;

-- Resolve legacy quantity-only orders only when their hall has exactly one GA section.
-- Ambiguous historical orders remain unassigned; capacity queries conservatively count them
-- against each GA section of their own event until an operator can allocate them.
UPDATE booking b
JOIN event e ON e.id = b.event_id
JOIN (
    SELECT hall_id, MIN(id) AS section_id
    FROM section WHERE booking_mode = 'GENERAL_ADMISSION'
    GROUP BY hall_id HAVING COUNT(*) = 1
) ga ON ga.hall_id = e.hall_id
SET b.section_id = ga.section_id
WHERE b.general_admission_quantity > 0 AND b.section_id IS NULL;

UPDATE booking b
JOIN section s ON s.id = b.section_id
SET b.general_admission_section_name = s.name,
    b.general_admission_currency = s.currency
WHERE b.general_admission_quantity > 0;

CREATE INDEX idx_booking_event_section_status_expiry
    ON booking (event_id, section_id, status, expires_at);

ALTER TABLE booking ADD CONSTRAINT chk_booking_ga_quantity
    CHECK (general_admission_quantity >= 0 AND general_admission_quantity <= quantity);
