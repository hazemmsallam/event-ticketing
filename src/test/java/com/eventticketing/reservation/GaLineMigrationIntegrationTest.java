package com.eventticketing.reservation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V15 moves the single GA line off {@code booking} into {@code booking_ga_line}. Databases that
 * already ran V14 hold real GA orders in the old columns; this proves they survive the move and
 * that hall and event capacity are recomputed as seats plus GA capacity.
 */
@Testcontainers(disabledWithoutDocker = true)
class GaLineMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @Test
    void existingGaOrdersMoveToLinesAndCapacityIsDerived() throws Exception {
        flyway("14").migrate();
        try (Connection db = connect(); Statement sql = db.createStatement()) {
            sql.execute("INSERT INTO organizer (id, created_at, updated_at, name) VALUES (1, NOW(6), NOW(6), 'Org')");
            // A mixed hall saved before V15: capacity counted its 2 seats only.
            sql.execute("""
                    INSERT INTO hall (id, created_at, updated_at, name, capacity, is_seated)
                    VALUES (1, NOW(6), NOW(6), 'Hall', 2, 1)""");
            sql.execute("""
                    INSERT INTO section (id, created_at, updated_at, hall_id, name, booking_mode, currency, capacity)
                    VALUES (10, NOW(6), NOW(6), 1, 'Seats', 'SEATED', 'JOD', NULL),
                           (20, NOW(6), NOW(6), 1, 'Standing', 'GENERAL_ADMISSION', 'JOD', 50)""");
            sql.execute("""
                    INSERT INTO seat (id, created_at, updated_at, hall_id, row_label, row_index, seat_number, label, section_id)
                    VALUES (1, NOW(6), NOW(6), 1, 'A', 1, 1, 'A1', 10),
                           (2, NOW(6), NOW(6), 1, 'A', 1, 2, 'A2', 10)""");
            sql.execute("""
                    INSERT INTO event (id, created_at, updated_at, name, start_at, end_at, status, organizer_id, hall_id, max_capacity)
                    VALUES (1, NOW(6), NOW(6), 'Show', NOW(6), NOW(6), 'PUBLISHED', 1, 1, 2)""");
            // One GA order (3 x 7.50) and one seat-only order.
            sql.execute("""
                    INSERT INTO booking (id, created_at, updated_at, event_id, customer_ref, status, quantity,
                                         total_amount, expires_at, section_id, general_admission_quantity,
                                         general_admission_unit_price, general_admission_section_name,
                                         general_admission_currency)
                    VALUES (1, NOW(6), NOW(6), 1, 'c1', 'CONFIRMED', 3, 22.50, NOW(6), 20, 3, 7.50, 'Standing', 'JOD'),
                           (2, NOW(6), NOW(6), 1, 'c2', 'CONFIRMED', 1, 20.00, NOW(6), NULL, 0, NULL, NULL, NULL)""");
        }

        flyway(null).migrate();

        try (Connection db = connect(); Statement sql = db.createStatement()) {
            ResultSet line = sql.executeQuery(
                    "SELECT booking_id, event_id, section_id, section_name, quantity, unit_price FROM booking_ga_line");
            assertThat(line.next()).isTrue();
            assertThat(line.getLong("booking_id")).isEqualTo(1);
            assertThat(line.getLong("event_id")).isEqualTo(1);
            assertThat(line.getLong("section_id")).isEqualTo(20);
            assertThat(line.getString("section_name")).isEqualTo("Standing");
            assertThat(line.getInt("quantity")).isEqualTo(3);
            assertThat(line.getBigDecimal("unit_price")).isEqualByComparingTo("7.50");
            assertThat(line.next()).as("the seat-only order has no GA line").isFalse();

            assertThat(scalar(sql, "SELECT capacity FROM hall WHERE id = 1")).isEqualTo(52);
            assertThat(scalar(sql, "SELECT max_capacity FROM event WHERE id = 1")).isEqualTo(52);
            assertThat(scalar(sql, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'booking'
                      AND column_name IN ('section_id', 'general_admission_quantity')""")).isZero();
            // V18: points is a native JSON column, and a missing boundary became an empty array.
            assertThat(scalar(sql, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'section'
                      AND column_name = 'points' AND data_type = 'json'""")).isEqualTo(1);
            assertThat(scalar(sql, "SELECT COUNT(*) FROM section WHERE JSON_LENGTH(points) = 0")).isEqualTo(2);
            // V17: no table carries a currency any more.
            assertThat(scalar(sql, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND column_name = 'currency'
                      AND table_name IN ('section', 'booking_seat', 'booking_ga_line')""")).isZero();
        }
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static long scalar(Statement sql, String query) throws Exception {
        try (ResultSet rs = sql.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
