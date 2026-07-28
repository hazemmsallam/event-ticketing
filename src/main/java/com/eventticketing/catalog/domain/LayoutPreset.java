package com.eventticketing.catalog.domain;

import com.eventticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A named, reusable arrangement of sections, seats and tables captured from an admin's selection
 * and stamped onto a hall later — the "save this block and use it again" template.
 *
 * <p>Deliberately hall-agnostic: the members live in the {@link #payload} JSON document rather than
 * as foreign keys, because a preset is a template of shapes and relative offsets, not a reference
 * to the rows it was copied from (those may later be moved or deleted without invalidating it).
 * Member coordinates are relative to the preset's own bounding box ({@link #width} x
 * {@link #height}), so applying one is a translate by the chosen drop point.
 *
 * <p>{@link #name} is unique — it is how an admin picks a preset from the list.
 */
@Entity
@Table(name = "layout_preset")
@Getter
@Setter
@NoArgsConstructor
public class LayoutPreset extends BaseEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** Bounding-box width of the captured block, in layout pixels. */
    @Column(name = "width", nullable = false)
    private Integer width;

    /** Bounding-box height of the captured block, in layout pixels. */
    @Column(name = "height", nullable = false)
    private Integer height;

    /** The members as JSON ({@code {"sections":[...],"seats":[...],"tables":[...]}}). */
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;
}
