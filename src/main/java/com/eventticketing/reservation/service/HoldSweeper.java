package com.eventticketing.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically releases seats whose holds have expired without payment, so they become
 * bookable again. Booking-time lazy release covers the same event immediately; this is the
 * safety net for events nobody is actively booking.
 */
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    private final ReservationService reservationService;

    public HoldSweeper(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** Safety valve: stop after this many batches so one tick can never run unbounded. */
    private static final int MAX_BATCHES_PER_RUN = 20;

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval}")
    public void sweep() {
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
