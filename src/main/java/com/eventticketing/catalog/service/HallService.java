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
import com.eventticketing.catalog.dto.UpdateHallRequest;
import com.eventticketing.catalog.repository.HallRepository;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HallService {

    private final HallRepository hallRepository;

    public HallService(HallRepository hallRepository) {
        this.hallRepository = hallRepository;
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
}
