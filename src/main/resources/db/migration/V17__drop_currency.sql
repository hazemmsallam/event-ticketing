-- Prices are plain amounts in the one currency the platform sells in, so no table carries a
-- currency any more. Prices and booking totals are unchanged.
ALTER TABLE section DROP COLUMN currency;
ALTER TABLE booking_seat DROP COLUMN currency;
ALTER TABLE booking_ga_line DROP COLUMN currency;
