package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.UssdTransaction;
import com.example.ussdGateway.repository.UssdTransactionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Processes a single chunk of parsed USSD log lines inside its own <b>independent</b> transaction.
 * <p>
 * Using {@code Propagation.REQUIRES_NEW} guarantees that each chunk is committed to the database
 * as soon as processing finishes — making the records immediately visible in report queries,
 * even while later chunks (or other files) are still being ingested. This also means that if a
 * later chunk fails, all previously committed chunks are <b>not</b> rolled back.
 * <p>
 * This class is intentionally a separate Spring bean (not a private method on
 * {@link LogIngestionService}) because Spring AOP transaction proxies only intercept calls made
 * through the proxy — i.e. public methods called from <em>outside</em> the bean.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionChunkProcessor {

    private static final int LOOKUP_CHUNK_SIZE = 1000;

    private final UssdTransactionRepository ussdTransactionRepository;
    private final UssdLogLineParser ussdLogLineParser;
    private final EntityManager entityManager;
    private final ReportRollupService reportRollupService;

    /**
     * Parses a chunk of raw log lines, deduplicates against the DB, and persists the results
     * — all inside a dedicated transaction that commits before this method returns.
     *
     * @param nodeIp       IP of the source USSD server (written into each record)
     * @param lines        raw log lines for this chunk
     * @param chunkNumber  ordinal chunk number (for progress logging)
     * @param totalLines   total lines read so far (for progress logging)
     * @return number of records saved in this chunk
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processChunk(String nodeIp, List<String> lines, int chunkNumber, long totalLines) {
        Map<String, UssdTransaction> parsed = ussdLogLineParser.parseLines(lines);
        if (parsed.isEmpty()) {
            return 0;
        }

        // Look up existing rows in sub-chunks to avoid huge IN (...) clauses
        Map<String, UssdTransaction> existingByCallId = new HashMap<>();
        List<String> callIds = new ArrayList<>(parsed.keySet());
        for (int i = 0; i < callIds.size(); i += LOOKUP_CHUNK_SIZE) {
            ussdTransactionRepository.findByCallIdIn(
                    callIds.subList(i, Math.min(i + LOOKUP_CHUNK_SIZE, callIds.size()))
            ).forEach(tx -> existingByCallId.put(tx.getCallId(), tx));
        }

        List<UssdTransaction> toSave = new ArrayList<>();
        for (UssdTransaction incoming : parsed.values()) {
            if (incoming.getCallId() == null || incoming.getCallId().isBlank()) {
                continue;
            }
            incoming.setNodeIp(nodeIp);
            incoming.truncateFields();

            UssdTransaction existing = existingByCallId.get(incoming.getCallId());
            if (existing != null) {
                mergeInto(existing, incoming);
                existing.truncateFields();
                toSave.add(existing);
            } else if (incoming.getTriggeredAt() != null) {
                toSave.add(incoming);
            } else if (incoming.getResponseTime() != null) {
                incoming.setTriggeredAt(incoming.getResponseTime());
                toSave.add(incoming);
            } else {
                log.warn("Dropping USSD transaction with callId {} - no timestamp could be determined",
                        incoming.getCallId());
            }
        }

        if (!toSave.isEmpty()) {
            try {
                ussdTransactionRepository.saveAll(toSave);
                // Flush the batch to the DB and detach all entities so they can be GC'd
                entityManager.flush();
            } catch (Exception e) {
                log.warn("Batch flush failed for chunk #{} ({}), falling back to individual inserts...",
                        chunkNumber, e.getMessage());
                entityManager.clear();
                List<UssdTransaction> recovered = new ArrayList<>();
                for (UssdTransaction tx : toSave) {
                    try {
                        tx.truncateFields();
                        ussdTransactionRepository.save(tx);
                        entityManager.flush();
                        recovered.add(tx);
                    } catch (Exception rowEx) {
                        log.error("Failed to persist USSD transaction callId {}: {}", tx.getCallId(), rowEx.getMessage());
                        entityManager.clear();
                    }
                }
                toSave = recovered;
            }
            entityManager.clear();
            if (!toSave.isEmpty()) {
                reportRollupService.markDirty(toSave.stream()
                        .map(UssdTransaction::getTriggeredAt)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()));
            }
        }

        if (chunkNumber % 10 == 0 || chunkNumber == 1) {
            log.info("  chunk #{}: {} lines processed so far, {} records in this chunk, {} parsed callIds",
                    chunkNumber, totalLines, toSave.size(), parsed.size());
        }

        return toSave.size();
    }

    /** Merges non-null fields from a newly-parsed record onto an already-persisted one. */
    private void mergeInto(UssdTransaction target, UssdTransaction incoming) {
        if (incoming.getMsisdn() != null) target.setMsisdn(incoming.getMsisdn());
        if (incoming.getTransactionId() != null) target.setTransactionId(incoming.getTransactionId());

        if ("SUCCESS".equalsIgnoreCase(incoming.getStatus())) {
            target.setStatus("SUCCESS");
            target.setErrorCode(null);
            target.setErrorMessage(null);
            target.setErrorReason(null);
        } else if (incoming.getStatus() != null) {
            target.setStatus(incoming.getStatus());
        }

        if (incoming.getLatencyMs() != null) target.setLatencyMs(incoming.getLatencyMs());
        if (incoming.getShortCode() != null) target.setShortCode(incoming.getShortCode());
        if (incoming.getProductName() != null) target.setProductName(incoming.getProductName());

        if (!"SUCCESS".equalsIgnoreCase(target.getStatus())) {
            if (incoming.getErrorCode() != null) target.setErrorCode(incoming.getErrorCode());
            if (incoming.getErrorReason() != null) target.setErrorReason(incoming.getErrorReason());

            if (incoming.getTibcoResponse() != null) {
                target.setTibcoResponse(incoming.getTibcoResponse());
            }
            if (incoming.getErrorMessage() != null) {
                target.setErrorMessage(incoming.getErrorMessage());
            } else if (target.getErrorMessage() == null && incoming.getTibcoResponse() != null) {
                target.setErrorMessage(incoming.getTibcoResponse());
            }
        }

        if (incoming.getRemoteUrl() != null) target.setRemoteUrl(incoming.getRemoteUrl());
        if (incoming.getSubscriberReply() != null) target.setSubscriberReply(incoming.getSubscriberReply());
        if (incoming.getResponseTime() != null) target.setResponseTime(incoming.getResponseTime());
        if (incoming.getServiceType() != null) target.setServiceType(incoming.getServiceType());
        if (incoming.getLang() != null) target.setLang(incoming.getLang());
        if (incoming.getNodeIp() != null) target.setNodeIp(incoming.getNodeIp());
        if (incoming.getCellId() != null) target.setCellId(incoming.getCellId());
        if (incoming.getVlr() != null) target.setVlr(incoming.getVlr());
        if (incoming.getChannel() != null) target.setChannel(incoming.getChannel());

        // Protect original entry timestamp: only set triggeredAt if target doesn't already have one
        if (target.getTriggeredAt() == null && incoming.getTriggeredAt() != null) {
            target.setTriggeredAt(incoming.getTriggeredAt());
        }
    }
}
