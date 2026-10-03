package com.platform.portal.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record PageDto(Long id, String slug, String name, String description, String icon, String group, int sortOrder,
                          boolean system, List<ColumnDto> columns, long recordCount, Instant updatedAt) {
    }

    public record ColumnDto(Long id, String key, String label, ColumnType type, boolean required, boolean locked,
                            boolean visible, Integer width, int sortOrder, Map<String, Object> options,
                            boolean expiryTracking, String description) {
    }

    public record RecordDto(Long id, Map<String, Object> data, String createdBy, Instant createdAt, String updatedBy,
                            Instant updatedAt) {
    }

    public record PageRequest(@NotBlank @Size(max = 120) String name, @Size(max = 80) String slug,
                              @Size(max = 500) String description, @Size(max = 60) String icon,
                              @Size(max = 80) String group, List<ColumnRequest> columns) {
    }

    public record ColumnRequest(@Size(max = 80) String key, @NotBlank @Size(max = 120) String label, @NotNull ColumnType type,
                                Boolean required, Boolean visible, Integer width, Map<String, Object> options,
                                Boolean expiryTracking, @Size(max = 500) String description) {
    }

    public record RecordRequest(@NotNull Map<String, Object> data) {
    }

    public record BulkDeleteRequest(@NotNull List<Long> ids) {
    }

    public record ImportResult(int created, List<String> errors) {
    }

    public record ExpiringItem(String pageSlug, String pageName, Long recordId, String title, String columnKey,
                               String columnLabel, String expiresOn, long daysLeft) {
    }
}
