package org.example.incident.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.example.incident.persistence.IncidentRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
public class MailIncidentNotifier {

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;
    private final String to;
    private final String subjectPrefix;
    private final int retryAttempts;
    private final long retryDelayMs;

    public MailIncidentNotifier(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${aiops.notification.email.enabled:false}") boolean enabled,
            @Value("${aiops.notification.email.from:}") String from,
            @Value("${aiops.notification.email.to:}") String to,
            @Value("${aiops.notification.email.subject-prefix:[AIOps]}") String subjectPrefix,
            @Value("${aiops.notification.email.retry-attempts:3}") int retryAttempts,
            @Value("${aiops.notification.email.retry-delay-ms:1000}") long retryDelayMs) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.enabled = enabled;
        this.from = from;
        this.to = to;
        this.subjectPrefix = subjectPrefix;
        this.retryAttempts = Math.max(1, retryAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
    }

    public void manualRequired(IncidentRecord incident, String reason) {
        String title = "需要人工介入 - " + incident.getServiceId();
        String plainText = "AIOps 需要人工介入\n\n"
                + "事件 ID: " + incident.getId() + "\n"
                + "服务: " + incident.getServiceId() + "\n"
                + "严重级别: " + incident.getSeverity() + "\n"
                + "症状: " + incident.getTitle() + "\n"
                + "原因: " + reason;
        send(title, plainText, renderHtml(
                "ACTION REQUIRED", "需要人工介入", "自动化处理已停止，请运维人员接管。",
                "#b54708", "#fff4e5", incident, reason));
    }

    public void resolved(IncidentRecord incident) {
        String title = "自动修复已恢复 - " + incident.getServiceId();
        String plainText = "AIOps 自动修复已通过健康检查\n\n"
                + "事件 ID: " + incident.getId() + "\n"
                + "服务: " + incident.getServiceId() + "\n"
                + "状态: RESOLVED";
        send(title, plainText, renderHtml(
                "RECOVERY VERIFIED", "自动修复已恢复", "服务已通过自动修复后的健康检查。",
                "#027a48", "#ecfdf3", incident, "验证结果：健康检查通过"));
    }

    private void send(String subject, String plainText, String htmlText) {
        if (!enabled) {
            return;
        }
        if (mailSender == null) {
            throw new IllegalStateException("Incident email is enabled but SMTP is not configured");
        }

        String[] recipients = Arrays.stream(to.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
        if (recipients.length == 0) {
            throw new IllegalStateException("Incident email is enabled but AIOPS_EMAIL_TO is blank");
        }

        MimeMessage message;
        try {
            message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, true, StandardCharsets.UTF_8.name());
            if (!from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(recipients);
            helper.setSubject(subjectPrefix + " " + subject);
            helper.setText(plainText, htmlText);
        } catch (MessagingException ex) {
            throw new IllegalStateException("Incident email message could not be created", ex);
        }

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                mailSender.send(message);
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (attempt < retryAttempts && retryDelayMs > 0) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Incident email notification was interrupted", interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException("Incident email notification failed after retries", lastFailure);
    }

    private String renderHtml(
            String eyebrow,
            String title,
            String intro,
            String accent,
            String accentBackground,
            IncidentRecord incident,
            String detail) {
        String safeId = escapeHtml(incident.getId());
        String safeService = escapeHtml(incident.getServiceId());
        String safeSeverity = escapeHtml(incident.getSeverity());
        String safeSource = escapeHtml(incident.getSource());
        String safeTitle = escapeHtml(incident.getTitle());
        String safeDetail = escapeHtml(detail);
        String safeCreatedAt = escapeHtml(incident.getCreatedAt());

        return """
                <!doctype html>
                <html lang="zh-CN">
                <body style="margin:0;padding:0;background:#f2efe9;color:#202124;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',Arial,sans-serif;">
                  <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="width:100%%;background:#f2efe9;padding:28px 12px;">
                    <tr><td align="center">
                      <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="width:100%%;max-width:680px;background:#ffffff;border:1px solid #e6e0d7;border-radius:18px;overflow:hidden;box-shadow:0 12px 32px rgba(41,35,28,.10);">
                        <tr>
                          <td style="padding:26px 30px 24px;background:#17191d;color:#ffffff;">
                            <div style="font-size:12px;letter-spacing:2px;color:#f5a524;font-weight:700;">AIOPS / OPERATIONS CONTROL</div>
                            <div style="margin-top:26px;font-size:12px;letter-spacing:1.5px;color:#a9adb5;font-weight:700;">%s</div>
                            <div style="margin-top:7px;font-size:30px;line-height:1.2;font-weight:800;">%s</div>
                            <div style="margin-top:12px;color:#d5d8de;font-size:15px;line-height:1.65;">%s</div>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:26px 30px 30px;">
                            <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="width:100%%;border-collapse:collapse;">
                              <tr>
                                <td style="width:50%%;padding:0 14px 20px 0;vertical-align:top;">
                                  <div style="font-size:12px;color:#77736c;letter-spacing:.5px;">服务</div>
                                  <div style="margin-top:6px;font-size:21px;font-weight:800;color:#202124;word-break:break-word;">%s</div>
                                </td>
                                <td style="width:50%%;padding:0 0 20px 14px;vertical-align:top;">
                                  <div style="font-size:12px;color:#77736c;letter-spacing:.5px;">严重级别</div>
                                  <div style="margin-top:7px;"><span style="display:inline-block;padding:5px 10px;border-radius:999px;background:%s;color:%s;font-size:13px;font-weight:800;">%s</span></div>
                                </td>
                              </tr>
                              <tr>
                                <td style="padding:16px 14px 16px 0;border-top:1px solid #eeeae4;vertical-align:top;">
                                  <div style="font-size:12px;color:#77736c;">事件 ID</div>
                                  <div style="margin-top:6px;font-size:13px;color:#3f4146;word-break:break-all;">%s</div>
                                </td>
                                <td style="padding:16px 0 16px 14px;border-top:1px solid #eeeae4;vertical-align:top;">
                                  <div style="font-size:12px;color:#77736c;">事件来源</div>
                                  <div style="margin-top:6px;font-size:13px;color:#3f4146;">%s</div>
                                </td>
                              </tr>
                              <tr>
                                <td colspan="2" style="padding:16px 0;border-top:1px solid #eeeae4;vertical-align:top;">
                                  <div style="font-size:12px;color:#77736c;">告警症状</div>
                                  <div style="margin-top:7px;font-size:15px;line-height:1.6;font-weight:700;color:#292b30;">%s</div>
                                </td>
                              </tr>
                            </table>
                            <div style="margin-top:8px;padding:16px 18px;background:%s;border-left:4px solid %s;border-radius:10px;">
                              <div style="font-size:12px;letter-spacing:.5px;color:%s;font-weight:800;">处理信息</div>
                              <div style="margin-top:7px;font-size:14px;line-height:1.65;color:#3f4146;">%s</div>
                            </div>
                            <div style="margin-top:22px;font-size:12px;line-height:1.6;color:#99958e;">创建时间：%s</div>
                          </td>
                        </tr>
                        <tr>
                          <td style="padding:18px 30px;background:#faf9f6;border-top:1px solid #eeeae4;color:#8a867f;font-size:12px;line-height:1.6;">
                            This message was generated by AIOps Autonomous Operations.<br>
                            请不要直接回复此邮件。
                          </td>
                        </tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(
                escapeHtml(eyebrow), escapeHtml(title), escapeHtml(intro), safeService,
                accentBackground, accent, safeSeverity, safeId, safeSource, safeTitle,
                accentBackground, accent, accent, safeDetail, safeCreatedAt);
    }

    private String escapeHtml(Object value) {
        return String.valueOf(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
