-- Dead letter for reconciliation.
--
-- When reconcile() throws, its transaction rolls back and `updated_at` is never touched, so the
-- row stays eligible and is retried on every tick — forever. A permanently broken payment then
-- consumes gateway calls indefinitely and delays the healthy in-doubt payments queued behind it,
-- while nobody is told that real money is unresolved.
--
-- These columns let the job back off between attempts and, after enough failures, park the row in
-- the terminal NEEDS_REVIEW state so it leaves the queue and surfaces to an operator.
ALTER TABLE payment
    ADD COLUMN reconcile_attempts   INT NOT NULL DEFAULT 0,
    ADD COLUMN last_reconcile_at    DATETIME(6) NULL,
    ADD COLUMN last_reconcile_error VARCHAR(500) NULL;

-- The reconciliation queue now also filters on last_reconcile_at for exponential backoff.
CREATE INDEX idx_payment_reconcile_queue
    ON payment (status, updated_at, last_reconcile_at);
