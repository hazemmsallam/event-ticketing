package com.eventticketing.catalog.dto;

import com.eventticketing.catalog.domain.LayoutObjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LayoutObjectJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void screenObjectTypeIsAcceptedByTheHallLayoutContract() throws Exception {
        String json = """
                {
                  "objectType": "SCREEN",
                  "shape": "RECTANGLE",
                  "label": "Main display",
                  "layoutX": 80,
                  "layoutY": 40,
                  "layoutZ": 60,
                  "rotationDegrees": 0,
                  "layoutWidth": 220,
                  "layoutDepth": 24,
                  "objectHeight": 130
                }
                """;

        LayoutObjectItem item = objectMapper.readValue(json, LayoutObjectItem.class);

        assertEquals(LayoutObjectType.SCREEN, item.objectType());
        assertEquals("Main display", item.label());
    }

    @Test
    void oldPresetObjectsWithoutATypeRemainBackwardCompatible() throws Exception {
        String json = """
                {
                  "shape": "RECTANGLE",
                  "label": "Head table",
                  "x": 10,
                  "y": 20,
                  "z": 0,
                  "rotationDegrees": 0,
                  "width": 120,
                  "depth": 60,
                  "objectHeight": 40
                }
                """;

        PresetTableItem item = objectMapper.readValue(json, PresetTableItem.class);

        assertNull(item.objectType());
        assertEquals("Head table", item.label());
    }
}
