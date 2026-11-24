package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.UssdTransaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless parser for the Monolog-format TIBCO USSD log lines, e.g.:
 * {@code [2026-03-20 17:09:11] local.INFO: TibcoSubscription USSD ENTRY: {json}}
 * <p>
 * A single USSD call is spread across several log lines correlated by {@code callID}
 * (or, for the final USSD response line, by a {@code callID} field embedded in its JSON body).
 * This parser groups those lines into one {@link UssdTransaction} per call for the caller to upsert.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UssdLogLineParser {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // [timestamp] channel.LEVEL: message
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})]\\s+\\S+:\\s+(.*)$");

    // <callID> <msisdn> HTTP request: URL:<url> [Body:<anything>]
    private static final Pattern HTTP_REQUEST_PATTERN = Pattern.compile(
            "^(\\S+)\\s+(\\S+)\\s+HTTP request:\\s*URL:\\s*(\\S+?)(?:\\s+Body:\\s*(\\{.*))?$");

    // <callID> <msisdn> HTTP response: <anything>
    private static final Pattern HTTP_RESPONSE_PATTERN = Pattern.compile(
            "^(\\S+)\\s+(\\S+)\\s+HTTP response:\\s*(.+)$");

    // serviceUrlId:17 69bd6317ba733c3d09d3e3c3 249123078460 *200*2# New USSD DIRECT subscription
    private static final Pattern SERVICE_URL_PATTERN = Pattern.compile(
            "^serviceUrlId:\\d+\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)(?:\\s+(.*))?$");

    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("(?i)URL:\\s*(\\S+)");

    private static final String ENTRY_PREFIX = "TibcoSubscription USSD ENTRY: ";
    private static final String USSD_RESPONSE_PREFIX = "USSD response: ";

    /** Regex fallbacks: extract values from raw text when JSON parsing fails (e.g. truncated logs). */
    private static final Pattern REMOTE_URL_REGEX = Pattern.compile("\"remoteURL\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CALL_ID_REGEX = Pattern.compile("\"callID\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MSISDN_REGEX = Pattern.compile("\"msisdn\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SHORT_CODE_USSDSTR_REGEX = Pattern.compile("\"ussdstr\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SHORT_CODE_ORIGINAL_REGEX = Pattern.compile("\"originalRequest\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern BODY_SHORTCODE_REGEX = Pattern.compile("\"(?:ShortCode|short_code|shortCode)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"|\"value\"\\s*:\\s*\"(\\*\\d+[0-9*#]*#)\"");
    private static final Pattern SERVICE_TYPE_REGEX = Pattern.compile("\"serviceType\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern LANG_REGEX = Pattern.compile("\"lang\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REASON_REGEX = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RESULT_REGEX = Pattern.compile("\"result\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MSG_REGEX = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RESPONSE_MSG_REGEX = Pattern.compile("\"responseMessage\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TXN_ID_REGEX = Pattern.compile("\"transactionId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CODE_REGEX = Pattern.compile("\"(?:code|statusDetails_code)\"\\s*:\\s*\"([^\"]+)\"");

    private final ObjectMapper objectMapper;

    /**
     * Parse a batch of raw log lines (already newline-split) into one {@link UssdTransaction}
     * per distinct callID encountered. Fields are merged across all matching lines found for
     * that callID within this batch. Unparseable/irrelevant lines are skipped without failing
     * the whole batch.
     */
    public Map<String, UssdTransaction> parseLines(List<String> lines) {
        Map<String, UssdTransaction> transactions = new LinkedHashMap<>();
        Map<String, LocalDateTime> entryTimestamps = new HashMap<>();
        String pendingHttpRespCallId = null;
        String lastSeenCallId = null;

        for (String line : lines) {
            try {
                Matcher lineMatcher = LINE_PATTERN.matcher(line);
                if (!lineMatcher.matches()) {
                    // Multi-line continuation of an HTTP response body (e.g. multi-line HTML error pages)
                    if (pendingHttpRespCallId != null && !line.isBlank()) {
                        UssdTransaction tx = transactions.get(pendingHttpRespCallId);
                        if (tx != null) {
                            String trimmed = line.trim();
                            String current = tx.getTibcoResponse();
                            String updated = cleanResponseBody((current != null ? current : "") + " " + trimmed);
                            tx.setTibcoResponse(updated);
                            tx.setErrorMessage(updated);
                            updateErrorClassification(tx, updated);
                        }
                    }
                    continue;
                }

                String lineCallId = parseLine(lineMatcher, transactions, entryTimestamps, lastSeenCallId);
                if (lineCallId != null) {
                    lastSeenCallId = lineCallId;
                }
                if (lineCallId != null && line.contains("HTTP response:")) {
                    pendingHttpRespCallId = lineCallId;
                } else if (!line.contains("HTTP response:")) {
                    pendingHttpRespCallId = null;
                }
            } catch (Exception e) {
                log.warn("Skipping unparseable USSD log line: {}", e.getMessage());
            }
        }
        return transactions;
    }

    private String parseLine(Matcher lineMatcher, Map<String, UssdTransaction> transactions,
                             Map<String, LocalDateTime> entryTimestamps, String lastSeenCallId) {
        LocalDateTime timestamp = LocalDateTime.parse(lineMatcher.group(1), TIMESTAMP_FORMAT);
        String message = lineMatcher.group(2).trim();

        if (message.startsWith(ENTRY_PREFIX)) {
            return handleEntryLine(message.substring(ENTRY_PREFIX.length()).trim(), timestamp, transactions, entryTimestamps);
        }
        if (message.startsWith("serviceUrlId:")) {
            return handleServiceUrlLine(message, timestamp, transactions);
        }
        if (message.startsWith(USSD_RESPONSE_PREFIX)) {
            return handleUssdResponseLine(message.substring(USSD_RESPONSE_PREFIX.length()).trim(), timestamp, transactions, entryTimestamps);
        }
        if (message.contains("HTTP request:")) {
            return handleHttpRequestLineFlexible(message, timestamp, transactions, lastSeenCallId);
        }
        if (message.contains("HTTP response:")) {
            return handleHttpResponseLineFlexible(message, timestamp, transactions, lastSeenCallId);
        }
        return null;
    }

    private String handleEntryLine(String json, LocalDateTime timestamp,
                                  Map<String, UssdTransaction> transactions,
                                  Map<String, LocalDateTime> entryTimestamps) {
        JsonNode node = parseJson(json);
        if (node == null) {
            // JSON parsing failed (truncated/malformed) — try regex fallback for critical fields
            handleEntryLineFallback(json, timestamp, transactions, entryTimestamps);
            return extractRegex(CALL_ID_REGEX, json);
        }
        String callId = textOrNull(node, "callID");
        if (callId == null) {
            return null;
        }
        UssdTransaction tx = getOrCreate(transactions, callId);
        tx.setMsisdn(textOrNull(node, "msisdn"));
        String menuCode = textOrNull(node, "ussdstr");
        String shortCode = menuCode != null ? menuCode : textOrNull(node, "originalRequest");
        tx.setShortCode(shortCode);
        if (tx.getProductName() == null) {
            tx.setProductName(shortCode);
        }
        String serviceType = textOrNull(node, "serviceType");
        tx.setServiceType(serviceType == null || serviceType.isEmpty() ? null
                : Character.toUpperCase(serviceType.charAt(0)) + serviceType.substring(1));
        tx.setLang(textOrNull(node, "lang"));
        tx.setChannel(node.hasNonNull("Direct") ? "DirectSubscription" : (menuCode != null ? "MenuSubscription" : null));
        String remoteUrl = textOrNull(node, "remoteURL");
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            String cleaned = cleanUrl(remoteUrl);
            if (!cleaned.contains("127.0.0.1") && !cleaned.contains("localhost")) {
                tx.setRemoteUrl(cleaned);
            }
        }
        tx.setTriggeredAt(timestamp);
        entryTimestamps.put(callId, timestamp);
        return callId;
    }

    private String handleServiceUrlLine(String message, LocalDateTime timestamp,
                                        Map<String, UssdTransaction> transactions) {
        Matcher m = SERVICE_URL_PATTERN.matcher(message);
        if (m.matches()) {
            String callId = m.group(1);
            String msisdn = m.group(2);
            String shortCode = m.group(3);
            UssdTransaction tx = getOrCreate(transactions, callId);
            if (tx.getTriggeredAt() == null) {
                tx.setTriggeredAt(timestamp);
            }
            if (tx.getMsisdn() == null && msisdn != null && !msisdn.isBlank()) {
                tx.setMsisdn(msisdn);
            }
            if (shortCode != null && !shortCode.isBlank()) {
                if (tx.getShortCode() == null || tx.getShortCode().isBlank()) {
                    tx.setShortCode(shortCode);
                }
                if (tx.getProductName() == null || tx.getProductName().isBlank()) {
                    tx.setProductName(shortCode);
                }
            }
            return callId;
        }
        return null;
    }

    private String handleUssdResponseLine(String json, LocalDateTime timestamp,
                                         Map<String, UssdTransaction> transactions,
                                         Map<String, LocalDateTime> entryTimestamps) {
        List<JsonNode> nodes = parseJsonValues(json);
        if (nodes.isEmpty()) {
            return null;
        }
        JsonNode node = nodes.get(0);
        String callId = null;
        String msisdn = null;
        for (JsonNode n : nodes) {
            if (callId == null) callId = textOrNull(n, "callID");
            if (msisdn == null) msisdn = textOrNull(n, "msisdn");
        }
        if (callId == null) {
            return null;
        }
        UssdTransaction tx = getOrCreate(transactions, callId);
        if (tx.getMsisdn() == null) {
            tx.setMsisdn(msisdn);
        }

        for (JsonNode n : nodes) {
            if (tx.getShortCode() == null || tx.getShortCode().isBlank()) {
                String menuCode = textOrNull(n, "ussdstr");
                if (menuCode == null) menuCode = textOrNull(n, "originalRequest");
                if (menuCode == null) menuCode = textOrNull(n, "ussd_str");
                if (menuCode != null && !menuCode.isBlank()) {
                    tx.setShortCode(menuCode);
                    if (tx.getProductName() == null) tx.setProductName(menuCode);
                }
            }
            JsonNode shortProps = n.path("UssdShortCodesProperties");
            if (!shortProps.isMissingNode()) {
                String sc = textOrNull(shortProps, "ussd_str");
                if (sc == null) sc = textOrNull(shortProps, "ussdstr");
                if (sc != null && !sc.isBlank() && (tx.getShortCode() == null || tx.getShortCode().isBlank())) {
                    tx.setShortCode(sc);
                }
                String desc = textOrNull(shortProps, "Desc");
                if (desc != null && !desc.isBlank()) {
                    tx.setProductName(desc);
                }
                String serviceUrl = textOrNull(shortProps, "ServiceURL");
                if (serviceUrl != null && !serviceUrl.isBlank()) {
                    String cleaned = cleanUrl(serviceUrl);
                    if (!cleaned.contains("127.0.0.1") && !cleaned.contains("localhost") && tx.getRemoteUrl() == null) {
                        tx.setRemoteUrl(cleaned);
                    }
                }
            }
        }

        String transactionId = textOrNull(node, "transactionId");
        if (transactionId != null) {
            tx.setTransactionId(transactionId);
        }
        Integer responseCode = intOrNull(node, "responseCode");
        String statusCode = textOrNull(node, "statusDetails_code");
        if (statusCode != null) {
            tx.setErrorCode(statusCode);
        } else if (responseCode != null && (responseCode < 200 || responseCode >= 300) && tx.getErrorCode() == null) {
            tx.setErrorCode("HTTP_" + responseCode);
        }

        String status = resolveStatus(textOrNull(node, "statusDetails_status"), responseCode);
        if (status != null && tx.getStatus() == null) {
            tx.setStatus(status);
        } else if ("FAILED".equals(status)) {
            tx.setStatus("FAILED");
        }

        String result = textOrNull(node, "result");
        if (result != null && !result.isBlank()) {
            tx.setSubscriberReply(result);
        }
        String responseMessage = textOrNull(node, "responseMessage");
        if (responseMessage != null && !responseMessage.isBlank()) {
            if (tx.getSubscriberReply() == null) {
                tx.setSubscriberReply(responseMessage);
            }
            if (tx.getErrorReason() == null) {
                tx.setErrorReason(responseMessage);
            }
            if (tx.getTibcoResponse() == null) {
                tx.setTibcoResponse(responseMessage);
            }
            if (tx.getErrorMessage() == null) {
                tx.setErrorMessage(responseMessage);
            }
        }

        if ("FAILED".equals(tx.getStatus())) {
            if (tx.getErrorMessage() == null) {
                tx.setErrorMessage(tx.getTibcoResponse() != null ? tx.getTibcoResponse()
                        : (tx.getErrorReason() != null ? tx.getErrorReason()
                        : (result != null ? result : "Request Failed")));
            }
        } else if ("SUCCESS".equals(tx.getStatus())) {
            tx.setErrorCode(null);
            tx.setErrorMessage(null);
            tx.setErrorReason(null);
        }

        LocalDateTime entryTs = entryTimestamps.get(callId);
        if (entryTs == null) {
            entryTs = tx.getTriggeredAt();
        }
        if (entryTs != null) {
            tx.setLatencyMs(Duration.between(entryTs, timestamp).toMillis());
        }
        if (tx.getTriggeredAt() == null) {
            tx.setTriggeredAt(timestamp);
        }
        if (tx.getResponseTime() == null) {
            tx.setResponseTime(timestamp);
        }
        return callId;
    }

    private String handleHttpRequestLineFlexible(String message, LocalDateTime timestamp,
                                                  Map<String, UssdTransaction> transactions,
                                                  String lastSeenCallId) {
        int reqIdx = message.indexOf("HTTP request:");
        String before = message.substring(0, reqIdx).trim();
        String callId = null;
        String msisdn = null;
        if (!before.isEmpty()) {
            String[] tokens = before.split("\\s+");
            if (tokens.length >= 1 && !tokens[0].isBlank()) {
                callId = tokens[0];
            }
            if (tokens.length >= 2 && !tokens[1].isBlank()) {
                msisdn = tokens[1];
            }
        }
        if (callId == null || callId.isBlank()) {
            callId = lastSeenCallId;
        }
        if (callId == null) {
            return null;
        }

        String after = message.substring(reqIdx + "HTTP request:".length()).trim();
        String url = null;
        String body = null;

        Matcher urlMatcher = HTTP_URL_PATTERN.matcher(after);
        if (urlMatcher.find()) {
            url = urlMatcher.group(1).trim();
        }

        int bodyIdx = after.toLowerCase().indexOf("body:");
        if (bodyIdx != -1) {
            body = after.substring(bodyIdx + 5).trim();
        }

        handleHttpRequestLine(callId, msisdn, url, body, timestamp, transactions);
        return callId;
    }

    private String handleHttpResponseLineFlexible(String message, LocalDateTime timestamp,
                                                   Map<String, UssdTransaction> transactions,
                                                   String lastSeenCallId) {
        int respIdx = message.indexOf("HTTP response:");
        String before = message.substring(0, respIdx).trim();
        String callId = null;
        String msisdn = null;
        if (!before.isEmpty()) {
            String[] tokens = before.split("\\s+");
            if (tokens.length >= 1 && !tokens[0].isBlank()) {
                callId = tokens[0];
            }
            if (tokens.length >= 2 && !tokens[1].isBlank()) {
                msisdn = tokens[1];
            }
        }
        if (callId == null || callId.isBlank()) {
            callId = lastSeenCallId;
        }
        if (callId == null) {
            return null;
        }

        String rawResponse = message.substring(respIdx + "HTTP response:".length()).trim();
        handleHttpResponseLine(callId, msisdn, rawResponse, timestamp, transactions);
        return callId;
    }

    private void handleHttpRequestLine(String callId, String msisdn, String url, String json,
                                        LocalDateTime timestamp,
                                        Map<String, UssdTransaction> transactions) {
        UssdTransaction tx = getOrCreate(transactions, callId);
        if (tx.getTriggeredAt() == null) {
            tx.setTriggeredAt(timestamp);
        }
        if (tx.getMsisdn() == null && msisdn != null && !msisdn.isBlank()) {
            tx.setMsisdn(msisdn);
        }
        if (url != null && !url.isBlank()) {
            tx.setRemoteUrl(cleanUrl(url));
        }
        if (json != null && !json.isBlank()) {
            JsonNode node = parseJson(json);
            if (node != null) {
                JsonNode chCharacteristics = node.at("/relatedChannel/0/channel/characteristic");
                if (chCharacteristics.isArray()) {
                    for (JsonNode c : chCharacteristics) {
                        String name = textOrNull(c, "name");
                        String value = textOrNull(c, "value");
                        if ("ShortCode".equalsIgnoreCase(name) && value != null && !value.isBlank()) {
                            if (tx.getShortCode() == null || tx.getShortCode().isBlank()) {
                                tx.setShortCode(value);
                            }
                            if (tx.getProductName() == null || tx.getProductName().isBlank()) {
                                tx.setProductName(value);
                            }
                        }
                    }
                }
                JsonNode characteristics = node.at("/productOrderItem/0/relatedParty/0/party/characteristic");
                if (characteristics.isArray()) {
                    for (JsonNode c : characteristics) {
                        String name = textOrNull(c, "name");
                        String value = textOrNull(c, "value");
                        if (name == null || value == null) {
                            continue;
                        }
                        if ("vlrNumber".equalsIgnoreCase(name)) {
                            tx.setVlr(value);
                        } else if ("cellid".equalsIgnoreCase(name)) {
                            tx.setCellId(value);
                        } else if ("ShortCode".equalsIgnoreCase(name) && (tx.getShortCode() == null || tx.getShortCode().isBlank())) {
                            tx.setShortCode(value);
                            if (tx.getProductName() == null || tx.getProductName().isBlank()) {
                                tx.setProductName(value);
                            }
                        }
                    }
                }
            }
            if (tx.getShortCode() == null || tx.getShortCode().isBlank()) {
                String sc = extractRegex(BODY_SHORTCODE_REGEX, json);
                if (sc != null && !sc.isBlank()) {
                    tx.setShortCode(sc);
                    if (tx.getProductName() == null || tx.getProductName().isBlank()) {
                        tx.setProductName(sc);
                    }
                }
            }
        }
    }

    private void handleHttpResponseLine(String callId, String msisdn, String rawResponse,
                                         LocalDateTime timestamp,
                                         Map<String, UssdTransaction> transactions) {
        UssdTransaction tx = getOrCreate(transactions, callId);
        tx.setResponseTime(timestamp);
        if (tx.getMsisdn() == null && msisdn != null && !msisdn.isBlank()) {
            tx.setMsisdn(msisdn);
        }
        if (tx.getTriggeredAt() != null && tx.getLatencyMs() == null) {
            tx.setLatencyMs(Duration.between(tx.getTriggeredAt(), timestamp).toMillis());
        }

        String cleaned = cleanResponseBody(rawResponse);
        if (cleaned == null || cleaned.isBlank()) {
            return;
        }

        // Store exact TIBCO HTTP response body
        tx.setTibcoResponse(cleaned);

        int jsonStart = cleaned.indexOf('{');
        JsonNode node = jsonStart >= 0 ? parseJson(cleaned.substring(jsonStart)) : null;
        if (node != null) {
            String transactionId = node.at("/header/transaction/transactionId").asText(null);
            if (transactionId != null) {
                tx.setTransactionId(transactionId);
            }
            String statusMsg = null;
            JsonNode statusDetails = node.at("/header/statusDetails");
            if (!statusDetails.isMissingNode()) {
                String code = textOrNull(statusDetails, "code");
                String st = textOrNull(statusDetails, "status");
                statusMsg = textOrNull(statusDetails, "message");
                if (code != null) tx.setErrorCode(code);
                if (st != null) tx.setStatus("SUCCESS".equalsIgnoreCase(st) ? "SUCCESS" : "FAILED");
                if (statusMsg != null && !statusMsg.isBlank()) tx.setErrorReason(statusMsg);
            }

            JsonNode errorMessageNode = node.at("/responseBody/productOrderErrorMessage");
            if (!errorMessageNode.isMissingNode()) {
                String reason = textOrNull(errorMessageNode, "reason");
                String msg = textOrNull(errorMessageNode, "message");
                if (reason != null && !reason.isBlank()) {
                    tx.setErrorReason(reason);
                } else if (msg != null && !msg.isBlank() && tx.getErrorReason() == null) {
                    tx.setErrorReason(msg);
                }
                if (msg != null && !msg.isBlank()) {
                    tx.setErrorMessage(msg);
                    tx.setSubscriberReply(msg);
                } else if (reason != null && !reason.isBlank()) {
                    tx.setErrorMessage(reason);
                }
                tx.setStatus("FAILED");
            }

            // Extract explicit "reason" / "Reason" / "errorReason" anywhere in the JSON response
            String explicitReason = extractReasonFromJson(node);
            if (explicitReason != null && !explicitReason.isBlank()) {
                tx.setErrorReason(explicitReason);
            }

            Integer respCode = intOrNull(node, "responseCode");
            String messageField = textOrNull(node, "message");
            if (messageField != null && !messageField.isBlank() && tx.getErrorReason() == null) {
                tx.setErrorReason(messageField);
            }
            String responseMsgField = textOrNull(node, "responseMessage");
            if (responseMsgField != null && !responseMsgField.isBlank()) {
                if (tx.getSubscriberReply() == null) {
                    tx.setSubscriberReply(responseMsgField);
                }
                if (tx.getErrorReason() == null) {
                    tx.setErrorReason(responseMsgField);
                }
            }
            String resultField = textOrNull(node, "result");
            if (resultField != null && !resultField.isBlank() && tx.getSubscriberReply() == null) {
                tx.setSubscriberReply(resultField);
            }

            if (respCode != null && respCode >= 400) {
                tx.setStatus("FAILED");
                if (tx.getErrorCode() == null) {
                    tx.setErrorCode("HTTP_" + respCode);
                }
            }
            String statusField = textOrNull(node, "status");
            if (statusField != null && !statusField.isBlank()) {
                if ("500".equals(statusField) || "504".equals(statusField) || "400".equals(statusField) || "404".equals(statusField)) {
                    tx.setStatus("FAILED");
                    tx.setErrorCode("HTTP_" + statusField);
                }
            }
            if (tx.getErrorMessage() == null) {
                if (statusMsg != null && !statusMsg.isBlank()) {
                    tx.setErrorMessage(statusMsg);
                } else if (explicitReason != null && !explicitReason.isBlank()) {
                    tx.setErrorMessage(explicitReason);
                } else if (messageField != null && !messageField.isBlank()) {
                    tx.setErrorMessage(messageField);
                }
            }
            updateErrorClassification(tx, cleaned);
        } else {
            // Regex fallback for truncated or non-standard JSON before marking as generic HTML error
            String reason = extractRegex(REASON_REGEX, cleaned);
            String msg = extractRegex(MSG_REGEX, cleaned);
            if (msg == null) msg = extractRegex(RESPONSE_MSG_REGEX, cleaned);
            String result = extractRegex(RESULT_REGEX, cleaned);
            String txnId = extractRegex(TXN_ID_REGEX, cleaned);
            String code = extractRegex(CODE_REGEX, cleaned);

            if (txnId != null) tx.setTransactionId(txnId);
            if (reason != null && !reason.isBlank()) tx.setErrorReason(reason);
            if (msg != null && !msg.isBlank()) {
                if (tx.getErrorReason() == null) tx.setErrorReason(msg);
                tx.setErrorMessage(msg);
                tx.setSubscriberReply(msg);
            }
            if (result != null && !result.isBlank() && tx.getSubscriberReply() == null) {
                tx.setSubscriberReply(result);
            }

            tx.setStatus("FAILED");
            if (tx.getErrorCode() == null) {
                tx.setErrorCode(code != null ? code : (cleaned.contains("<html") ? "HTML_ERROR" : "FAILED"));
            }
            if (tx.getErrorMessage() == null) {
                tx.setErrorMessage(cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned);
            }
            updateErrorClassification(tx, cleaned);
        }
    }

    private String extractReasonFromJson(JsonNode node) {
        if (node == null) return null;
        JsonNode rNode = node.at("/responseBody/productOrderErrorMessage/reason");
        if (!rNode.isMissingNode() && !rNode.isNull() && !rNode.asText().isBlank()) {
            return rNode.asText().trim();
        }
        JsonNode found = node.findValue("reason");
        if (found != null && !found.isNull() && !found.asText().isBlank()) {
            return found.asText().trim();
        }
        found = node.findValue("Reason");
        if (found != null && !found.isNull() && !found.asText().isBlank()) {
            return found.asText().trim();
        }
        found = node.findValue("errorReason");
        if (found != null && !found.isNull() && !found.asText().isBlank()) {
            return found.asText().trim();
        }
        return null;
    }

    private void updateErrorClassification(UssdTransaction tx, String cleaned) {
        if (cleaned == null) return;
        boolean hasExplicitReason = tx.getErrorReason() != null && !tx.getErrorReason().isBlank();
        if (cleaned.contains("Read timed out")) {
            if (tx.getErrorCode() == null) tx.setErrorCode("READ_TIMEOUT");
            if (!hasExplicitReason) tx.setErrorReason("Read timed out");
        } else if (cleaned.contains("timeout timer") || cleaned.contains("interrupted by timeout")) {
            if (tx.getErrorCode() == null) tx.setErrorCode("TIMEOUT");
            if (!hasExplicitReason) tx.setErrorReason("Target request interrupted by timeout timer");
        } else if (cleaned.contains("504") || cleaned.toLowerCase().contains("time-out")) {
            if (tx.getErrorCode() == null) tx.setErrorCode("HTTP_504");
            if (!hasExplicitReason) tx.setErrorReason("504 Gateway Time-out");
        } else if (cleaned.contains("Connection reset")) {
            if (tx.getErrorCode() == null) tx.setErrorCode("HTTP_500");
            if (!hasExplicitReason) tx.setErrorReason("Connection reset");
        } else if (cleaned.contains("failed to respond")) {
            if (tx.getErrorCode() == null) tx.setErrorCode("HTTP_500");
            if (!hasExplicitReason) tx.setErrorReason("Failed to respond");
        } else if (cleaned.contains("500") || cleaned.contains("Internal Server Error") || cleaned.contains("Request failed")) {
            if (tx.getErrorCode() == null) tx.setErrorCode("HTTP_500");
            if (!hasExplicitReason) tx.setErrorReason("500 Internal Server Error");
        } else if (cleaned.contains("404") || cleaned.contains("Not Found")) {
            if (tx.getErrorCode() == null) tx.setErrorCode("HTTP_404");
            if (!hasExplicitReason) tx.setErrorReason("404 Not Found");
        } else if (cleaned.contains("400") || cleaned.contains("Bad Request")) {
            if (tx.getErrorCode() == null) tx.setErrorCode("HTTP_400");
            if (!hasExplicitReason) tx.setErrorReason("400 Bad Request");
        } else if (!hasExplicitReason) {
            tx.setErrorReason(cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned);
        }
    }

    private String cleanUrl(String raw) {
        if (raw == null) return null;
        return raw.replace("\\/", "/").trim();
    }

    private String cleanResponseBody(String body) {
        if (body == null) return null;
        return body.replaceAll("\\s*\\r?\\n\\s*", " ").trim();
    }

    private String resolveStatus(String statusDetailsStatus, Integer responseCode) {
        if (statusDetailsStatus != null) {
            return "SUCCESS".equalsIgnoreCase(statusDetailsStatus) ? "SUCCESS" : "FAILED";
        }
        if (responseCode != null) {
            return (responseCode >= 200 && responseCode < 300) ? "SUCCESS" : "FAILED";
        }
        return null;
    }

    private UssdTransaction getOrCreate(Map<String, UssdTransaction> transactions, String callId) {
        return transactions.computeIfAbsent(callId, id -> {
            UssdTransaction tx = new UssdTransaction();
            tx.setCallId(id);
            return tx;
        });
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            // Attempt auto-repair for truncated JSON (e.g. missing closing braces or unclosed quotes)
            String repaired = tryRepairJson(json);
            if (repaired != null && !repaired.equals(json)) {
                try {
                    return objectMapper.readTree(repaired);
                } catch (Exception ignored) {}
            }
            log.debug("Failed to parse embedded JSON in USSD log line: {}", e.getMessage());
            return null;
        }
    }

    private String tryRepairJson(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (!s.startsWith("{") && !s.startsWith("[")) {
            return null;
        }
        // Count unescaped double quotes
        int quoteCount = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                quoteCount++;
            }
        }
        StringBuilder sb = new StringBuilder(s);
        if (quoteCount % 2 != 0) {
            sb.append('"');
        }
        // Count open vs closed curly braces
        int openBraces = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '{') openBraces++;
            else if (c == '}') openBraces--;
        }
        while (openBraces > 0) {
            sb.append('}');
            openBraces--;
        }
        return sb.toString();
    }

    private void handleEntryLineFallback(String raw, LocalDateTime timestamp,
                                          Map<String, UssdTransaction> transactions,
                                          Map<String, LocalDateTime> entryTimestamps) {
        String callId = extractRegex(CALL_ID_REGEX, raw);
        if (callId == null) return;

        UssdTransaction tx = getOrCreate(transactions, callId);
        String msisdn = extractRegex(MSISDN_REGEX, raw);
        if (msisdn != null) tx.setMsisdn(msisdn);

        String menuCode = extractRegex(SHORT_CODE_USSDSTR_REGEX, raw);
        String shortCode = menuCode != null ? menuCode : extractRegex(SHORT_CODE_ORIGINAL_REGEX, raw);
        if (shortCode != null) {
            tx.setShortCode(shortCode);
            if (tx.getProductName() == null) tx.setProductName(shortCode);
        }

        String serviceType = extractRegex(SERVICE_TYPE_REGEX, raw);
        if (serviceType != null && !serviceType.isEmpty()) {
            tx.setServiceType(Character.toUpperCase(serviceType.charAt(0)) + serviceType.substring(1));
        }

        String lang = extractRegex(LANG_REGEX, raw);
        if (lang != null) tx.setLang(lang);

        if (raw.contains("\"Direct\"")) {
            tx.setChannel("DirectSubscription");
        } else if (menuCode != null) {
            tx.setChannel("MenuSubscription");
        }

        String remoteUrl = extractRegex(REMOTE_URL_REGEX, raw);
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            String cleaned = cleanUrl(remoteUrl);
            if (!cleaned.contains("127.0.0.1") && !cleaned.contains("localhost")) {
                tx.setRemoteUrl(cleaned);
            }
        }

        tx.setTriggeredAt(timestamp);
        entryTimestamps.put(callId, timestamp);
    }

    private String extractRegex(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                String val = m.group(i);
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            }
        }
        return null;
    }

    /** Reads every top-level JSON value in the string (a line can hold several back to back). */
    private List<JsonNode> parseJsonValues(String json) {
        List<JsonNode> nodes = new ArrayList<>();
        try (MappingIterator<JsonNode> it = objectMapper.readerFor(JsonNode.class).readValues(json)) {
            while (it.hasNextValue()) {
                nodes.add(it.nextValue());
            }
        } catch (Exception e) {
            if (nodes.isEmpty()) {
                log.warn("Failed to parse embedded JSON in USSD log line: {}", e.getMessage());
            }
        }
        return nodes;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asInt();
    }
}
