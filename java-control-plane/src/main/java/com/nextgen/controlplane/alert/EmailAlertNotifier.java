package com.nextgen.controlplane.alert;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A real SMTP email, sent via Angus Mail (the Jakarta Mail reference implementation) — the second
 * concrete {@link AlertNotifier} channel, alongside {@link WebhookAlertNotifier}, closing one of the
 * two named-but-undecided options from that interface's own Javadoc. No email-sending capability
 * existed anywhere in this project before this class; the account system's password reset deliberately
 * uses an offline recovery code for exactly that reason.
 *
 * <p>{@code Transport.send(...)} is a real, synchronous, blocking network call — every send here runs on
 * a dedicated single-thread executor, never the calling monitor thread, matching {@link
 * WebhookAlertNotifier#send}'s identical "a slow/unreachable channel must never block detection"
 * discipline. A single-thread executor also means a burst of alerts is delivered in order rather than
 * as a storm of concurrent SMTP connections against one server.
 */
public final class EmailAlertNotifier implements AlertNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(EmailAlertNotifier.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Session session;
    private final String fromAddress;
    private final List<String> toAddresses;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "email-alert-notifier");
        t.setDaemon(true);
        return t;
    });

    public EmailAlertNotifier(String smtpHost, int smtpPort, String username, String password,
                               boolean useTls, String fromAddress, List<String> toAddresses) {
        this.fromAddress = fromAddress;
        this.toAddresses = List.copyOf(toAddresses);

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        props.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(useTls));
        // A real connect/write timeout — an unreachable/black-holed SMTP host must fail fast rather than
        // hang the single background thread every subsequent alert also waits behind.
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        this.session = username.isBlank()
                ? Session.getInstance(props)
                : Session.getInstance(props, new jakarta.mail.Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });
    }

    @Override
    public void notifyNodeDown(String nodeId, String reason) {
        send("NextGen Control Plane: node '" + nodeId + "' is down",
                "Node:      " + nodeId + "\n"
                        + "Event:     reactive detection (heartbeat actually stopped)\n"
                        + "Reason:    " + reason + "\n"
                        + "Time (UTC): " + TIMESTAMP_FORMAT.format(Instant.now()) + "\n");
    }

    @Override
    public void notifyNodeAtRisk(String nodeId, double riskScore, String reason) {
        send("NextGen Control Plane: node '" + nodeId + "' is at risk",
                "Node:      " + nodeId + "\n"
                        + "Event:     predictive detection (risk crossed threshold before failure)\n"
                        + "Risk score: " + riskScore + "\n"
                        + "Reason:    " + reason + "\n"
                        + "Time (UTC): " + TIMESTAMP_FORMAT.format(Instant.now()) + "\n");
    }

    private void send(String subject, String body) {
        // Fire-and-forget on the dedicated executor: a failure here is logged, never thrown back into
        // the caller's sweep thread, matching WebhookAlertNotifier's exact same guarantee.
        executor.submit(() -> {
            try {
                MimeMessage message = new MimeMessage(session);
                message.setFrom(new InternetAddress(fromAddress));
                for (String to : toAddresses) {
                    message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
                }
                message.setSubject(subject);
                message.setText(body);
                Transport.send(message);
            } catch (MessagingException e) {
                LOG.warn("Could not deliver alert email ({}): {}", subject, e.getMessage());
            }
        });
    }
}
