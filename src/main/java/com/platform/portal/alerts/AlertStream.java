package com.platform.portal.alerts;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Server-sent events fan-out so the UI alert bell updates live. */
@Component
public class AlertStream {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, "hello", Map.of("ok", true));
        return emitter;
    }

    public void publish(String event, Object payload) {
        for (SseEmitter emitter : emitters) {
            send(emitter, event, payload);
        }
    }

    /** Keeps idle connections open through proxies / ingress controllers. */
    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        publish("ping", Map.of("ts", System.currentTimeMillis()));
    }

    private void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
