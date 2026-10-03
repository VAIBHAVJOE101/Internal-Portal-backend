package com.platform.portal.alerts;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.platform.portal.alerts.AlertRuleService.Policy;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.Strings;
import com.platform.portal.config.PortalProperties;
import com.platform.portal.settings.IntegrationProbe;
import com.platform.portal.settings.SettingType;
import com.platform.portal.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

/**
 * Delivers alert notifications to Email (SMTP) and Microsoft Teams (incoming webhook / Workflows,
 * Adaptive Card payload). Delivery is asynchronous; each attempt is written to the alert timeline.
 */
@Component
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.systemDefault());

    public enum Kind { FIRING, REPEAT, ESCALATION, RESOLVED, TEST }

    /** Immutable copy of the alert taken inside the transaction, safe to use on another thread. */
    public record Message(Long alertId, AlertType type, Alert.Severity severity, Alert.Status status, String title, String message,
                          String resource, Instant firedAt, int occurrences, int notificationNumber, int escalationLevel,
                          String resolvedReason) {
        static Message of(Alert a) {
            return new Message(a.getId(), a.getType(), a.getSeverity(), a.getStatus(), a.getTitle(), a.getMessage(), a.getResource(),
                    a.getFiredAt() == null ? a.getFirstSeen() : a.getFiredAt(), a.getOccurrences(), a.getNotificationCount(),
                    a.getEscalationLevel(), a.getResolvedReason());
        }
    }

    public record Delivery(String channel, boolean success, String detail) {
    }

    private final SettingsService settings;
    private final AlertEvent.Repository events;
    private final PortalProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final RestClient http;

    public AlertNotifier(@Lazy SettingsService settings, AlertEvent.Repository events, PortalProperties properties) {
        this.settings = settings;
        this.events = events;
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Fire-and-forget delivery; outcomes are recorded as alert events. */
    void dispatch(Message m, Policy policy, Kind kind, List<String> extraEmails, boolean teams, boolean escalationChannel) {
        executor.submit(() -> {
            for (Delivery d : deliver(m, policy, kind, extraEmails, teams, escalationChannel)) {
                AlertEvent.Kind ek = d.detail().startsWith("skipped") ? AlertEvent.Kind.NOTIFY_SKIPPED
                        : d.success() ? AlertEvent.Kind.NOTIFIED : AlertEvent.Kind.NOTIFY_FAILED;
                try {
                    events.save(new AlertEvent(m.alertId(), ek, d.channel(), d.success(), kind.name().toLowerCase() + ": " + d.detail(), "notifier"));
                } catch (RuntimeException e) {
                    log.warn("Could not record notification event for alert {}", m.alertId(), e);
                }
            }
        });
    }

    /** Synchronous delivery, used for "send test" from the rules screen. */
    public List<Delivery> deliver(Message m, Policy policy, Kind kind, List<String> extraEmails, boolean teams, boolean escalationChannel) {
        List<Delivery> out = new ArrayList<>();
        Set<String> recipients = new LinkedHashSet<>();
        if (policy.emailEnabled()) {
            recipients.addAll(policy.emailRecipients());
            if (recipients.isEmpty()) {
                recipients.addAll(Strings.asList(settings.resolve(SettingType.EMAIL).get("defaultRecipients")));
            }
        }
        if (extraEmails != null) recipients.addAll(extraEmails);
        if (!recipients.isEmpty()) {
            out.add(sendEmail(m, kind, List.copyOf(recipients)));
        } else if (policy.emailEnabled()) {
            out.add(new Delivery("email", false, "skipped – no recipients configured"));
        }
        boolean postTeams = kind == Kind.ESCALATION ? teams : policy.teamsEnabled();
        if (postTeams) {
            out.add(sendTeams(m, kind, escalationChannel));
        }
        if (out.isEmpty()) {
            out.add(new Delivery("none", false, "skipped – no channel enabled for this rule"));
        }
        return out;
    }

    // ------------------------------------------------------------------ email

    private Delivery sendEmail(Message m, Kind kind, List<String> to) {
        Map<String, String> cfg = settings.resolve(SettingType.EMAIL);
        if (Strings.isBlank(cfg.get("host")) || Strings.isBlank(cfg.get("from"))) {
            return new Delivery("email", false, "skipped – SMTP not configured (Settings → Email)");
        }
        try {
            JavaMailSenderImpl sender = mailSender(cfg);
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(cfg.get("from"));
            helper.setTo(to.toArray(String[]::new));
            String prefix = Strings.isBlank(cfg.get("subjectPrefix")) ? "[Platform Portal]" : cfg.get("subjectPrefix");
            helper.setSubject("%s %s %s".formatted(prefix, headline(m, kind), m.title()));
            helper.setText(plainText(m, kind), emailHtml(m, kind));
            sender.send(mime);
            return new Delivery("email", true, "sent to " + String.join(", ", to));
        } catch (Exception e) {
            log.warn("Alert email failed: {}", e.getMessage());
            return new Delivery("email", false, "failed – " + e.getMessage());
        }
    }

    static JavaMailSenderImpl mailSender(Map<String, String> cfg) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.get("host"));
        sender.setPort(Integer.parseInt(cfg.getOrDefault("port", "587")));
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        if (!Strings.isBlank(cfg.get("username"))) {
            sender.setUsername(cfg.get("username"));
            sender.setPassword(cfg.get("password"));
            props.put("mail.smtp.auth", "true");
        }
        boolean startTls = !"false".equalsIgnoreCase(cfg.getOrDefault("startTls", "true"));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    private String emailHtml(Message m, Kind kind) {
        String color = kind == Kind.RESOLVED ? "#16a34a" : m.severity() == Alert.Severity.CRITICAL ? "#dc2626"
                : m.severity() == Alert.Severity.WARNING ? "#d97706" : "#0284c7";
        StringBuilder rows = new StringBuilder();
        facts(m, kind).forEach((k, v) -> rows.append("<tr><td style=\"padding:4px 12px 4px 0;color:#64748b\">").append(esc(k))
                .append("</td><td style=\"padding:4px 0;color:#0f172a\">").append(esc(v)).append("</td></tr>"));
        return """
                <div style="font-family:Segoe UI,Arial,sans-serif;max-width:620px;margin:0 auto;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden">
                  <div style="background:%s;color:#fff;padding:12px 20px;font-size:13px;font-weight:600;letter-spacing:.04em">%s</div>
                  <div style="padding:20px">
                    <h2 style="margin:0 0 8px;font-size:18px;color:#0f172a">%s</h2>
                    <p style="margin:0 0 16px;color:#334155;white-space:pre-wrap">%s</p>
                    <table style="font-size:13px;border-collapse:collapse">%s</table>
                    <p style="margin:20px 0 0"><a href="%s" style="background:#2563eb;color:#fff;text-decoration:none;padding:9px 16px;border-radius:8px;font-size:13px">Open in portal</a></p>
                  </div>
                </div>""".formatted(color, esc(headline(m, kind)), esc(m.title()), esc(m.message() == null ? "" : m.message()),
                rows, esc(link(m)));
    }

    private String plainText(Message m, Kind kind) {
        StringBuilder sb = new StringBuilder(headline(m, kind)).append('\n').append(m.title()).append("\n\n");
        if (m.message() != null) sb.append(m.message()).append("\n\n");
        facts(m, kind).forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.append('\n').append(link(m)).toString();
    }

    // ------------------------------------------------------------------ teams

    private Delivery sendTeams(Message m, Kind kind, boolean escalationChannel) {
        Map<String, String> cfg = settings.resolve(SettingType.TEAMS);
        String url = escalationChannel && !Strings.isBlank(cfg.get("escalationWebhookUrl")) ? cfg.get("escalationWebhookUrl") : cfg.get("webhookUrl");
        String channel = escalationChannel && !Strings.isBlank(cfg.get("escalationWebhookUrl")) ? "teams-escalation" : "teams";
        if (Strings.isBlank(url)) {
            return new Delivery(channel, false, "skipped – Teams webhook not configured (Settings → Microsoft Teams)");
        }
        try {
            http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(teamsCard(m, kind)).retrieve().toBodilessEntity();
            return new Delivery(channel, true, "posted to channel");
        } catch (Exception e) {
            log.warn("Teams notification failed: {}", e.getMessage());
            return new Delivery(channel, false, "failed – " + Strings.truncate(e.getMessage(), 300));
        }
    }

    private Map<String, Object> teamsCard(Message m, Kind kind) {
        String color = kind == Kind.RESOLVED ? "Good" : m.severity() == Alert.Severity.CRITICAL ? "Attention"
                : m.severity() == Alert.Severity.WARNING ? "Warning" : "Accent";
        List<Map<String, Object>> facts = facts(m, kind).entrySet().stream()
                .map(e -> Map.<String, Object>of("title", e.getKey(), "value", e.getValue())).toList();
        List<Object> body = new ArrayList<>();
        body.add(Map.of("type", "TextBlock", "text", headline(m, kind), "weight", "Bolder", "color", color, "size", "Small"));
        body.add(Map.of("type", "TextBlock", "text", m.title(), "wrap", true, "size", "Large", "weight", "Bolder"));
        if (!Strings.isBlank(m.message())) {
            body.add(Map.of("type", "TextBlock", "text", Strings.truncate(m.message(), 900), "wrap", true, "isSubtle", true));
        }
        body.add(Map.of("type", "FactSet", "facts", facts));
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("msteams", Map.of("width", "Full"));
        card.put("body", body);
        card.put("actions", List.of(Map.of("type", "Action.OpenUrl", "title", "Open in portal", "url", link(m))));
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("content", card);
        return Map.of("type", "message", "attachments", List.of(attachment));
    }

    // ------------------------------------------------------------------ shared

    private static String headline(Message m, Kind kind) {
        return switch (kind) {
            case FIRING -> "FIRING · " + m.severity();
            case REPEAT -> "STILL FIRING · " + m.severity() + " · reminder " + Math.max(1, m.notificationNumber() - 1);
            case ESCALATION -> "ESCALATED · level " + m.escalationLevel() + " · " + m.severity();
            case RESOLVED -> "RESOLVED";
            case TEST -> "TEST NOTIFICATION";
        };
    }

    private static Map<String, String> facts(Message m, Kind kind) {
        Map<String, String> f = new LinkedHashMap<>();
        if (m.type() != null) f.put("Alert type", m.type().label());
        if (m.resource() != null) f.put("Resource", m.resource());
        if (m.firedAt() != null) f.put(kind == Kind.RESOLVED ? "Fired at" : "Since", TIME.format(m.firedAt()));
        f.put("Occurrences", String.valueOf(m.occurrences()));
        if (m.escalationLevel() > 0) f.put("Escalation level", String.valueOf(m.escalationLevel()));
        if (kind == Kind.RESOLVED && m.resolvedReason() != null) f.put("Resolution", m.resolvedReason());
        return f;
    }

    private String link(Message m) {
        String base = properties.publicUrl() == null ? "" : properties.publicUrl().replaceAll("/+$", "");
        return base + "/alerts" + (m.alertId() == null ? "" : "?focus=" + m.alertId());
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    // ------------------------------------------------------------------ connection tests

    /** "Test connection" for Settings → Email: verifies SMTP login and sends a test mail to the default recipients. */
    @Component
    static class EmailProbe implements IntegrationProbe {
        @Override
        public SettingType type() {
            return SettingType.EMAIL;
        }

        @Override
        public Map<String, Object> probe(Map<String, String> cfg) {
            if (Strings.isBlank(cfg.get("host")) || Strings.isBlank(cfg.get("from"))) {
                throw ApiException.notConfigured("Email (host / from)");
            }
            JavaMailSenderImpl sender = mailSender(cfg);
            try {
                sender.testConnection();
                List<String> to = Strings.asList(cfg.get("defaultRecipients"));
                if (!to.isEmpty()) {
                    MimeMessage mime = sender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
                    helper.setFrom(cfg.get("from"));
                    helper.setTo(to.toArray(String[]::new));
                    helper.setSubject("[Platform Portal] Test email");
                    helper.setText("Email notifications from the Platform Portal are working.");
                    sender.send(mime);
                }
                return Map.of("smtp", cfg.get("host") + ":" + cfg.getOrDefault("port", "587"), "testMailTo", to.isEmpty() ? "none" : String.join(", ", to));
            } catch (Exception e) {
                throw ApiException.upstream("SMTP: " + e.getMessage(), e);
            }
        }
    }

    /** "Test connection" for Settings → Microsoft Teams: posts a test card to the webhook. */
    @Component
    static class TeamsProbe implements IntegrationProbe {
        private final AlertNotifier notifier;

        TeamsProbe(@Lazy AlertNotifier notifier) {
            this.notifier = notifier;
        }

        @Override
        public SettingType type() {
            return SettingType.TEAMS;
        }

        @Override
        public Map<String, Object> probe(Map<String, String> cfg) {
            if (Strings.isBlank(cfg.get("webhookUrl"))) {
                throw ApiException.notConfigured("Microsoft Teams (webhook URL)");
            }
            Message m = new Message(null, null, Alert.Severity.INFO, Alert.Status.OPEN, "Teams notifications are working",
                    "This is a test message from the Platform Portal.", null, Instant.now(), 1, 1, 0, null);
            Delivery d = notifier.sendTeams(m, Kind.TEST, false);
            if (!d.success()) throw ApiException.upstream(d.detail(), null);
            return Map.of("result", d.detail());
        }
    }
}
