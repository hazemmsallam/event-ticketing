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

    @Scheduled(fixedDelayString = "${app.reservation.sweep-interval}")
    public void sweep() {
        int released = reservationService.releaseExpired();
        if (released > 0) {
            log.info("Released {} expired booking hold(s).", released);
        }
    }
}
