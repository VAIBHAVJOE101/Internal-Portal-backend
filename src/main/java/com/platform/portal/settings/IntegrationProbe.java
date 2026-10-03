package com.platform.portal.settings;

import java.util.Map;

/** Implemented by each integration to back the "Test connection" button in Settings. */
public interface IntegrationProbe {

    SettingType type();

    /** Returns details on success, throws on failure. */
    Map<String, Object> probe(Map<String, String> settings);
}
