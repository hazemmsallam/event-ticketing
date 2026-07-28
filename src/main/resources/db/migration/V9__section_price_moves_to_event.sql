-- Prices belong to an event, never to the venue: the same section is worth different amounts at
-- different events. `section.default_price` was a hall-level fallback; it is removed so
-- `event_pricing` (one line per event + section) is the single source of truth for price.
--
-- Any section price that is not already covered by an explicit event price line is carried over
-- first, so no event silently loses its price. Publishing an event now requires a price line for
-- every section (EventService), which this backfill satisfies for existing data.
INSERT INTO event_pricing (version, created_at, updated_at, event_id, section_id, price)
SELECT 0, NOW(6), NOW(6), e.id, sec.id, sec.default_price
FROM event e
JOIN section sec ON sec.hall_id = e.hall_id
WHERE sec.default_price IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM event_pricing ep
      WHERE ep.event_id = e.id
        AND ep.section_id = sec.id
  );

ALTER TABLE section DROP COLUMN default_price;
