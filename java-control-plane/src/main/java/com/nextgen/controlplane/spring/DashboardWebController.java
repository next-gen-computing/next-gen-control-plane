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
 * Controller dedicated to serving the single-page cluster dashboard and ambient visualizers.
 * Loads static/index.html and static/glyph-animals.html directly from classpath into memory at startup
 * and serves them on all corresponding web routes.
 *
 * <p>Serving directly as bytes bypasses Spring MVC view resolution and forward
 * mechanics, preventing circular view path loops (status 500 Whitelabel errors).
 */
@Controller
public class DashboardWebController {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardWebController.class);
    private final byte[] indexHtmlBytes;
    private final byte[] animalsHtmlBytes;
    private final byte[] ambient3dJsBytes;

    public DashboardWebController() {
        this.indexHtmlBytes = loadClasspathResource("static/index.html", "Dashboard index.html");
        this.animalsHtmlBytes = loadClasspathResource("static/glyph-animals.html", "Glyph Animals ambient canvas");
        this.ambient3dJsBytes = loadClasspathResource("static/js/ambient-3d-animals.js", "Ambient 3D Fauna JS script");
    }

    private static byte[] loadClasspathResource(String path, String description) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    byte[] bytes = StreamUtils.copyToByteArray(is);
                    LOG.info("✅ Loaded {} into memory ({} bytes)", description, bytes.length);
                    return bytes;
                }
            } else {
                LOG.warn("⚠️ {} not found on classpath at {}", description, path);
            }
        } catch (IOException e) {
            LOG.error("Failed to read {} from classpath", description, e);
        }
        return new byte[0];
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

    @GetMapping(value = {"/animals", "/animals.html", "/glyph-animals", "/glyph-animals.html"}, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> getGlyphAnimals() {
        if (animalsHtmlBytes.length == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<h1>404 — Glyph Animals canvas not found</h1>".getBytes(StandardCharsets.UTF_8));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        return new ResponseEntity<>(animalsHtmlBytes, headers, HttpStatus.OK);
    }

    @GetMapping(value = {"/js/ambient-3d-animals.js", "/ambient-3d-animals.js"}, produces = "application/javascript")
    @ResponseBody
    public ResponseEntity<byte[]> getAmbient3dJs() {
        if (ambient3dJsBytes.length == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("// 404 — ambient-3d-animals.js not found".getBytes(StandardCharsets.UTF_8));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "javascript", StandardCharsets.UTF_8));
        headers.setCacheControl("public, max-age=3600");
        return new ResponseEntity<>(ambient3dJsBytes, headers, HttpStatus.OK);
    }
}
