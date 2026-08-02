package com.eventticketing.reservation.service;

import com.eventticketing.common.scheduling.SchedulerLease;
import com.eventticketing.reservation.config.ReservationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically releases seats whose holds have expired without payment, so they become
 * bookable again. Booking-time lazy release covers the same event immediately; this is the
 * safety net for events nobody is actively booking.
 *
 * <p>Runs on one instance per tick, chosen by a Redis lease. Without it every replica would fetch
 * and update the same batch — the result is still correct, but the wasted writes contend on the
 * same rows.
 */
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);
    private static final String JOB = "hold-sweeper";

    private final ReservationService reservationService;
    private final SchedulerLease lease;
    private final ReservationProperties properties;

    public HoldSweeper(ReservationService reservationService, SchedulerLease lease,
                       ReservationProperties properties) {
        this.reservationService = reservationService;
        this.lease = lease;
        this.properties = properties;
    }

    /** Safety valve: stop after this many batches so one tick can never run unbounded. */
    private static final int MAX_BATCHES_PER_RUN = 20;

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval}")
    public void sweep() {
        // Lease slightly shorter than the interval, so the next tick is never locked out.
        if (!lease.acquire(JOB, properties.sweepInterval().minusSeconds(1))) {
            return;
        }
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int released = reservationService.releaseExpired();
            total += released;
            // A short batch means the backlog is drained; stop rather than spin.
            if (!reservationService.isFullSweepBatch(released)) {
                break;
            }
        }
        if (total > 0) {
            log.info("Released {} expired booking hold(s).", total);
        }
    }
}
