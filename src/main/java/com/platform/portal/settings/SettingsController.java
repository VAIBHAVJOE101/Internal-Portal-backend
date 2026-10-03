package com.platform.portal.settings;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public List<SettingsService.SettingView> list() {
        return settingsService.list();
    }

    @PutMapping("/{type}/{key}")
    public SettingsService.SettingView save(@PathVariable SettingType type, @PathVariable String key,
                                           @RequestBody Map<String, String> values) {
        return settingsService.save(key, type, values);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        settingsService.delete(key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{type}/test")
    public Map<String, Object> test(@PathVariable SettingType type) {
        return settingsService.test(type);
    }
}
