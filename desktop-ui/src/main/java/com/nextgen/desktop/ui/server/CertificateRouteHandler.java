package com.nextgen.desktop.ui.server;

import com.nextgen.agent.AgentCredentials;
import com.nextgen.desktop.ui.server.dto.CertificateStatusDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code GET /api/certificates} — ported from {@code SettingsView.renderCertificateStatus}. Reads the
 * certificate actually on disk on every call (not a cached/configured intent), so re-checking after
 * enrolling elsewhere reflects reality immediately.
 */
public class CertificateRouteHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws java.io.IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        AgentCredentials credentials = AgentCredentials.fromEnvironment();
        credentials.load();
        JsonSupport.sendJson(exchange, 200, status(credentials));
    }

    static CertificateStatusDto status(AgentCredentials credentials) {
        Optional<X509Certificate> certificate = credentials.certificate();
        if (certificate.isEmpty()) {
            return new CertificateStatusDto("NOT_ENROLLED", "#898781",
                    "No client certificate has been issued to this machine. Enrol with a single-use "
                            + "token from the control plane (ENROLLMENT_TOKENS) to obtain one.");
        }

        X509Certificate cert = certificate.get();
        Instant notAfter = cert.getNotAfter().toInstant();
        Instant now = Instant.now();
        long daysLeft = Duration.between(now, notAfter).toDays();

        String state;
        String color;
        if (now.isAfter(notAfter)) {
            state = "EXPIRED";
            color = "#d03b3b";
        } else if (daysLeft <= 7) {
            state = "EXPIRING_SOON";
            color = "#fab219";
        } else {
            state = "VALID";
            color = "#0ca30c";
        }

        String detail = String.format(Locale.ROOT,
                "Subject %s · serial %s · expires %s (%d day%s remaining)",
                cert.getSubjectX500Principal().getName(),
                cert.getSerialNumber().toString(16),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(notAfter),
                Math.max(0, daysLeft), daysLeft == 1 ? "" : "s");

        return new CertificateStatusDto(state, color, detail);
    }
}
