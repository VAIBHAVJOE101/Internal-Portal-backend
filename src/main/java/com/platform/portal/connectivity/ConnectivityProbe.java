package com.platform.portal.connectivity;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.platform.portal.common.Strings;
import com.platform.portal.connectivity.ConnectivityTarget.TestType;
import org.springframework.stereotype.Component;

/**
 * Executes connectivity checks from inside the portal pod, so results reflect the real
 * Kubernetes egress path (network policies, DNS, proxies, firewalls).
 */
@Component
public class ConnectivityProbe {

    public record Spec(TestType type, String host, Integer port, String url, String method, Integer expectedStatus, int timeoutMs) {

        public String describe() {
            return switch (type) {
                case DNS -> host;
                case TCP, TLS -> host + ":" + (port == null ? (type == TestType.TLS ? 443 : 0) : port);
                case HTTP -> (method == null ? "GET" : method) + " " + url;
            };
        }
    }

    public record Outcome(boolean success, long latencyMs, String message, Map<String, Object> details) {
    }

    public Outcome run(Spec spec) {
        long start = System.nanoTime();
        try {
            return switch (spec.type()) {
                case DNS -> dns(spec, start);
                case TCP -> tcp(spec, start);
                case HTTP -> http(spec, start);
                case TLS -> tls(spec, start);
            };
        } catch (UnknownHostException e) {
            return fail(start, "DNS resolution failed for " + e.getMessage());
        } catch (HttpTimeoutException | java.net.SocketTimeoutException e) {
            return fail(start, "Timed out after " + spec.timeoutMs() + " ms");
        } catch (java.net.ConnectException e) {
            return fail(start, "Connection refused / unreachable: " + e.getMessage());
        } catch (javax.net.ssl.SSLException e) {
            return fail(start, "TLS error: " + e.getMessage());
        } catch (IOException e) {
            return fail(start, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(start, "Interrupted");
        } catch (IllegalArgumentException e) {
            return fail(start, "Invalid target: " + e.getMessage());
        }
    }

    private Outcome dns(Spec spec, long start) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(require(spec.host(), "host"));
        List<String> ips = Arrays.stream(addresses).map(InetAddress::getHostAddress).distinct().toList();
        return ok(start, "Resolved to " + String.join(", ", ips), Map.of("addresses", ips));
    }

    private Outcome tcp(Spec spec, long start) throws IOException {
        String host = require(spec.host(), "host");
        if (spec.port() == null) throw new IllegalArgumentException("port is required");
        long dnsStart = System.nanoTime();
        InetAddress address = InetAddress.getByName(host);
        long dnsMs = (System.nanoTime() - dnsStart) / 1_000_000;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, spec.port()), spec.timeoutMs());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("resolvedIp", address.getHostAddress());
            details.put("dnsMs", dnsMs);
            details.put("localAddress", socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort());
            return ok(start, "Connected to " + address.getHostAddress() + ":" + spec.port(), details);
        }
    }

    private Outcome http(Spec spec, long start) throws IOException, InterruptedException {
        URI uri = URI.create(require(spec.url(), "url"));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(spec.timeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String method = spec.method() == null ? "GET" : spec.method().toUpperCase();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(spec.timeoutMs()))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "devops-portal-connectivity/1.0")
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        boolean success = spec.expectedStatus() != null ? status == spec.expectedStatus() : status < 400;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", status);
        details.put("bytes", response.body().length);
        details.put("httpVersion", response.version().name());
        response.headers().firstValue("server").ifPresent(v -> details.put("server", v));
        response.headers().firstValue("content-type").ifPresent(v -> details.put("contentType", v));
        response.headers().firstValue("location").ifPresent(v -> details.put("location", v));
        String message = "HTTP " + status + (spec.expectedStatus() != null && !success ? " (expected " + spec.expectedStatus() + ")" : "");
        long latency = (System.nanoTime() - start) / 1_000_000;
        return new Outcome(success, latency, message, details);
    }

    private Outcome tls(Spec spec, long start) throws IOException {
        String host = require(spec.host(), "host");
        int port = spec.port() == null ? 443 : spec.port();
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), spec.timeoutMs());
            raw.setSoTimeout(spec.timeoutMs());
            try (SSLSocket socket = (SSLSocket) factory.createSocket(raw, host, port, true)) {
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(host)));
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                socket.startHandshake();
                Certificate[] chain = socket.getSession().getPeerCertificates();
                X509Certificate leaf = (X509Certificate) chain[0];
                long daysLeft = ChronoUnit.DAYS.between(Instant.now(), leaf.getNotAfter().toInstant());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("subject", leaf.getSubjectX500Principal().getName());
                details.put("issuer", leaf.getIssuerX500Principal().getName());
                details.put("notAfter", leaf.getNotAfter().toInstant().toString());
                details.put("daysLeft", daysLeft);
                details.put("protocol", socket.getSession().getProtocol());
                details.put("cipherSuite", socket.getSession().getCipherSuite());
                details.put("chainLength", chain.length);
                try {
                    List<String> sans = new ArrayList<>();
                    if (leaf.getSubjectAlternativeNames() != null) {
                        leaf.getSubjectAlternativeNames().forEach(s -> sans.add(String.valueOf(s.get(1))));
                    }
                    details.put("subjectAltNames", sans);
                } catch (java.security.cert.CertificateParsingException ignored) {
                    // optional detail
                }
                String message = daysLeft < 0 ? "Certificate expired" : "TLS OK, certificate valid for " + daysLeft + " more days";
                return new Outcome(daysLeft >= 0, (System.nanoTime() - start) / 1_000_000, message, details);
            }
        }
    }

    private static String require(String value, String name) {
        if (Strings.isBlank(value)) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static Outcome ok(long start, String message, Map<String, Object> details) {
        return new Outcome(true, (System.nanoTime() - start) / 1_000_000, message, details);
    }

    private static Outcome fail(long start, String message) {
        return new Outcome(false, (System.nanoTime() - start) / 1_000_000, message, Map.of());
    }
}
