package com.eventticketing.catalog.service;

import com.eventticketing.catalog.domain.Hall;
import com.eventticketing.catalog.domain.LayoutObject;
import com.eventticketing.catalog.domain.LayoutObjectType;
import com.eventticketing.catalog.domain.Seat;
import com.eventticketing.catalog.domain.SeatNumberingScheme;
import com.eventticketing.catalog.domain.Section;
import com.eventticketing.catalog.domain.SectionBookingMode;
import com.eventticketing.catalog.domain.SectionShape;
import com.eventticketing.catalog.domain.TableShape;
import com.eventticketing.catalog.dto.CreateHallRequest;
import com.eventticketing.catalog.dto.HallResponse;
import com.eventticketing.catalog.dto.HallSummaryResponse;
import com.eventticketing.catalog.dto.LayoutObjectItem;
import com.eventticketing.catalog.dto.LayoutObjectResponse;
import com.eventticketing.catalog.dto.PointItem;
import com.eventticketing.catalog.dto.SeatResponse;
import com.eventticketing.catalog.dto.SectionItem;
import com.eventticketing.catalog.dto.SectionResponse;
import com.eventticketing.catalog.dto.ShapeBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventticketing.catalog.dto.SeatEditItem;
import com.eventticketing.catalog.dto.SeatLayoutItem;
import com.eventticketing.catalog.dto.UpdateHallRequest;
import com.eventticketing.catalog.dto.UpdateHallSeatsRequest;
import com.eventticketing.catalog.dto.UpdateSeatLayoutRequest;
import com.eventticketing.catalog.repository.EventPricingRepository;
import com.eventticketing.catalog.repository.HallRepository;
import com.eventticketing.common.exception.BusinessRuleException;
import com.eventticketing.common.exception.ResourceNotFoundException;
import com.eventticketing.reservation.repository.BookingRepository;
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
	private final BookingRepository bookingRepository;
	private final EventPricingRepository eventPricingRepository;
	private final ObjectMapper objectMapper;

	@PersistenceContext
	private EntityManager entityManager;

	public HallService(HallRepository hallRepository, BookingSeatRepository bookingSeatRepository,
			BookingRepository bookingRepository, EventPricingRepository eventPricingRepository,
			ObjectMapper objectMapper) {
		this.hallRepository = hallRepository;
		this.bookingSeatRepository = bookingSeatRepository;
		this.bookingRepository = bookingRepository;
		this.eventPricingRepository = eventPricingRepository;
		this.objectMapper = objectMapper;
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
			addDefaultSection(hall, SectionBookingMode.GENERAL_ADMISSION, request.capacity());
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

		SeatNumberingScheme scheme = request.numberingScheme() != null ? request.numberingScheme()
				: SeatNumberingScheme.ALPHA_ROW_NUMERIC_SEAT;

		hall.setNumRows(rows);
		hall.setNumColumns(cols);
		hall.setNumberingScheme(scheme);
		hall.setCapacity(rows * cols);
		hall.setLayoutWidth(defaultLayoutWidth(cols));
		hall.setLayoutHeight(defaultLayoutHeight(rows));
		Section defaultSection = addDefaultSection(hall, SectionBookingMode.SEATED, null);

		for (int r = 1; r <= rows; r++) {
			String rowLabel = RowLabels.of(r);
			for (int c = 1; c <= cols; c++) {
				Seat seat = new Seat();
				seat.setRowLabel(rowLabel);
				seat.setRowIndex(r);
				seat.setSeatNumber(c);
				seat.setLabel(rowLabel + c);
				seat.setSection(defaultSection);
				seat.setSectionName(defaultSection.getName());
				applyDefaultLayout(seat, rows, cols, hall.getLayoutWidth());
				hall.addSeat(seat);
			}
		}
	}

	private Section addDefaultSection(Hall hall, SectionBookingMode mode, Integer capacity) {
		Section section = new Section();
		section.setName(mode == SectionBookingMode.SEATED ? "Main Section" : "General Admission");
		section.setBookingMode(mode);
		section.setCurrency("JOD");
		section.setCapacity(mode == SectionBookingMode.GENERAL_ADMISSION ? capacity : null);
		section.setShapeKind(SectionShape.RECTANGLE);
		section.setColor("#64748B");
		if (mode == SectionBookingMode.SEATED) {
			double width = hall.getLayoutWidth();
			double height = hall.getLayoutHeight();
			section.setPoints(SectionGeometry.toJson(objectMapper, List.of(
					new PointItem(0, 0),
					new PointItem(width, 0),
					new PointItem(width, height),
					new PointItem(0, height))));
		} else {
			section.setPoints("[]");
		}
		hall.addSection(section);
		return section;
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
		}
		return toResponse(hall);
	}

	/**
	 * Reconciles a seated hall against the full desired seat set: updates seats
	 * sent with an id, creates those sent without one, and deletes existing seats
	 * left out of the request. Seats that have bookings cannot be deleted.
	 * Recomputes rows/columns/capacity afterwards.
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

		// Validate the request as a whole (unique labels, geometry) before mutating
		// anything.
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

		// Delete existing seats not referenced by the request (blocked if they have
		// bookings).
		Set<Long> keptIds = request.seats().stream().map(SeatEditItem::id).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		List<Long> toDelete = existingById.keySet().stream().filter(existingId -> !keptIds.contains(existingId))
				.toList();
		if (!toDelete.isEmpty()) {
			List<Long> booked = bookingSeatRepository.findBookedSeatIds(toDelete);
			if (!booked.isEmpty()) {
				String labelsWithBookings = booked.stream().map(existingById::get).map(Seat::getLabel).sorted()
						.collect(Collectors.joining(", "));
				throw new BusinessRuleException("Cannot delete seats that have bookings: " + labelsWithBookings + ".");
			}
			hall.getSeats().removeIf(seat -> toDelete.contains(seat.getId()));
			// Flush the deletes so freed labels can be reused by inserts below (unique
			// index).
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
			seat.setLayoutX(item.layoutX());
			seat.setLayoutY(item.layoutY());
			seat.setRotationDegrees(item.rotationDegrees());
			seat.setLayoutWidth(item.layoutWidth());
			seat.setLayoutHeight(item.layoutHeight());
			seat.setSectionName(normalizeSectionName(item.sectionName()));
		}

		recomputeGridStats(hall);

		// Reconcile non-bookable layout objects. null = leave them untouched;
		// a list
		// (possibly empty) is the full desired set. Objects never affect capacity or
		// bookings.
		if (request.layoutObjects() != null) {
			reconcileLayoutObjects(hall, request.layoutObjects(), canvasWidth, canvasHeight);
		}

		// Reconcile sections, then assign each seat to the SEATED section that visually
		// contains it.
		if (request.sections() != null) {
			reconcileSections(hall, request.sections());
			assignSeatsToSections(hall);
		}

		// Flush so newly created seats/objects/sections receive their IDs before
		// mapping to response.
		entityManager.flush();
		return toResponse(hall);
	}

	/**
	 * Reconciles the hall's sections against the full desired set. Deleting a
	 * section detaches its seats (they simply become unassigned) but never touches
	 * bookings — sections are catalog data.
	 */
	private void reconcileSections(Hall hall, List<SectionItem> items) {
		Map<Long, Section> existingById = hall.getSections().stream()
				.collect(Collectors.toMap(Section::getId, Function.identity()));

		for (SectionItem item : items) {
			if (item.id() != null && !existingById.containsKey(item.id())) {
				throw ResourceNotFoundException.of("Section", item.id());
			}
			if (item.bookingMode() == SectionBookingMode.GENERAL_ADMISSION
					&& (item.capacity() == null || item.capacity() < 1)) {
				throw new BusinessRuleException("A general-admission section requires a capacity of at least 1.");
			}
		}

		Set<Long> keptIds = items.stream().map(SectionItem::id).filter(Objects::nonNull).collect(Collectors.toSet());
		// Everything that references a section must be resolved before it can be deleted, or the
		// FKs (seat.section_id, event_pricing.section_id, booking.section_id) reject the delete.
		List<Long> removedIds = hall.getSections().stream().map(Section::getId).filter(id -> !keptIds.contains(id))
				.toList();
		if (!removedIds.isEmpty()) {
			// A section with general-admission bookings represents real sales: refuse to delete it,
			// mirroring how seats with bookings are protected.
			List<Long> soldIds = bookingRepository.findBookedSectionIds(removedIds);
			if (!soldIds.isEmpty()) {
				String names = hall.getSections().stream().filter(s -> soldIds.contains(s.getId()))
						.map(Section::getName).collect(Collectors.joining(", "));
				throw new BusinessRuleException("Cannot delete sections that have bookings: " + names + ".");
			}
			// Seats survive their section (they just become unassigned); price lines do not — a
			// price for a section that no longer exists is meaningless.
			for (Seat seat : hall.getSeats()) {
				if (seat.getSection() != null && removedIds.contains(seat.getSection().getId())) {
					seat.setSection(null);
				}
			}
			eventPricingRepository.deleteBySectionIdIn(removedIds);
			eventPricingRepository.flush();
		}
		hall.getSections().removeIf(s -> !keptIds.contains(s.getId()));

		for (SectionItem item : items) {
			Section section;
			if (item.id() != null) {
				section = existingById.get(item.id());
			} else {
				section = new Section();
				hall.addSection(section);
			}
			section.setName(item.name().trim());
			section.setBookingMode(item.bookingMode());
			section.setCurrency(item.currency() != null && !item.currency().isBlank() ? item.currency().trim() : null);
			section.setCapacity(item.bookingMode() == SectionBookingMode.GENERAL_ADMISSION ? item.capacity() : null);
			section.setShapeKind(item.shapeKind());
			section.setColor(item.color());
			section.setPoints(SectionGeometry.toJson(objectMapper, resolveSectionPoints(item)));
		}
	}

	/**
	 * Resolves a section's boundary points: explicit {@code points} always win;
	 * otherwise, when a {@code shapeKind} preset and a {@code shapeBox} are
	 * supplied, the polygon is generated from them (see {@link ShapePoints}). Falls
	 * back to an empty boundary when neither is given.
	 */
	private List<PointItem> resolveSectionPoints(SectionItem item) {
		if (item.points() != null && !item.points().isEmpty()) {
			return item.points();
		}
		ShapeBox box = item.shapeBox();
		if (item.shapeKind() != null && box != null) {
			double rotation = box.rotationDegrees() != null ? box.rotationDegrees() : 0.0;
			return ShapePoints.forShape(item.shapeKind(), box.x(), box.y(), box.width(), box.height(), rotation);
		}
		return List.of();
	}

	/**
	 * Assigns every seat to the SEATED section whose polygon contains the seat's
	 * centre. Seats not inside any seated section are left unassigned. GA sections
	 * never own seats.
	 */
	private void assignSeatsToSections(Hall hall) {
		List<Section> seated = hall.getSections().stream().filter(s -> s.getBookingMode() == SectionBookingMode.SEATED)
				.toList();
		// Parse each seated section's polygon once. Keyed positionally (new sections
		// have no id yet).
		List<List<PointItem>> polygons = seated.stream().map(s -> SectionGeometry.fromJson(objectMapper, s.getPoints()))
				.toList();

		for (Seat seat : hall.getSeats()) {
			Integer lx = seat.getLayoutX(), ly = seat.getLayoutY();
			Integer lw = seat.getLayoutWidth(), lh = seat.getLayoutHeight();
			if (lx == null || ly == null || lw == null || lh == null) {
				seat.setSection(null);
				continue;
			}
			double cx = lx + lw / 2.0;
			double cy = ly + lh / 2.0;
			Section match = null;
			double matchArea = Double.POSITIVE_INFINITY;
			for (int i = 0; i < seated.size(); i++) {
				if (SectionGeometry.contains(polygons.get(i), cx, cy)) {
					double area = SectionGeometry.area(polygons.get(i));
					if (area < matchArea) {
						match = seated.get(i);
						matchArea = area;
					}
				}
			}
			seat.setSection(match);
		}
	}

	/**
	 * Reconciles the hall's non-bookable layout objects against the full desired
	 * set: updates those sent with an id, creates those without one, and deletes
	 * existing objects left out. Unlike seats, objects are never bookable, so
	 * deletion is unconditional.
	 */
	private void reconcileLayoutObjects(Hall hall, List<LayoutObjectItem> items, int canvasWidth, int canvasHeight) {
		Map<Long, LayoutObject> existingById = hall.getLayoutObjects().stream()
				.collect(Collectors.toMap(LayoutObject::getId, Function.identity()));

		// Validate the whole request before mutating anything.
		for (LayoutObjectItem item : items) {
			if (item.id() != null && !existingById.containsKey(item.id())) {
				throw ResourceNotFoundException.of("LayoutObject", item.id());
			}
			validateLayoutObject(item, canvasWidth, canvasHeight);
		}

		Set<Long> keptIds = items.stream().map(LayoutObjectItem::id).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		hall.getLayoutObjects().removeIf(obj -> !keptIds.contains(obj.getId()));

		for (LayoutObjectItem item : items) {
			LayoutObject obj;
			if (item.id() != null) {
				obj = existingById.get(item.id());
			} else {
				obj = new LayoutObject();
				hall.addLayoutObject(obj);
			}
			LayoutObjectType objectType = item.objectType() != null
					? item.objectType()
					: LayoutObjectType.TABLE;
			obj.setObjectType(objectType);
			obj.setLabel(normalizeLabel(item.label()));
			obj.setLayoutX(item.layoutX());
			obj.setLayoutY(item.layoutY());
			obj.setLayoutZ(item.layoutZ());
			obj.setShape(objectType == LayoutObjectType.SCREEN ? TableShape.RECTANGLE : item.shape());
			obj.setRotationDegrees(item.rotationDegrees());
			obj.setLayoutWidth(item.layoutWidth());
			obj.setLayoutDepth(item.layoutDepth());
			obj.setObjectHeight(item.objectHeight());
		}
	}

	private void validateLayoutObject(LayoutObjectItem item, int canvasWidth, int canvasHeight) {
		LayoutObjectType type = item.objectType() != null ? item.objectType() : LayoutObjectType.TABLE;
		if (item.shape() == null) {
			throw new BusinessRuleException("A layout object requires a shape.");
		}
		if (type == LayoutObjectType.SCREEN && item.shape() != TableShape.RECTANGLE) {
			throw new BusinessRuleException("A screen must use a rectangular footprint.");
		}
		if (item.layoutX() < 0 || item.layoutY() < 0) {
			throw new BusinessRuleException("Layout object coordinates cannot be negative.");
		}
		if (item.layoutX() > canvasWidth || item.layoutY() > canvasHeight) {
			throw new BusinessRuleException("Layout object must fit inside the hall canvas.");
		}
		if (item.shape() == TableShape.CIRCLE && !item.layoutWidth().equals(item.layoutDepth())) {
			throw new BusinessRuleException("A circular table's width and diameter must match.");
		}
		if (item.shape() == TableShape.SQUARE && !item.layoutWidth().equals(item.layoutDepth())) {
			throw new BusinessRuleException("A square table's width and length must match.");
		}
	}

	private String normalizeLabel(String label) {
		if (label == null || label.isBlank()) {
			return null;
		}
		String normalized = label.trim();
		if (normalized.length() > 80) {
			throw new BusinessRuleException("Label must be 80 characters or fewer.");
		}
		return normalized;
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
		return hallRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.of("Hall", id));
	}

	private HallResponse toResponse(Hall hall) {
		List<SeatResponse> seats = hall.getSeats().stream().map(SeatResponse::from).toList();
		List<LayoutObjectResponse> layoutObjects = hall.getLayoutObjects().stream().map(LayoutObjectResponse::from)
				.toList();
		List<SectionResponse> sections = hall.getSections().stream()
				.map(s -> SectionResponse.from(s, SectionGeometry.fromJson(objectMapper, s.getPoints()))).toList();
		return HallResponse.from(hall, seats, layoutObjects, sections);
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
