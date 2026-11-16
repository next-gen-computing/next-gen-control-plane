package com.nextgen.controlplane.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Controller dedicated to serving the single-page cluster dashboard.
 * Loads static/index.html directly from classpath into memory at startup
 * and serves it on all dashboard routes.
 *
 * <p>Serving directly as bytes bypasses Spring MVC view resolution and forward
 * mechanics, preventing circular view path loops (status 500 Whitelabel errors).
 */
@Controller
public class DashboardWebController {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardWebController.class);
    private final byte[] indexHtmlBytes;

    public DashboardWebController() {
        byte[] bytes = new byte[0];
        try {
            ClassPathResource resource = new ClassPathResource("static/index.html");
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    bytes = StreamUtils.copyToByteArray(is);
                    LOG.info("✅ Loaded dashboard index.html into memory ({} bytes)", bytes.length);
                }
            } else {
                LOG.warn("⚠️ static/index.html not found on classpath!");
            }
        } catch (IOException e) {
            LOG.error("Failed to read static/index.html from classpath", e);
        }
        this.indexHtmlBytes = bytes;
    }

    @GetMapping(value = {"/", "/index", "/index.html", "/dashboard", "/dashboard/**", "/ui", "/ui/**"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> getDashboard() {
        if (indexHtmlBytes.length == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<h1>404 — Dashboard index.html not found</h1>".getBytes(StandardCharsets.UTF_8));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        return new ResponseEntity<>(indexHtmlBytes, headers, HttpStatus.OK);
    }
}

