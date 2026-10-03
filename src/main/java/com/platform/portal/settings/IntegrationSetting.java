package com.platform.portal.settings;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "integration_setting")
public class IntegrationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String settingKey;
    @Enumerated(EnumType.STRING)
    private SettingType settingType;
    /** Non-secret configuration as JSON. */
    private String config;
    /** AES-GCM encrypted JSON map of secret values. */
    private String secrets;
    private String updatedBy;
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getSettingKey() { return settingKey; }
    public void setSettingKey(String settingKey) { this.settingKey = settingKey; }
    public SettingType getSettingType() { return settingType; }
    public void setSettingType(SettingType settingType) { this.settingType = settingType; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public String getSecrets() { return secrets; }
    public void setSecrets(String secrets) { this.secrets = secrets; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
