package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.Contact;
import com.example.ussdGateway.entity.ContactGroup;
import com.example.ussdGateway.entity.EmailConfig;
import com.example.ussdGateway.repository.ContactRepository;
import com.example.ussdGateway.repository.EmailConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Service;

import jakarta.activation.DataSource;
import jakarta.mail.util.ByteArrayDataSource;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.scheduling.annotation.Async;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailNotificationService {

    private final ContactRepository contactRepository;
    private final EmailConfigRepository emailConfigRepository;

    /**
     * Sends one alert email per contact group, with every member of that group in the "To" field,
     * using the first active EmailConfig stored in the database.
     *
     * @param bodyForGroup builds the HTML body for a group (e.g. to greet the group by name)
     */
    @Async
    public void sendAlertsToGroups(Collection<ContactGroup> groups, String subject, Function<ContactGroup, String> bodyForGroup) {
        if (groups == null || groups.isEmpty()) return;
        log.info("Dispatching Email alert to {} Contact Groups", groups.size());

        // 1. Resolve the active mail server from the database
        EmailConfig mailConfig = emailConfigRepository.findAll().stream()
                .filter(c -> c.getIsActive() != null && c.getIsActive())
                .findFirst()
                .orElse(null);

        if (mailConfig == null) {
            log.warn("No active Email Server configured. Skipping bulk email dispatch.");
            return;
        }

        // 2. Get the MailSender from cache or build dynamically if changed
        JavaMailSender sender = getMailSender(mailConfig);

        // 3. One message per group, addressed to all of its members
        int sent = 0;
        int failed = 0;

        for (ContactGroup group : groups) {
            String[] recipients = contactRepository.findByGroupId(group.getId()).stream()
                    .map(Contact::getEmail)
                    .filter(email -> email != null && !email.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toArray(String[]::new);

            if (recipients.length == 0) {
                log.warn("Contact group '{}' has no members with an email address. Skipping.", group.getName());
                continue;
            }

            try {
                MimeMessage mimeMessage = sender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "utf-8");
                helper.setFrom(mailConfig.getFromAddress());
                helper.setTo(recipients);
                helper.setSubject(subject);
                helper.setText(bodyForGroup.apply(group), true); // true indicates HTML content
                sender.send(mimeMessage);
                sent++;
                log.info("Email sent to group '{}' ({} recipients) via {}", group.getName(), recipients.length, mailConfig.getSmtpHost());
            } catch (Exception e) {
                failed++;
                log.error("Failed to send email to group '{}' via {}: {}", group.getName(), mailConfig.getSmtpHost(), e.getMessage());
            }
        }
        log.info("Email group dispatch complete: {} sent, {} failed", sent, failed);
    }

    private final Map<Long, JavaMailSender> senderCache = new ConcurrentHashMap<>();
    private final Map<Long, Integer> senderHashCache = new ConcurrentHashMap<>();

    private JavaMailSender getMailSender(EmailConfig config) {
        int configHash = java.util.Objects.hash(
            config.getSmtpHost(), config.getSmtpPort(), 
            config.getUsername(), config.getPassword(), 
            config.getUseTls()
        );

        Integer cachedHash = senderHashCache.get(config.getId());
        if (cachedHash != null && cachedHash.equals(configHash)) {
            return senderCache.get(config.getId());
        }

        JavaMailSender newSender = buildMailSender(config);
        senderCache.put(config.getId(), newSender);
        senderHashCache.put(config.getId(), configHash);
        return newSender;
    }

    /**
     * Builds a JavaMailSender dynamically from a database-stored EmailConfig.
     * This allows admins to change SMTP settings at runtime without restarting the application.
     */
    private JavaMailSender buildMailSender(EmailConfig config) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(config.getSmtpHost());
        mailSender.setPort(config.getSmtpPort());
        mailSender.setUsername(config.getUsername());
        mailSender.setPassword(config.getPassword()); // Decrypted by JPA AttributeConverter

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        // Deliver to the valid addresses even if one recipient in "To" is rejected by the server
        props.put("mail.smtp.sendpartial", "true");

        if (config.getUseTls() != null && config.getUseTls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        return mailSender;
    }

    /**
     * Sends a PDF + Excel report to all contacts in the specified contact groups.
     * Uses multipart MIME messages with file attachments.
     * <p>
     * This method is intentionally <b>synchronous</b> so callers (e.g. the
     * "Send Now" controller endpoint) receive accurate feedback about delivery
     * success/failure.  The scheduled runner already executes on its own
     * background thread, so it does not block the main event loop either.
     *
     * @param contactGroupIds list of contact group IDs to resolve recipients from
     * @param subject         email subject line
     * @param htmlBody        HTML email body
     * @param pdfAttachment   PDF report bytes (may be null — will be skipped)
     * @param xlsxAttachment  Excel report bytes (may be null — will be skipped)
     * @param pdfFilename     filename for the PDF attachment
     * @param xlsxFilename    filename for the Excel attachment
     * @return a short summary, e.g. "2 sent, 0 failed"
     * @throws IllegalStateException if no active email server is configured or
     *                               no contacts are found in the given groups
     */
    public String sendReportWithAttachments(List<Long> contactGroupIds, String subject, String htmlBody,
                                          byte[] pdfAttachment, byte[] xlsxAttachment,
                                          String pdfFilename, String xlsxFilename) {
        if (contactGroupIds == null || contactGroupIds.isEmpty()) {
            throw new IllegalStateException("No contact group IDs provided.");
        }
        log.info("Dispatching report email to {} Contact Group(s)", contactGroupIds.size());

        EmailConfig mailConfig = emailConfigRepository.findAll().stream()
                .filter(c -> c.getIsActive() != null && c.getIsActive())
                .findFirst()
                .orElse(null);

        if (mailConfig == null) {
            throw new IllegalStateException("No active Email Server configured. Please configure one under Mail Server settings.");
        }

        JavaMailSender sender = getMailSender(mailConfig);
        List<Contact> contacts = contactRepository.findByGroupIdIn(contactGroupIds);

        if (contacts.isEmpty()) {
            throw new IllegalStateException("No contacts with email addresses found in the selected group(s).");
        }

        log.info("Resolved {} contact(s) from {} group(s). PDF={} bytes, XLSX={} bytes",
                contacts.size(), contactGroupIds.size(),
                pdfAttachment != null ? pdfAttachment.length : 0,
                xlsxAttachment != null ? xlsxAttachment.length : 0);

        int sent = 0, failed = 0;
        String lastError = null;

        for (Contact contact : contacts) {
            if (contact.getEmail() != null && !contact.getEmail().isBlank()) {
                try {
                    MimeMessage mimeMessage = sender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "utf-8");
                    helper.setFrom(mailConfig.getFromAddress());
                    helper.setTo(contact.getEmail());
                    helper.setSubject(subject);
                    helper.setText(htmlBody, true);

                    if (pdfAttachment != null && pdfAttachment.length > 0) {
                        DataSource pdfDs = new ByteArrayDataSource(pdfAttachment, "application/pdf");
                        helper.addAttachment(pdfFilename != null ? pdfFilename : "report.pdf", pdfDs);
                    }
                    if (xlsxAttachment != null && xlsxAttachment.length > 0) {
                        DataSource xlsxDs = new ByteArrayDataSource(xlsxAttachment,
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                        helper.addAttachment(xlsxFilename != null ? xlsxFilename : "report.xlsx", xlsxDs);
                    }

                    sender.send(mimeMessage);
                    sent++;
                    log.info("Report email with attachments sent to {}", contact.getEmail());
                } catch (Exception e) {
                    failed++;
                    lastError = e.getMessage();
                    log.error("Failed to send report email to {}: {}", contact.getEmail(), e.getMessage(), e);
                }
            }
        }

        String summary = sent + " sent, " + failed + " failed";
        log.info("Report email dispatch complete: {}", summary);

        if (sent == 0 && failed > 0) {
            throw new RuntimeException("All emails failed to send. Last error: " + lastError);
        }
        return summary;
    }
}
