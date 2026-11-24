package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.Contact;
import com.example.ussdGateway.entity.ContactGroup;
import com.example.ussdGateway.entity.SmppConfig;
import com.example.ussdGateway.repository.ContactRepository;
import com.example.ussdGateway.repository.SmppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsmpp.bean.*;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.SubmitSmResult;
import org.jsmpp.util.AbsoluteTimeFormatter;
import org.jsmpp.util.TimeFormatter;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Async;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsNotificationService {

    private final ContactRepository contactRepository;
    private final SmppConfigRepository smppConfigRepository;
    private final TimeFormatter timeFormatter = new AbsoluteTimeFormatter();

    /**
     * Sends an SMS alert to every contact in the given groups,
     * using the first active SmppConfig stored in the database.
     */
    @Async
    public void sendAlertsToGroups(Collection<ContactGroup> groups, String message) {
        if (groups == null || groups.isEmpty()) return;
        List<Long> groupIds = groups.stream().map(ContactGroup::getId).collect(Collectors.toList());
        log.info("Dispatching SMS (SMPP) alert to {} Contact Groups", groups.size());

        // 1. Resolve the active SMPP config from the database
        SmppConfig smppConfig = smppConfigRepository.findAll().stream()
                .filter(c -> c.getIsActive() != null && c.getIsActive())
                .findFirst()
                .orElse(null);

        if (smppConfig == null) {
            log.warn("No active SMPP account configured. Skipping bulk SMS dispatch.");
            return;
        }

        // 2. Open an SMPP session and dispatch messages
        SMPPSession session = null;
        try {
            session = createSmppSession(smppConfig);

            List<Contact> contacts = contactRepository.findByGroupIdIn(groupIds);
            int sent = 0;
            int failed = 0;

            for (Contact contact : contacts) {
                if (contact.getPhoneNumber() != null && !contact.getPhoneNumber().isBlank()) {
                    try {
                        SubmitSmResult result = session.submitShortMessage(
                                smppConfig.getSystemType() != null ? smppConfig.getSystemType() : "",
                                TypeOfNumber.INTERNATIONAL,
                                NumberingPlanIndicator.ISDN,
                                smppConfig.getSourceAddress() != null ? smppConfig.getSourceAddress() : "USSD-GW",
                                TypeOfNumber.INTERNATIONAL,
                                NumberingPlanIndicator.ISDN,
                                contact.getPhoneNumber(),
                                new ESMClass(),
                                (byte) 0,
                                (byte) 1,
                                timeFormatter.format(new Date()),
                                null,
                                new RegisteredDelivery(SMSCDeliveryReceipt.DEFAULT),
                                (byte) 0,
                                new GeneralDataCoding(Alphabet.ALPHA_DEFAULT, MessageClass.CLASS1, false),
                                (byte) 0,
                                message.getBytes()
                        );
                        String messageId = result.getMessageId();
                        sent++;
                        log.info("SMS sent to {} via SMPP. MessageId: {}", contact.getPhoneNumber(), messageId);
                    } catch (Exception e) {
                        failed++;
                        log.error("Failed to send SMS to {} via SMPP: {}", contact.getPhoneNumber(), e.getMessage());
                    }
                }
            }
            log.info("SMS bulk dispatch complete: {} sent, {} failed", sent, failed);

        } catch (Exception e) {
            log.error("Failed to establish SMPP session to {}:{} - {}", smppConfig.getSmppHost(), smppConfig.getSmppPort(), e.getMessage());
        } finally {
            if (session != null) {
                try {
                    session.unbindAndClose();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Creates and binds an SMPP session from the database-stored SmppConfig.
     */
    private SMPPSession createSmppSession(SmppConfig config) throws Exception {
        SMPPSession session = new SMPPSession();
        session.setTransactionTimer(10000L);

        session.connectAndBind(
                config.getSmppHost(),
                config.getSmppPort(),
                new BindParameter(
                        BindType.BIND_TX,
                        config.getSystemId(),
                        config.getPassword(), // Decrypted by JPA AttributeConverter
                        config.getSystemType() != null ? config.getSystemType() : "",
                        TypeOfNumber.UNKNOWN,
                        NumberingPlanIndicator.UNKNOWN,
                        null
                )
        );

        log.info("SMPP session established to {}:{} as {}", config.getSmppHost(), config.getSmppPort(), config.getSystemId());
        return session;
    }
}
