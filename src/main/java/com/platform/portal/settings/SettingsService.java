package com.platform.portal.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.platform.portal.audit.AuditService;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.CurrentUser;
import com.platform.portal.common.Json;
import com.platform.portal.common.SecretCipher;
import com.platform.portal.common.Strings;
import com.platform.portal.config.PortalProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Secure store for integration credentials and configuration.
 * Resolution order: values saved in the UI, then env/ConfigMap defaults from {@code portal.integrations}.
 */
@Service
public class SettingsService {

    public static final String MASK = "********";

    private final IntegrationSettingRepository repository;
    private final SecretCipher cipher;
    private final Json json;
    private final PortalProperties properties;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<IntegrationProbe> probeProvider;

    public SettingsService(IntegrationSettingRepository repository, SecretCipher cipher, Json json, PortalProperties properties,
                           AuditService audit, ApplicationEventPublisher events, ObjectProvider<IntegrationProbe> probeProvider) {
        this.repository = repository;
        this.cipher = cipher;
        this.json = json;
        this.properties = properties;
        this.audit = audit;
        this.events = events;
        this.probeProvider = probeProvider;
    }

    /** Effective (decrypted) values for a singleton integration. */
    @Transactional(readOnly = true)
    public Map<String, String> resolve(SettingType type) {
        Map<String, String> values = new LinkedHashMap<>();
        properties.integrationDefaults(type.name().replace('_', '-')).forEach((k, v) -> {
            if (!Strings.isBlank(v)) {
                values.put(normalizeKey(type, k), v);
            }
        });
        repository.findBySettingKey(type.name()).ifPresent(s -> values.putAll(decryptAll(s)));
        return values;
    }

    /** Effective values for a keyed setting such as a Kafka credential reference. */
    @Transactional(readOnly = true)
    public Map<String, String> resolveKey(String key) {
        return repository.findBySettingKey(key).map(this::decryptAll).orElse(Map.of());
    }

    public String require(Map<String, String> values, String key, SettingType type) {
        String value = values.get(key);
        if (Strings.isBlank(value)) {
            throw ApiException.notConfigured(type.label() + " (" + key + ")");
        }
        return value;
    }

    @Transactional(readOnly = true)
    public List<SettingView> list() {
        List<SettingView> views = new ArrayList<>();
        for (SettingType type : SettingType.values()) {
            if (type.singleton()) {
                IntegrationSetting stored = repository.findBySettingKey(type.name()).orElse(null);
                views.add(view(type, type.name(), resolve(type), stored));
            } else {
                for (IntegrationSetting s : repository.findBySettingTypeOrderBySettingKey(type)) {
                    views.add(view(type, s.getSettingKey(), decryptAll(s), s));
                }
            }
        }
        return views;
    }

    @Transactional
    public SettingView save(String key, SettingType type, Map<String, String> input) {
        if (type.singleton() && !key.equals(type.name())) {
            throw ApiException.badRequest("Key for " + type + " must be " + type.name());
        }
        if (!type.singleton() && (Strings.isBlank(key) || !key.matches("[A-Za-z0-9._-]{2,100}"))) {
            throw ApiException.badRequest("Credential reference must be 2-100 characters of letters, digits, '.', '_' or '-'");
        }
        IntegrationSetting setting = repository.findBySettingKey(key).orElseGet(() -> {
            IntegrationSetting s = new IntegrationSetting();
            s.setSettingKey(key);
            s.setSettingType(type);
            return s;
        });
        if (setting.getSettingType() != type) {
            throw ApiException.conflict("Key " + key + " already used by " + setting.getSettingType());
        }
        Map<String, String> config = new LinkedHashMap<>();
        Map<String, String> secrets = setting.getSecrets() == null ? new LinkedHashMap<>() : decryptSecrets(setting.getSecrets());
        List<String> changed = new ArrayList<>();
        Map<String, String> previousConfig = toStringMap(json.readMap(setting.getConfig()));
        for (SettingType.Field field : type.fields()) {
            String value = input.get(field.key());
            if (field.secret()) {
                if (value == null || MASK.equals(value)) {
                    continue; // unchanged
                }
                if (value.isBlank()) {
                    if (secrets.remove(field.key()) != null) changed.add(field.key());
                } else {
                    secrets.put(field.key(), value);
                    changed.add(field.key());
                }
            } else {
                String trimmed = Strings.trimToNull(value);
                if (trimmed != null) {
                    config.put(field.key(), trimmed);
                }
                if (!Objects.equals(trimmed, previousConfig.get(field.key()))) {
                    changed.add(field.key());
                }
            }
        }
        setting.setConfig(json.write(config));
        setting.setSecrets(secrets.isEmpty() ? null : cipher.encrypt(json.write(secrets)));
        setting.setUpdatedBy(CurrentUser.username());
        setting.setUpdatedAt(Instant.now());
        audit.track("SETTINGS_UPDATE", "setting", key, Map.of("type", type.name(), "changedFields", changed),
                () -> repository.save(setting));
        events.publishEvent(new SettingsChangedEvent(type, key));
        return view(type, key, type.singleton() ? resolve(type) : decryptAll(setting), setting);
    }

    @Transactional
    public void delete(String key) {
        IntegrationSetting setting = repository.findBySettingKey(key).orElseThrow(() -> ApiException.notFound("Setting " + key));
        audit.track("SETTINGS_DELETE", "setting", key, Map.of("type", setting.getSettingType().name()),
                () -> repository.delete(setting));
        events.publishEvent(new SettingsChangedEvent(setting.getSettingType(), key));
    }

    public Map<String, Object> test(SettingType type) {
        IntegrationProbe probe = probes().get(type);
        if (probe == null) {
            throw ApiException.badRequest("No connection test available for " + type.label());
        }
        long start = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("details", probe.probe(resolve(type)));
            result.put("success", true);
        } catch (RuntimeException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        result.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
        audit.record("SETTINGS_TEST", "setting", type.name(), Map.of("success", result.get("success")),
                Boolean.TRUE.equals(result.get("success")), (String) result.get("message"));
        return result;
    }

    private Map<SettingType, IntegrationProbe> probes() {
        return probeProvider.orderedStream()
                .collect(Collectors.toMap(IntegrationProbe::type, Function.identity(), (a, b) -> a));
    }

    private SettingView view(SettingType type, String key, Map<String, String> values, IntegrationSetting stored) {
        Map<String, Object> masked = new LinkedHashMap<>();
        Map<String, Boolean> secretSet = new LinkedHashMap<>();
        for (SettingType.Field field : type.fields()) {
            String value = values.get(field.key());
            if (field.secret()) {
                secretSet.put(field.key(), !Strings.isBlank(value));
                masked.put(field.key(), Strings.isBlank(value) ? "" : MASK);
            } else {
                masked.put(field.key(), value == null ? "" : value);
            }
        }
        boolean configured = type.fields().stream().filter(SettingType.Field::required)
                .allMatch(f -> !Strings.isBlank(values.get(f.key())));
        return new SettingView(key, type, type.label(), type.fields(), masked, secretSet, configured,
                probes().containsKey(type), stored == null ? null : stored.getUpdatedBy(), stored == null ? null : stored.getUpdatedAt());
    }

    private Map<String, String> decryptAll(IntegrationSetting s) {
        Map<String, String> values = new LinkedHashMap<>(toStringMap(json.readMap(s.getConfig())));
        if (s.getSecrets() != null) {
            values.putAll(decryptSecrets(s.getSecrets()));
        }
        return values;
    }

    private Map<String, String> decryptSecrets(String encrypted) {
        return toStringMap(json.readMap(cipher.decrypt(encrypted)));
    }

    private static Map<String, String> toStringMap(Map<String, Object> map) {
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, v == null ? null : v.toString()));
        return out;
    }

    /** Property binding may lowercase keys; map them back onto the declared field names. */
    private static String normalizeKey(SettingType type, String key) {
        return type.fields().stream().map(SettingType.Field::key).filter(k -> k.equalsIgnoreCase(key)).findFirst().orElse(key);
    }

    public record SettingView(String key, SettingType type, String label, List<SettingType.Field> fields,
                              Map<String, Object> values, Map<String, Boolean> secretsSet, boolean configured,
                              boolean testable, String updatedBy, Instant updatedAt) {
    }
}
