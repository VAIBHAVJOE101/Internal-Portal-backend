package com.platform.portal.alerts;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.platform.portal.common.Strings;
import com.platform.portal.settings.SettingType;
import com.platform.portal.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Posts new alerts to a Teams / Slack compatible incoming webhook, if configured. */
@Component
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    private final SettingsService settings;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final RestClient http;

    public AlertNotifier(@Lazy SettingsService settings) {
        this.settings = settings;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    void notifyAsync(Alert alert) {
        String title = alert.getTitle();
        Alert.Severity severity = alert.getSeverity();
        String message = alert.getMessage();
        String resource = alert.getResource();
        executor.submit(() -> send(title, severity, message, resource));
    }

    private void send(String title, Alert.Severity severity, String message, String resource) {
        try {
            Map<String, String> cfg = settings.resolve(SettingType.NOTIFICATIONS);
            String url = cfg.get("webhookUrl");
            if (Strings.isBlank(url)) {
                return;
            }
            Alert.Severity min = parse(cfg.get("minSeverity"));
            if (severity.ordinal() > min.ordinal()) {
                return;
            }
            String text = "[%s] %s%s%s".formatted(severity, title,
                    resource == null ? "" : " - " + resource, message == null ? "" : "\n" + message);
            http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(Map.of("text", text)).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Failed to deliver alert notification: {}", e.getMessage());
        }
    }

    private static Alert.Severity parse(String value) {
        try {
            return Strings.isBlank(value) ? Alert.Severity.WARNING : Alert.Severity.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Alert.Severity.WARNING;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
