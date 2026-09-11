package com.nextgen.controlplane.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fans a single alert out to every configured channel — the seam that lets an operator enable any
 * combination of {@link WebhookAlertNotifier}, {@link EmailAlertNotifier}, and {@link
 * DesktopNotificationAlertNotifier} at once, while {@code HeartbeatMonitor}/{@code RiskMonitor}
 * themselves keep taking exactly one {@link AlertNotifier} and stay unaware that more than one channel
 * might be behind it.
 *
 * <p>Each channel's own implementation is already internally best-effort (async, failures swallowed
 * into a log line), so an exception escaping synchronously from one is not expected in normal operation
 * — but this class still isolates each channel in its own try/catch so a defect in one implementation
 * can never stop a different, working channel from receiving the same alert.
 */
public final class CompositeAlertNotifier implements AlertNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(CompositeAlertNotifier.class);

    private final List<AlertNotifier> notifiers;

    public CompositeAlertNotifier(List<AlertNotifier> notifiers) {
        this.notifiers = List.copyOf(notifiers);
    }

    @Override
    public void notifyNodeDown(String nodeId, String reason) {
        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.notifyNodeDown(nodeId, reason);
            } catch (RuntimeException e) {
                LOG.warn("Alert channel {} failed on notifyNodeDown: {}",
                        notifier.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void notifyNodeAtRisk(String nodeId, double riskScore, String reason) {
        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.notifyNodeAtRisk(nodeId, riskScore, reason);
            } catch (RuntimeException e) {
                LOG.warn("Alert channel {} failed on notifyNodeAtRisk: {}",
                        notifier.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
