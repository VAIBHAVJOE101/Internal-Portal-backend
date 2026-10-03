package com.platform.portal.settings;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationSettingRepository extends JpaRepository<IntegrationSetting, Long> {

    Optional<IntegrationSetting> findBySettingKey(String settingKey);

    List<IntegrationSetting> findBySettingTypeOrderBySettingKey(SettingType type);
}
