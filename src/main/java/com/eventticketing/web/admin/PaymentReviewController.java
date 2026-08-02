package com.eventticketing.web.admin;

import com.eventticketing.reservation.service.PaymentReviewItem;
import com.eventticketing.reservation.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin: the payment dead letter.
 *
 * <p>Reconciliation parks a payment here when it has failed too many times. Every row means money
 * may have moved without being accounted for, so this list is meant to be watched — an empty list
 * is the only healthy state.
 */
@RestController
@RequestMapping("/api/payments/review")
public class PaymentReviewController {

    private final ReservationService reservationService;

    public PaymentReviewController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** Everything awaiting an operator, with the identifiers needed to search the provider. */
    @GetMapping
    public List<PaymentReviewItem> list() {
        return reservationService.listPaymentsNeedingReview();
    }

    /**
     * How many payments are parked. Cheap enough to poll, so it can back a dashboard tile or an
     * alert rule; anything above zero warrants a look.
     */
    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("needsReview", reservationService.countPaymentsNeedingReview());
    }

    /**
     * Returns a payment to the reconciliation queue once the underlying cause is fixed — expired
     * credentials, a provider outage, a data problem. Reconciliation then re-decides from the
     * gateway's own record rather than anyone guessing the outcome by hand.
     */
    @PostMapping("/{paymentId}/requeue")
    public ResponseEntity<Void> requeue(@PathVariable Long paymentId) {
        reservationService.requeueForReconciliation(paymentId);
        return ResponseEntity.noContent().build();
    }
}
