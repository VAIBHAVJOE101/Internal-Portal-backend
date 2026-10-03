package com.platform.portal.settings;

/** Published after a setting is saved or deleted so cached clients can be rebuilt. */
public record SettingsChangedEvent(SettingType type, String key) {
}
