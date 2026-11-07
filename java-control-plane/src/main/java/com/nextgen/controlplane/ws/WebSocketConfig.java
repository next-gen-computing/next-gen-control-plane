package com.nextgen.controlplane.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Spring WebSocket (STOMP) configuration.
 *
 * <p>Clients connect to {@code ws://localhost:8080/ws} and subscribe to topics:
 * <ul>
 *   <li>{@code /topic/nodes}         — live node registry updates</li>
 *   <li>{@code /topic/cluster}       — cluster-wide summary (avg CPU, node counts)</li>
 *   <li>{@code /topic/tasks}         — task state transitions</li>
 *   <li>{@code /topic/jobs}          — job lifecycle events</li>
 *   <li>{@code /topic/cluster-tasks} — running task list across all nodes</li>
 * </ul>
 *
 * <p>The frontend Next.js app (Phase 2) subscribes via {@code @stomp/stompjs}.
 * The existing JavaFX desktop-ui WebView continues using SSE ({@code /api/stream})
 * until it is migrated in Phase 4.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Simple in-memory broker for /topic/** subscriptions
        config.enableSimpleBroker("/topic");
        // Prefix for @MessageMapping methods (client → server commands)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Primary WebSocket endpoint — modern browsers
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");

        // SockJS fallback for environments that block raw WebSockets
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
