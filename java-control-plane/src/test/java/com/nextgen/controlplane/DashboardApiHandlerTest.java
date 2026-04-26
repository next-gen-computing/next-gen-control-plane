package com.nextgen.controlplane;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DashboardApiHandler - verifies JSON API responses.
 */
class DashboardApiHandlerTest {

    @Test
    void testEmptyRegistryReturnsEmptyNodes() throws IOException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        DashboardApiHandler handler = new DashboardApiHandler(registry);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestMethod()).thenReturn("GET");
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(outputStream);

        handler.handle(exchange);

        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"nodes\":[]"));
        assertTrue(response.contains("\"totalNodes\":0"));
        assertTrue(response.contains("\"aliveNodes\":0"));
        assertTrue(response.contains("\"deadNodes\":0"));
    }

    @Test
    void testSingleNodeInRegistry() throws IOException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        record.setCpuUsage(45.5f);
        record.setMemoryUsage(78.3f);
        registry.put("node1", record);

        DashboardApiHandler handler = new DashboardApiHandler(registry);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestMethod()).thenReturn("GET");
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(outputStream);

        handler.handle(exchange);

        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"nodeId\":\"node1\""));
        assertTrue(response.contains("\"ip\":\"192.168.1.1\""));
        assertTrue(response.contains("\"hostname\":\"host1\""));
        assertTrue(response.contains("\"cpuUsage\":45.50"));
        assertTrue(response.contains("\"memoryUsage\":78.30"));
        assertTrue(response.contains("\"totalNodes\":1"));
        assertTrue(response.contains("\"aliveNodes\":1"));
    }

    @Test
    void testCorsHeadersSet() throws IOException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        DashboardApiHandler handler = new DashboardApiHandler(registry);

        HttpExchange exchange = mock(HttpExchange.class);
        Headers headers = new Headers();
        when(exchange.getResponseHeaders()).thenReturn(headers);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());

        handler.handle(exchange);

        assertEquals("*", headers.getFirst("Access-Control-Allow-Origin"));
        assertEquals("GET, OPTIONS", headers.getFirst("Access-Control-Allow-Methods"));
        assertTrue(headers.containsKey("Content-Type"));
    }

    @Test
    void testOptionsRequestReturns204() throws IOException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        DashboardApiHandler handler = new DashboardApiHandler(registry);

        HttpExchange exchange = mock(HttpExchange.class);
        Headers headers = new Headers();
        when(exchange.getResponseHeaders()).thenReturn(headers);
        when(exchange.getRequestMethod()).thenReturn("OPTIONS");

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(204, -1);
    }

    @Test
    void testDeadNodeCountedCorrectly() throws IOException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        
        NodeRecord aliveNode = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        registry.put("node1", aliveNode);
        
        NodeRecord deadNode = new NodeRecord("node2", "192.168.1.2", 50051, "host2");
        deadNode.setStatus("SUSPECTED_DEAD");
        registry.put("node2", deadNode);

        DashboardApiHandler handler = new DashboardApiHandler(registry);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestMethod()).thenReturn("GET");
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(outputStream);

        handler.handle(exchange);

        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"totalNodes\":2"));
        assertTrue(response.contains("\"aliveNodes\":1"));
        assertTrue(response.contains("\"deadNodes\":1"));
    }

    @Test
    void testJsonEscaping() throws IOException {
        ConcurrentHashMap<String, NodeRecord> registry = new ConcurrentHashMap<>();
        // Node with special characters in hostname
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host\"with\\quotes");
        registry.put("node1", record);

        DashboardApiHandler handler = new DashboardApiHandler(registry);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestMethod()).thenReturn("GET");
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(outputStream);

        handler.handle(exchange);

        String response = outputStream.toString(StandardCharsets.UTF_8);
        // Should contain escaped quotes
        assertTrue(response.contains("host\\\"with\\\\quotes"));
    }
}
