package com.eventticketing.catalog.service;

import com.eventticketing.catalog.domain.Hall;
import com.eventticketing.catalog.domain.Seat;
import com.eventticketing.catalog.domain.SeatNumberingScheme;
import com.eventticketing.catalog.domain.SeatType;
import com.eventticketing.catalog.dto.CreateHallRequest;
import com.eventticketing.catalog.dto.HallResponse;
import com.eventticketing.catalog.dto.HallSummaryResponse;
import com.eventticketing.catalog.dto.RowTypeRange;
import com.eventticketing.catalog.dto.SeatResponse;
import com.eventticketing.catalog.dto.SeatEditItem;
import com.eventticketing.catalog.dto.SeatLayoutItem;
import com.eventticketing.catalog.dto.UpdateHallRequest;
import com.eventticketing.catalog.dto.UpdateHallSeatsRequest;
import com.eventticketing.catalog.dto.UpdateSeatLayoutRequest;
import com.eventticketing.catalog.repository.HallRepository;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import com.eventticketing.reservation.repository.BookingSeatRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HallService {

    private final HallRepository hallRepository;
    private final BookingSeatRepository bookingSeatRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public HallService(HallRepository hallRepository, BookingSeatRepository bookingSeatRepository) {
        this.hallRepository = hallRepository;
        this.bookingSeatRepository = bookingSeatRepository;
    }

    @Transactional
    public HallResponse create(CreateHallRequest request) {
        Hall hall = new Hall();
        hall.setName(request.name());
        hall.setAddress(request.address());
        hall.setSeated(request.seated());

        if (request.seated()) {
            populateSeatedHall(hall, request);
        } else {
            if (request.capacity() == null || request.capacity() < 1) {
                throw new BusinessRuleException("A non-seated hall requires a capacity of at least 1.");
            }
            hall.setCapacity(request.capacity());
        }

        Hall saved = hallRepository.save(hall);
        return toResponse(saved);
    }

    private void populateSeatedHall(Hall hall, CreateHallRequest request) {
        Integer rows = request.numRows();
        Integer cols = request.numColumns();
        if (rows == null || rows < 1 || cols == null || cols < 1) {
            throw new BusinessRuleException("A seated hall requires numRows and numColumns of at least 1.");
        }

        SeatNumberingScheme scheme = request.numberingScheme() != null
                ? request.numberingScheme()
                : SeatNumberingScheme.ALPHA_ROW_NUMERIC_SEAT;

        hall.setNumRows(rows);
        hall.setNumColumns(cols);
        hall.setNumberingScheme(scheme);
        hall.setCapacity(rows * cols);
        hall.setLayoutWidth(defaultLayoutWidth(cols));
        hall.setLayoutHeight(defaultLayoutHeight(rows));

        SeatType[] typeByRow = resolveRowTypes(rows, request.rowTypes());

        for (int r = 1; r <= rows; r++) {
            String rowLabel = RowLabels.of(r);
            SeatType type = typeByRow[r - 1];
            for (int c = 1; c <= cols; c++) {
                Seat seat = new Seat();
                seat.setRowLabel(rowLabel);
                seat.setRowIndex(r);
                seat.setSeatNumber(c);
                seat.setLabel(rowLabel + c);
                seat.setSeatType(type);
                applyDefaultLayout(seat, rows, cols, hall.getLayoutWidth());
                hall.addSeat(seat);
            }
        }
    }

    /** Builds a per-row seat-type array, defaulting uncovered rows to REGULAR. Later ranges win. */
    private SeatType[] resolveRowTypes(int rows, List<RowTypeRange> ranges) {
        SeatType[] typeByRow = new SeatType[rows];
        for (int i = 0; i < rows; i++) {
            typeByRow[i] = SeatType.REGULAR;
        }
        if (ranges == null) {
            return typeByRow;
        }
        for (RowTypeRange range : ranges) {
            if (range.fromRow() > range.toRow()) {
                throw new BusinessRuleException(
                        "Invalid row range: fromRow %d is greater than toRow %d.".formatted(range.fromRow(), range.toRow()));
            }
            if (range.fromRow() < 1 || range.toRow() > rows) {
                throw new BusinessRuleException(
                        "Row range %d-%d is outside the hall's rows 1-%d.".formatted(range.fromRow(), range.toRow(), rows));
            }
            for (int r = range.fromRow(); r <= range.toRow(); r++) {
                typeByRow[r - 1] = range.seatType();
            }
        }
        return typeByRow;
    }

    @Transactional
    public HallResponse update(Long id, UpdateHallRequest request) {
        Hall hall = getEntity(id);
        hall.setName(request.name());
        hall.setAddress(request.address());
        return toResponse(hall);
    }

    @Transactional
    public HallResponse updateLayout(Long id, UpdateSeatLayoutRequest request) {
        Hall hall = getEntity(id);
        if (!hall.isSeated()) {
            throw new BusinessRuleException("Only seated halls have a seat layout.");
        }

        hall.setLayoutWidth(request.layoutWidth());
        hall.setLayoutHeight(request.layoutHeight());

        Map<Long, Seat> seatsById = hall.getSeats().stream()
                .collect(Collectors.toMap(Seat::getId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (SeatLayoutItem item : request.seats()) {
            if (!seen.add(item.id())) {
                throw new BusinessRuleException("Duplicate seat layout item: " + item.id() + ".");
            }
            Seat seat = seatsById.get(item.id());
            if (seat == null) {
                throw ResourceNotFoundException.of("Seat", item.id());
            }
            validateSeatLayout(item, request.layoutWidth(), request.layoutHeight());
            seat.setLayoutX(item.layoutX());
            seat.setLayoutY(item.layoutY());
            seat.setRotationDegrees(item.rotationDegrees());
            seat.setLayoutWidth(item.layoutWidth());
            seat.setLayoutHeight(item.layoutHeight());
            seat.setSectionName(normalizeSectionName(item.sectionName()));
            if (item.seatType() != null) {
                seat.setSeatType(item.seatType());
            }
        }
        return toResponse(hall);
    }

    /**
     * Reconciles a seated hall against the full desired seat set: updates seats sent with an id,
     * creates those sent without one, and deletes existing seats left out of the request. Seats
     * that have bookings cannot be deleted. Recomputes rows/columns/capacity afterwards.
     */
    @Transactional
    public HallResponse updateSeats(Long id, UpdateHallSeatsRequest request) {
        Hall hall = getEntity(id);
        if (!hall.isSeated()) {
            throw new BusinessRuleException("Only seated halls have a seat layout.");
        }

        int canvasWidth = request.layoutWidth();
        int canvasHeight = request.layoutHeight();
        hall.setLayoutWidth(canvasWidth);
        hall.setLayoutHeight(canvasHeight);

        Map<Long, Seat> existingById = hall.getSeats().stream()
                .collect(Collectors.toMap(Seat::getId, Function.identity()));

        // Validate the request as a whole (unique labels, geometry) before mutating anything.
        Set<String> labels = new HashSet<>();
        for (SeatEditItem item : request.seats()) {
            String label = item.label().trim();
            if (!labels.add(label)) {
                throw new BusinessRuleException("Duplicate seat label: " + label + ".");
            }
            if (item.id() != null && !existingById.containsKey(item.id())) {
                throw ResourceNotFoundException.of("Seat", item.id());
            }
            validateSeatGeometry(item.layoutX(), item.layoutY(), item.layoutWidth(), item.layoutHeight(),
                    item.rotationDegrees(), canvasWidth, canvasHeight);
        }

        // Delete existing seats not referenced by the request (blocked if they have bookings).
        Set<Long> keptIds = request.seats().stream()
                .map(SeatEditItem::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<Long> toDelete = existingById.keySet().stream()
                .filter(existingId -> !keptIds.contains(existingId))
                .toList();
        if (!toDelete.isEmpty()) {
            List<Long> booked = bookingSeatRepository.findBookedSeatIds(toDelete);
            if (!booked.isEmpty()) {
                String labelsWithBookings = booked.stream()
                        .map(existingById::get)
                        .map(Seat::getLabel)
                        .sorted()
                        .collect(Collectors.joining(", "));
                throw new BusinessRuleException(
                        "Cannot delete seats that have bookings: " + labelsWithBookings + ".");
            }
            hall.getSeats().removeIf(seat -> toDelete.contains(seat.getId()));
            // Flush the deletes so freed labels can be reused by inserts below (unique index).
            entityManager.flush();
        }

        // Apply updates and creates.
        for (SeatEditItem item : request.seats()) {
            Seat seat;
            if (item.id() != null) {
                seat = existingById.get(item.id());
            } else {
                seat = new Seat();
                hall.addSeat(seat);
            }
            seat.setLabel(item.label().trim());
            seat.setRowLabel(item.rowLabel().trim());
            seat.setRowIndex(item.rowIndex());
            seat.setSeatNumber(item.seatNumber());
            seat.setSeatType(item.seatType());
            seat.setLayoutX(item.layoutX());
            seat.setLayoutY(item.layoutY());
            seat.setRotationDegrees(item.rotationDegrees());
            seat.setLayoutWidth(item.layoutWidth());
            seat.setLayoutHeight(item.layoutHeight());
            seat.setSectionName(normalizeSectionName(item.sectionName()));
        }

        recomputeGridStats(hall);
        // Flush so newly created seats receive their IDs before we map them into the response.
        entityManager.flush();
        return toResponse(hall);
    }

    private void recomputeGridStats(Hall hall) {
        int rows = 0;
        int cols = 0;
        for (Seat seat : hall.getSeats()) {
            rows = Math.max(rows, seat.getRowIndex());
            cols = Math.max(cols, seat.getSeatNumber());
        }
        hall.setNumRows(rows);
        hall.setNumColumns(cols);
        hall.setCapacity(hall.getSeats().size());
    }

    @Transactional
    public void delete(Long id) {
        hallRepository.delete(getEntity(id));
    }

    @Transactional(readOnly = true)
    public List<HallSummaryResponse> list() {
        return hallRepository.findAll().stream().map(HallSummaryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public HallResponse get(Long id) {
        return toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public Hall getEntity(Long id) {
        return hallRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Hall", id));
    }

    private HallResponse toResponse(Hall hall) {
        List<SeatResponse> seats = hall.getSeats().stream().map(SeatResponse::from).toList();
        return HallResponse.from(hall, seats);
    }

    private int defaultLayoutWidth(int cols) {
        return Math.max(820, cols * 64 + 360);
    }

    private int defaultLayoutHeight(int rows) {
        return Math.max(560, rows * 58 + 270);
    }

    private void applyDefaultLayout(Seat seat, int rows, int cols, int canvasWidth) {
        int seatWidth = 42;
        int seatHeight = 38;
        int gapX = 58;
        int gapY = 52;
        int rowWidth = (cols - 1) * gapX + seatWidth;
        int startX = Math.max(70, (canvasWidth - rowWidth) / 2);
        int startY = 150;
        double centerSeat = (cols + 1) / 2.0;
        int arcOffset = (int) Math.round(Math.pow(seat.getSeatNumber() - centerSeat, 2) * 1.35);
        int rowFan = (int) Math.round((seat.getRowIndex() - (rows + 1) / 2.0) * 3.0);

        seat.setLayoutX(startX + (seat.getSeatNumber() - 1) * gapX + rowFan);
        seat.setLayoutY(startY + (seat.getRowIndex() - 1) * gapY + arcOffset);
        seat.setRotationDegrees(rowFan / 2);
        seat.setLayoutWidth(seatWidth);
        seat.setLayoutHeight(seatHeight);
        seat.setSectionName(seat.getSeatType().name());
    }

    private void validateSeatLayout(SeatLayoutItem item, int canvasWidth, int canvasHeight) {
        validateSeatGeometry(item.layoutX(), item.layoutY(), item.layoutWidth(), item.layoutHeight(),
                item.rotationDegrees(), canvasWidth, canvasHeight);
    }

    private void validateSeatGeometry(int x, int y, int w, int h, int rot, int canvasWidth, int canvasHeight) {
        if (x < 0 || y < 0) {
            throw new BusinessRuleException("Seat layout coordinates cannot be negative.");
        }
        if (x > canvasWidth || y > canvasHeight) {
            throw new BusinessRuleException("Seat layout coordinates must fit inside the hall canvas.");
        }
        if (w < 12 || h < 12) {
            throw new BusinessRuleException("Seat layout dimensions are too small.");
        }
        if (w > 120 || h > 120) {
            throw new BusinessRuleException("Seat layout dimensions are too large.");
        }
        if (rot < -180 || rot > 180) {
            throw new BusinessRuleException("Seat rotation must be between -180 and 180 degrees.");
        }
    }

    private String normalizeSectionName(String sectionName) {
        if (sectionName == null || sectionName.isBlank()) {
            return null;
        }
        String normalized = sectionName.trim();
        if (normalized.length() > 80) {
            throw new BusinessRuleException("Section name must be 80 characters or fewer.");
        }
        return normalized;
    }
}
