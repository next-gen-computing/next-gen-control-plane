package com.nextgen.controlplane.alert;

/**
 * Stage GG: consumes the two real node-health transition events {@code HeartbeatMonitor}/{@code
 * RiskMonitor} already compute — {@code StatusTransition} (reactive: a node's heartbeat actually
 * stopped) and the rising edge of {@code RiskTransition} (predictive: a node just crossed into
 * at-risk) — and pushes a real, external alert for each. Previously no alerting existed beyond the
 * dashboard itself; an operator not actively watching it had no way to learn about either event.
 *
 * <p>The notification channel itself was an explicitly open question in the project plan (email/
 * webhook/desktop-notification all named as options, none decided). {@link WebhookAlertNotifier} is
 * the concrete channel implemented here — a generic HTTP POST that Slack/PagerDuty/Discord/a custom
 * receiver can all consume, and the smallest real building block other channels could be layered on
 * top of later (an email or desktop-notification {@code AlertNotifier} implementation is a small,
 * separate addition behind this same interface, not a redesign).
 *
 * <p>Every implementation must be best-effort: a failed or slow notification must never affect the
 * liveness/risk detection that triggered it. See {@link WebhookAlertNotifier}'s own discipline.
 */
public interface AlertNotifier {

    /** A node's heartbeat actually stopped ({@code ALIVE -> SUSPECTED_DEAD}) — {@code HeartbeatMonitor}'s
     * reactive detection. */
    void notifyNodeDown(String nodeId, String reason);

    /** A node just crossed the false→true edge of {@code atRisk} — {@code RiskMonitor}'s predictive
     * detection, fired before the node has actually failed. */
    void notifyNodeAtRisk(String nodeId, double riskScore, String reason);
}
