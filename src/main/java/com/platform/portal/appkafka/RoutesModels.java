package com.platform.portal.appkafka;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class RoutesModels {

    private RoutesModels() {
    }

    /** One API Gateway operation and its Kafka mapping fields (field path -> value). */
    public record RouteRow(String id, String docId, int index, String api, String operationId, String method, String publicUrl,
                           String internalUrl, Map<String, String> mappings) {
    }

    public record RoutesConfig(String source, List<String> mappingFields, boolean configured) {
    }

    public enum Mode { SET, REPLACE, CLEAR }

    /**
     * Bulk change: for the selected rows, either SET the field to {@code value}, REPLACE {@code find}
     * with {@code replace} inside the current value, or CLEAR it.
     */
    public record BulkChangeRequest(@NotEmpty List<String> rowIds, @NotNull String field, @NotNull Mode mode, String value,
                                    String find, String replace) {
    }

    public record Change(String rowId, String operationId, String publicUrl, String field, String before, String after) {
    }

    public record ChangeResult(String rowId, String operationId, boolean success, String message) {
    }

    public record ApplyResult(int requested, int succeeded, int failed, List<ChangeResult> results) {
    }
}
