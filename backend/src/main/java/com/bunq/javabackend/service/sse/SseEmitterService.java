package com.bunq.javabackend.service.sse;

import com.bunq.javabackend.dto.response.events.PipelineEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SseEmitterService {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    public SseEmitterService() {
        heartbeat.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
    }

    public SseEmitter register(String sessionId) {
        return register(sessionId, SSE_TIMEOUT_MS);
    }

    public SseEmitter register(String sessionId, long timeoutMs) {
        List<SseEmitter> sessionEmitters = emitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());

        cleanupStaleEmitters(sessionId, sessionEmitters);

        SseEmitter emitter = new SseEmitter(timeoutMs);

        Runnable removeCallback = () -> {
            sessionEmitters.remove(emitter);
            if (sessionEmitters.isEmpty()) {
                emitters.remove(sessionId, sessionEmitters);
            }
        };

        emitter.onCompletion(() -> {
            removeCallback.run();
            log.debug("SSE connection completed for session {} (remaining: {})", sessionId, sessionEmitters.size());
        });

        emitter.onTimeout(() -> {
            emitter.complete();
            log.debug("SSE connection timed out for session {}", sessionId);
        });

        emitter.onError(e -> {
            removeCallback.run();
            log.debug("SSE connection error for session {}: {}", sessionId, e.getMessage());
        });

        sessionEmitters.add(emitter);

        try {
            emitter.send(SseEmitter.event().name("connected").data("Connected"));
        } catch (IOException e) {
            log.debug("Failed to send initial SSE event to session {} (client likely disconnected)", sessionId);
            sessionEmitters.remove(emitter);
            emitter.completeWithError(e);
        }

        log.info("Session {} subscribed to SSE (active connections: {})", sessionId, sessionEmitters.size());
        return emitter;
    }

    public void send(String sessionId, PipelineEvent event) {
        send(sessionId, event.getType(), event);
    }

    public void send(String sessionId, String eventName, Object data) {
        List<SseEmitter> sessionEmitters = emitters.get(sessionId);
        if (sessionEmitters == null || sessionEmitters.isEmpty()) {
            log.debug("No active SSE connections for session {}, event '{}' not sent", sessionId, eventName);
            return;
        }

        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : sessionEmitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                log.debug("Failed to send SSE event '{}' to session {} (client likely disconnected)", eventName, sessionId);
                dead.add(emitter);
            }
        }

        for (SseEmitter d : dead) {
            sessionEmitters.remove(d);
            try {
                d.completeWithError(new IOException("Send failed"));
            } catch (Exception ignored) {}
        }
    }

    public void complete(String sessionId) {
        List<SseEmitter> sessionEmitters = emitters.remove(sessionId);
        if (sessionEmitters != null) {
            sessionEmitters.forEach(SseEmitter::complete);
            log.debug("Removed {} SSE emitter(s) for session {}", sessionEmitters.size(), sessionId);
        }
    }

    public boolean hasActiveConnection(String sessionId) {
        List<SseEmitter> sessionEmitters = emitters.get(sessionId);
        return sessionEmitters != null && !sessionEmitters.isEmpty();
    }

    private void cleanupStaleEmitters(String sessionId, List<SseEmitter> sessionEmitters) {
        if (sessionEmitters.isEmpty()) return;

        List<SseEmitter> stale = new ArrayList<>();
        for (SseEmitter emitter : sessionEmitters) {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (Exception e) {
                stale.add(emitter);
            }
        }

        if (!stale.isEmpty()) {
            log.info("Cleaning up {} stale SSE connections for session {}", stale.size(), sessionId);
            for (SseEmitter dead : stale) {
                sessionEmitters.remove(dead);
                try {
                    dead.complete();
                } catch (Exception ignored) {}
            }
        }
    }

    private void sendHeartbeat() {
        for (Map.Entry<String, List<SseEmitter>> entry : new ArrayList<>(emitters.entrySet())) {
            String sessionId = entry.getKey();
            List<SseEmitter> sessionEmitters = entry.getValue();
            List<SseEmitter> dead = new ArrayList<>();
            for (SseEmitter emitter : sessionEmitters) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data(""));
                } catch (Exception e) {
                    log.debug("SSE emitter for key {} dead, removing: {}", sessionId, e.getMessage());
                    dead.add(emitter);
                }
            }
            for (SseEmitter d : dead) {
                sessionEmitters.remove(d);
                try {
                    d.completeWithError(new IOException("Heartbeat failed"));
                } catch (Exception ignored) {}
            }
            if (sessionEmitters.isEmpty()) {
                emitters.remove(sessionId, sessionEmitters);
            }
        }
    }
}
