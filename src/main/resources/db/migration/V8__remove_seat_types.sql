-- Pricing and booking are section-based. Preserve legacy prices by copying them to the
-- matching section before removing the obsolete seat-type columns.

INSERT INTO event_pricing (
    version, created_at, updated_at, event_id, section_id, price
)
SELECT
    0, MIN(ep.created_at), MAX(ep.updated_at), ep.event_id, sec.id, MIN(ep.price)
FROM event_pricing ep
JOIN event e ON e.id = ep.event_id
JOIN section sec ON sec.hall_id = e.hall_id
LEFT JOIN seat s ON s.section_id = sec.id
                    AND s.seat_type = ep.seat_type
WHERE ep.section_id IS NULL
  AND (
      (ep.seat_type IS NULL AND sec.booking_mode = 'GENERAL_ADMISSION')
      OR
      (ep.seat_type IS NOT NULL AND sec.booking_mode = 'SEATED' AND s.id IS NOT NULL)
  )
  AND NOT EXISTS (
      SELECT 1
      FROM event_pricing existing
      WHERE existing.event_id = ep.event_id
        AND existing.section_id = sec.id
  )
GROUP BY ep.event_id, sec.id
HAVING COUNT(DISTINCT ep.price) = 1;

-- A legacy price can be assigned safely when the hall has exactly one section.
INSERT INTO event_pricing (
    version, created_at, updated_at, event_id, section_id, price
)
SELECT
    0, MIN(ep.created_at), MAX(ep.updated_at), ep.event_id,
    only_section.section_id, MIN(ep.price)
FROM event_pricing ep
JOIN event e ON e.id = ep.event_id
JOIN (
    SELECT hall_id, MIN(id) AS section_id
    FROM section
    GROUP BY hall_id
    HAVING COUNT(*) = 1
) only_section ON only_section.hall_id = e.hall_id
WHERE ep.section_id IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM event_pricing existing
      WHERE existing.event_id = ep.event_id
        AND existing.section_id = only_section.section_id
  )
GROUP BY ep.event_id, only_section.section_id
HAVING COUNT(DISTINCT ep.price) = 1;

-- Refuse to guess when an edited hall has merged differently priced legacy areas into one
-- section. The administrator must resolve that pricing before this migration can be applied.
CREATE TEMPORARY TABLE v8_unmapped_pricing_guard (
    legacy_pricing_id BIGINT NOT NULL
);

INSERT INTO v8_unmapped_pricing_guard (legacy_pricing_id)
SELECT NULL
FROM event_pricing ep
WHERE ep.section_id IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM event e
      JOIN section sec ON sec.hall_id = e.hall_id
      JOIN event_pricing mapped ON mapped.event_id = ep.event_id
                               AND mapped.section_id = sec.id
      LEFT JOIN seat s ON s.section_id = sec.id
                       AND s.seat_type = ep.seat_type
      WHERE e.id = ep.event_id
        AND (
            (ep.seat_type IS NULL AND sec.booking_mode = 'GENERAL_ADMISSION')
            OR
            (ep.seat_type IS NOT NULL AND sec.booking_mode = 'SEATED' AND s.id IS NOT NULL)
        )
  )
LIMIT 1;

DROP TEMPORARY TABLE v8_unmapped_pricing_guard;

DELETE FROM event_pricing WHERE section_id IS NULL;

ALTER TABLE event_pricing MODIFY COLUMN section_id BIGINT NOT NULL;
ALTER TABLE booking_seat DROP COLUMN seat_type;
ALTER TABLE event_pricing DROP COLUMN seat_type;
ALTER TABLE seat DROP COLUMN seat_type;
