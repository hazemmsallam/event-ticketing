package com.eventticketing.reservation.dto;

import com.eventticketing.reservation.domain.BookingGaLine;

import java.math.BigDecimal;

/** One general-admission line, priced at reservation time. */
public record GeneralAdmissionResponse(
        Long sectionId, String sectionName, int quantity,
        BigDecimal unitPrice, BigDecimal totalAmount, String currency) {

    public static GeneralAdmissionResponse from(BookingGaLine line) {
        return new GeneralAdmissionResponse(
                line.getSection() == null ? null : line.getSection().getId(),
                line.getSectionName(), line.getQuantity(),
                line.getUnitPrice(), line.lineTotal(), line.getCurrency());
    }
}
