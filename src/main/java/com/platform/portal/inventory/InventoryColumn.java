package com.platform.portal.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory_column")
public class InventoryColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long pageId;
    @Column(name = "col_key")
    private String key;
    private String label;
    @Enumerated(EnumType.STRING)
    @Column(name = "col_type")
    private ColumnType type;
    private boolean required;
    /** Locked columns belong to system pages and cannot be deleted or retyped. */
    private boolean locked;
    private boolean visible = true;
    private Integer width;
    private int sortOrder;
    /** JSON: {"choices":[{"value":"prod","color":"red"}], "refPage":"servers"} */
    private String options;
    private boolean expiryTracking;
    private String description;

    public Long getId() { return id; }
    public Long getPageId() { return pageId; }
    public void setPageId(Long pageId) { this.pageId = pageId; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public ColumnType getType() { return type; }
    public void setType(ColumnType type) { this.type = type; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public boolean isExpiryTracking() { return expiryTracking; }
    public void setExpiryTracking(boolean expiryTracking) { this.expiryTracking = expiryTracking; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
