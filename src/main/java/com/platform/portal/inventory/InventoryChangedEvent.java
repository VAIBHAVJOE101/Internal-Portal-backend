package com.platform.portal.inventory;

/**
 * Published after inventory data changes. Modules that consume system pages (e.g. Kafka reading
 * the Kafka Instances page) listen to this to drop cached connections.
 */
public record InventoryChangedEvent(String pageSlug, Long recordId, String action) {
}
