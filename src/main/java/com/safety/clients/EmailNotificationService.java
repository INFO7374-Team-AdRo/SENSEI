package com.safety.clients;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * Sends email alerts via SMTP for T2_ALERT and T3_SHUTDOWN escalations.
 * Configure SMTP settings in application.conf under safety.email.*
 * Supports Gmail, Outlook, SendGrid, or any SMTP relay.
 */
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final Session session;
    private final String fromAddress;
    private final String toAddress;
    private final boolean enabled;

    public EmailNotificationService(String host, int port,
                                    String username, String password,
                                    String from, String to,
                                    boolean useTls) {
        this.fromAddress = from;
        this.toAddress   = to;

        boolean ok = false;
        Session sess = null;

        if (host == null || host.isBlank() || to == null || to.isBlank()) {
            log.warn("EmailNotificationService disabled — smtp.host or email.to not configured");
        } else {
            try {
                Properties props = new Properties();
                props.put("mail.smtp.host",            host);
                props.put("mail.smtp.port",            String.valueOf(port));
                props.put("mail.smtp.auth",            "true");
                props.put("mail.smtp.starttls.enable", String.valueOf(useTls));
                props.put("mail.smtp.ssl.trust",       host);

                final String user = username;
                final String pass = password;
                sess = Session.getInstance(props, new Authenticator() {
                    @Override protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass);
                    }
                });
                ok = true;
                log.info("EmailNotificationService ready — {}:{} → {}", host, port, to);
            } catch (Exception e) {
                log.warn("EmailNotificationService setup failed: {}", e.getMessage());
            }
        }

        this.session = sess;
        this.enabled = ok;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Sends an escalation alert email.
     *
     * @param tier          "T2_ALERT" or "T3_SHUTDOWN"
     * @param hazardType    e.g. "Smoke", "LPG+CO"
     * @param severity      e.g. "high", "critical"
     * @param confidence    fusion confidence 0.0–1.0
     * @param recommendation operational recommendation from LLM
     * @param causalChain   root-cause explanation
     * @param incidentId    e.g. "INC-1714392847123"
     * @param timestamp     when the escalation was finalised
     */
    public void sendEscalationAlert(
            String tier, String hazardType, String severity,
            double confidence, String recommendation,
            String causalChain, String incidentId, Instant timestamp) {

        if (!enabled) return;

        try {
            boolean isCritical = "T3_SHUTDOWN".equals(tier);

            String subject = String.format("[%s] %s — %s · %s",
                isCritical ? "🚨 EMERGENCY" : "⚠️ ALERT",
                hazardType,
                severity.toUpperCase(),
                incidentId);

            String body = buildBody(tier, hazardType, severity, confidence,
                                    recommendation, causalChain, incidentId, timestamp);

            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(fromAddress));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress));
            msg.setSubject(subject);
            msg.setContent(body, "text/html; charset=utf-8");

            Transport.send(msg);
            log.info("Escalation email sent → {} subject=\"{}\"", toAddress, subject);

        } catch (Exception e) {
            log.error("Failed to send escalation email: {}", e.getMessage());
        }
    }

    private String buildBody(String tier, String hazardType, String severity,
                              double confidence, String recommendation,
                              String causalChain, String incidentId, Instant timestamp) {

        boolean isCritical = "T3_SHUTDOWN".equals(tier);
        String headerColor = isCritical ? "#dc2626" : "#ea580c";
        String tierLabel   = isCritical ? "T3 — EMERGENCY SHUTDOWN" : "T2 — OPERATOR ALERT";
        String timeStr     = FMT.format(timestamp);

        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:24px">
              <div style="max-width:600px;margin:0 auto">

                <!-- Header -->
                <div style="background:%s;border-radius:12px 12px 0 0;padding:20px 24px">
                  <div style="font-size:11px;font-weight:600;letter-spacing:0.1em;color:rgba(255,255,255,0.7)">
                    INDUSTRIAL SAFETY MONITORING SYSTEM
                  </div>
                  <div style="font-size:22px;font-weight:700;color:#fff;margin-top:4px">%s</div>
                  <div style="font-size:13px;color:rgba(255,255,255,0.8);margin-top:2px">%s</div>
                </div>

                <!-- Body -->
                <div style="background:#1e293b;border-radius:0 0 12px 12px;padding:24px;border:1px solid #334155;border-top:none">

                  <!-- Key metrics -->
                  <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;margin-bottom:20px">
                    <div style="background:#0f172a;border-radius:8px;padding:12px;border:1px solid #334155">
                      <div style="font-size:10px;color:#64748b;text-transform:uppercase;letter-spacing:0.05em">Hazard Type</div>
                      <div style="font-size:16px;font-weight:700;color:#f1f5f9;margin-top:4px">%s</div>
                    </div>
                    <div style="background:#0f172a;border-radius:8px;padding:12px;border:1px solid #334155">
                      <div style="font-size:10px;color:#64748b;text-transform:uppercase;letter-spacing:0.05em">Severity</div>
                      <div style="font-size:16px;font-weight:700;color:%s;margin-top:4px">%s</div>
                    </div>
                    <div style="background:#0f172a;border-radius:8px;padding:12px;border:1px solid #334155">
                      <div style="font-size:10px;color:#64748b;text-transform:uppercase;letter-spacing:0.05em">Confidence</div>
                      <div style="font-size:16px;font-weight:700;color:#f1f5f9;margin-top:4px">%.1f%%</div>
                    </div>
                  </div>

                  <!-- Root cause -->
                  <div style="margin-bottom:16px">
                    <div style="font-size:11px;font-weight:600;color:#64748b;text-transform:uppercase;letter-spacing:0.05em;margin-bottom:6px">Root Cause</div>
                    <div style="background:#0f172a;border-left:3px solid %s;border-radius:4px;padding:12px;font-size:13px;color:#cbd5e1;line-height:1.6">%s</div>
                  </div>

                  <!-- Recommendation -->
                  <div style="margin-bottom:20px">
                    <div style="font-size:11px;font-weight:600;color:#64748b;text-transform:uppercase;letter-spacing:0.05em;margin-bottom:6px">Recommended Action</div>
                    <div style="background:#052e16;border:1px solid #166534;border-radius:8px;padding:12px;font-size:13px;color:#86efac;line-height:1.6">%s</div>
                  </div>

                  <!-- Footer meta -->
                  <div style="border-top:1px solid #334155;padding-top:16px;display:flex;justify-content:space-between;align-items:center">
                    <div style="font-size:11px;color:#475569">
                      Incident ID: <span style="font-family:monospace;color:#94a3b8">%s</span>
                    </div>
                    <div style="font-size:11px;color:#475569">%s</div>
                  </div>

                </div>

                <div style="text-align:center;margin-top:16px;font-size:10px;color:#334155">
                  Sent by Industrial Safety Monitoring System · Do not reply
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                headerColor, tierLabel, timeStr,
                hazardType,
                isCritical ? "#fca5a5" : "#fdba74", severity.toUpperCase(),
                confidence * 100,
                headerColor, causalChain,
                recommendation,
                incidentId, timeStr
            );
    }
}
