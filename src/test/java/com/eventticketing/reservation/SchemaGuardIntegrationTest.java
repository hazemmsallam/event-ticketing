package com.eventticketing.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one structure the whole booking design depends on: the generated {@code active_lock}
 * column on {@code booking_seat} and its UNIQUE index, which is what actually prevents
 * double-booking (the service-layer checks only produce friendly errors).
 *
 * <p>This exists because the constraint was once silently lost: with {@code ddl-auto} set to
 * {@code update}/{@code create}, Hibernate recreates the table from the entities, and it cannot
 * see a generated column that is not an entity field — so the guard disappeared while every test
 * and code path still looked correct. This test fails loudly if that ever happens again.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SchemaGuardIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void bookingSeatHasTheGeneratedActiveLockColumn() {
        String generation = jdbc.queryForObject("""
                select generation_expression
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'booking_seat'
                  and column_name = 'active_lock'
                """, String.class);

        assertThat(generation)
                .as("booking_seat.active_lock must exist as a generated column")
                .isNotNull()
                .contains("HELD")
                .contains("BOOKED");
    }

    @Test
    void activeLockIsUnique() {
        Integer nonUnique = jdbc.queryForObject("""
                select non_unique
                from information_schema.statistics
                where table_schema = database()
                  and table_name = 'booking_seat'
                  and column_name = 'active_lock'
                """, Integer.class);

        assertThat(nonUnique)
                .as("active_lock must carry a UNIQUE index — it is the double-booking guard")
                .isZero();
    }

    /** The columns Flyway defines must survive; an ENUM here means Hibernate rebuilt the table. */
    @Test
    void bookingSeatStatusIsTheFlywayColumnNotAHibernateEnum() {
        String columnType = jdbc.queryForObject("""
                select column_type
                from information_schema.columns
                where table_schema = database()
                  and table_name = 'booking_seat'
                  and column_name = 'status'
                """, String.class);

        assertThat(columnType)
                .as("status should be the migration's varchar, not a Hibernate-generated enum")
                .startsWith("varchar");
    }
}
