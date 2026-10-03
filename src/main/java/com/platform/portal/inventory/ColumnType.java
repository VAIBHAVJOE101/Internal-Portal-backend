package com.platform.portal.inventory;

public enum ColumnType {
    TEXT,
    LONGTEXT,
    NUMBER,
    DATE,
    DATETIME,
    BOOLEAN,
    SELECT,
    MULTISELECT,
    /** Free-form list of strings, e.g. broker addresses. */
    LIST,
    URL,
    IP,
    EMAIL,
    /** Id of a record on another inventory page (options.refPage). */
    REFERENCE
}
