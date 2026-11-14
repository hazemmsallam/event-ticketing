ALTER TABLE section ADD COLUMN service_fee_per_ticket DECIMAL(12,2) NULL;
ALTER TABLE booking ADD COLUMN service_fee DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE booking_seat ADD COLUMN service_fee_per_ticket DECIMAL(12,2) NOT NULL DEFAULT 0;
ALTER TABLE booking_ga_line ADD COLUMN service_fee_per_ticket DECIMAL(12,2) NOT NULL DEFAULT 0;
