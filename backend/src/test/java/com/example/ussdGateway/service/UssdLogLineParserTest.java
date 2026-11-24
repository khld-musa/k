package com.example.ussdGateway.service;

import com.example.ussdGateway.entity.UssdTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UssdLogLineParserTest {

    private UssdLogLineParser parser;

    @BeforeEach
    void setUp() {
        parser = new UssdLogLineParser(new ObjectMapper());
    }

    /** Exact sample log block supplied for this feature - one full USSD call across 8 lines. */
    private static final String SAMPLE_LOG = """
            [2026-03-20 17:09:11] local.INFO: TibcoSubscription USSD ENTRY: {"callID":"69bd6317ba733c3d09d3e3c3","originalRequest":"*200*2#","remoteURL":"http:\\/\\/127.0.0.1:8888\\/api\\/ussd\\/subscription","menuID":"665dc2cf9036c54318a19116","virtualNetworkID":"65ad142ac1c5e4379c777f74","localAddress":{"numberingPlan":"ISDN_TELEPHONY","natureOfAddress":"INTERNATIONAL","globalTitleIndicator":"GLOBAL_TITLE_INCLUDES_TRANSLATION_TYPE_NUMBERING_PLAN_ENCODING_SCHEME_AND_NATURE_OF_ADDRESS","routingIndicator":"ROUTING_BASED_ON_DPC_AND_SSN","encodingScheme":1,"translationType":0,"digits":"2491250814487","pcPresent":false,"ssnPresent":true,"reservedForNationalUseBit":false,"signalingPointCode":0,"subsystemNumber":8},"remoteAddress":{"numberingPlan":"ISDN_TELEPHONY","natureOfAddress":"INTERNATIONAL","globalTitleIndicator":"GLOBAL_TITLE_INCLUDES_TRANSLATION_TYPE_NUMBERING_PLAN_ENCODING_SCHEME_AND_NATURE_OF_ADDRESS","routingIndicator":"ROUTING_BASED_ON_GLOBAL_TITLE","encodingScheme":1,"translationType":0,"digits":"2491250814474","pcPresent":false,"ssnPresent":true,"reservedForNationalUseBit":false,"signalingPointCode":0,"subsystemNumber":6},"msisdn":"249123078460","imsi":"634070245678327","localDialogID":394420084,"remoteDialogID":14739448,"isComplete":false,"mappingType":"JSON","isMobileOriginated":true,"mobileRequestIdentifier":1,"isInitial":false,"isProxy":false,"directPool":"*200*","serviceType":"Data","lang":"ar","Direct":"DirectSubscription","servideType":"directSubscription"}
            [2026-03-20 17:09:11] local.INFO: serviceUrlId:17 69bd6317ba733c3d09d3e3c3 249123078460 *200*2# New USSD DIRECT subscription
            [2026-03-20 17:09:11] local.INFO: TebcoSubscription isLBSRequired value 1
            [2026-03-20 17:09:12] local.INFO: 69bd6317ba733c3d09d3e3c3 249123078460  HTTP request: URL:http://172.31.22.131:10015/productorderingmanagement/v1/productorder Body:{"@type":"ProductOrder","relatedChannel":[{"@type":"RelatedChannel","channel":{"@type":"ChannelRef","name":"USSD-DirectCodes","characteristic":[{"@type":"StringCharacteristic","name":"ShortCode","value":"*200*2#"}]}}],"productOrderItem":[{"@type":"ProductOrderItem","quantity":1,"action":"add","product":{"@type":"Product","isBundle":false,"productSpecification":[],"category":[{"@referredType":"Category","name":"Package"}]},"relatedParty":[{"@type":"RelatedPartyOrPartyRole","role":"CustomerA","party":{"@type":"PartyRef","@referredType":"Individual","characteristic":[{"@type":"StringCharacteristic","name":"MSISDN","value":"249123078460"},{"name":"mcc","value":"634"},{"name":"mnc","value":"7"},{"name":"lac","value":"31"},{"name":"cellid","value":"30648"},{"name":"aol","value":"-1"},{"name":"vlrNumber","value":"2491250814467"},{"name":"IMSI","value":"634070245678327"},{"name":"GT","value":"2491250814487"}],"languageAbility":{"isFavouriteLanguage":true,"languageCode":"ar"}}}]}]}
            [2026-03-20 17:09:12] local.INFO: 69bd6317ba733c3d09d3e3c3 249123078460 HTTP response: {"header":{"transaction":{"transactionId":"f5f551fa-71c1-40e0-a61c-c026a1973e03"},"statusDetails":{"status":"FAIL","code":"ESB-00051","message":"Not subscribed to the package"}},"responseBody":{"state":"Failed","productOrderErrorMessage":{"schemaLocation":"ProductOrderErrorMessage","baseType":"ErrorMessage","code":"51","message":"\u0627\u0646\u062a \u063a\u064a\u0631 \u0645\u0634\u062a\u0631\u0643 \u0641\u064a \u0647\u0630\u0629 \u0627\u0644\u0628\u0627\u0642\u0629","reason":"Not subscribed to the package","timestamp":"2026-03-20T17:09:12.605+02:00"}}}
            [2026-03-20 17:09:12] local.INFO: HourlyReportResponse:249123078460 {"resposneCode":0,"responseMessage":"pushed to redis report: *200*2#"}
            [2026-03-20 17:09:12] local.INFO: USSD response: {"responseCode":422,"transactionId":"f5f551fa-71c1-40e0-a61c-c026a1973e03","callID":"69bd6317ba733c3d09d3e3c3","statusDetails_status":"FAIL","statusDetails_code":"ESB-00051","result":"انت غير مشترك في هذة الباقة","msisdn":"249123078460"}
            [2026-03-20 17:09:12] local.INFO: CDR Save Response (error): 249123078460 {"responseCode":0,"responseMessage":"CDR record saved successfully","insertedId":"cdr_69bd63189aa444.74010182"}
            """;

    @Test
    void parsesFullCallAcrossAllLineTypesIntoOneTransaction() {
        List<String> lines = Arrays.asList(SAMPLE_LOG.split("\n"));

        Map<String, UssdTransaction> result = parser.parseLines(lines);

        assertEquals(1, result.size(), "All 8 lines correlate to a single callID and should produce one transaction");
        UssdTransaction tx = result.get("69bd6317ba733c3d09d3e3c3");
        assertNotNull(tx);

        assertEquals("249123078460", tx.getMsisdn());
        assertEquals("*200*2#", tx.getShortCode());
        assertEquals("*200*2#", tx.getProductName());
        assertEquals("Data", tx.getServiceType());
        assertEquals("ar", tx.getLang());
        assertEquals("DirectSubscription", tx.getChannel());
        assertEquals(LocalDateTime.of(2026, 3, 20, 17, 9, 11), tx.getTriggeredAt());

        assertEquals("2491250814467", tx.getVlr());
        assertEquals("30648", tx.getCellId());

        assertEquals("f5f551fa-71c1-40e0-a61c-c026a1973e03", tx.getTransactionId());
        assertEquals("FAILED", tx.getStatus());
        assertEquals("ESB-00051", tx.getErrorCode());
        assertEquals("انت غير مشترك في هذة الباقة", tx.getErrorMessage());
        assertEquals("Not subscribed to the package", tx.getErrorReason());

        assertEquals(1000L, tx.getLatencyMs());
    }

    @Test
    void parsesLinesWithTrailingWhitespaceAsWrittenByTheGateway() {
        // Real log lines end with two spaces; the HTTP request/response lines must still be parsed
        List<String> lines = Arrays.stream(SAMPLE_LOG.split("\n"))
                .filter(line -> !line.isBlank())
                .map(line -> line + "  ")
                .toList();

        UssdTransaction tx = parser.parseLines(lines).get("69bd6317ba733c3d09d3e3c3");

        assertEquals("2491250814467", tx.getVlr());
        assertEquals("30648", tx.getCellId());
        assertEquals("ESB-00051", tx.getErrorCode());
        assertEquals("FAILED", tx.getStatus());
    }

    @Test
    void parsesTibcoExceptionResponseWhoseCallIdIsInTheSecondJsonObject() {
        List<String> lines = List.of(
                "[2026-03-17 08:35:16] local.INFO: TibcoSubscription USSD ENTRY: {\"callID\":\"69b8f623c1b716069ca8a068\",\"originalRequest\":\"*4*11*5#\",\"msisdn\":\"249113581789\",\"serviceType\":\"data\",\"lang\":\"ar\",\"Direct\":\"DirectSubscription\"}  ",
                "[2026-03-17 08:35:16] local.INFO: USSD response: {\"responseCode\":500,\"responseMessage\":\"TibcoSubscription Exception Trying to access array offset on value of type null\",\"result\":\"Please try later\",\"UssdShortCodesProperties\":{\"id\":188,\"ussd_str\":\"*4*11*\",\"routeTarget\":\"TIBCO\",\"Desc\":\"40MBDayGift\"}} {\"callID\":\"69b8f623c1b716069ca8a068\",\"originalRequest\":\"*4*11*5#\",\"msisdn\":\"249113581789\"}  "
        );

        Map<String, UssdTransaction> result = parser.parseLines(lines);

        assertEquals(1, result.size());
        UssdTransaction tx = result.get("69b8f623c1b716069ca8a068");
        assertEquals("FAILED", tx.getStatus());
        assertEquals("HTTP_500", tx.getErrorCode());
        assertEquals("TibcoSubscription Exception Trying to access array offset on value of type null", tx.getErrorMessage());
        assertEquals("40MBDayGift", tx.getProductName());
        assertEquals("Data", tx.getServiceType());
    }

    @Test
    void usesSelectedMenuCodeAndMarksHtmlErrorPagesAsFailed() {
        List<String> lines = List.of(
                "[2026-03-19 19:28:28] local.INFO: TibcoSubscription USSD ENTRY: {\"callID\":\"69bc323cba733c3d09e9f240\",\"originalRequest\":\"*4*\",\"ussdstr\":\"*4*015#\",\"msisdn\":\"249120120296\",\"servideType\":\"Gifting\"}  ",
                "[2026-03-19 19:28:29] local.INFO: 69bc323cba733c3d09e9f240 249120120296 HTTP response: <html>",
                "  <head>"
        );

        UssdTransaction tx = parser.parseLines(lines).get("69bc323cba733c3d09e9f240");

        assertEquals("*4*015#", tx.getShortCode());
        assertEquals("MenuSubscription", tx.getChannel());
        assertEquals("FAILED", tx.getStatus());
        assertEquals("HTML_ERROR", tx.getErrorCode());
    }

    @Test
    void ignoresLinesWithoutParsableCallCorrelator() {
        List<String> lines = List.of(
                "[2026-03-20 17:09:11] local.INFO: TebcoSubscription isLBSRequired value 1",
                "[2026-03-20 17:09:12] local.INFO: HourlyReportResponse:249123078460 {\"resposneCode\":0,\"responseMessage\":\"ok\"}",
                "[2026-03-20 17:09:12] local.INFO: CDR Save Response (error): 249123078460 {\"responseCode\":0}"
        );

        Map<String, UssdTransaction> result = parser.parseLines(lines);

        assertTrue(result.isEmpty());
    }

    @Test
    void skipsUnparseableLinesWithoutThrowing() {
        List<String> lines = List.of(
                "this is not a log line at all",
                "[2026-03-20 17:09:11] local.INFO: TibcoSubscription USSD ENTRY: {not valid json"
        );

        assertDoesNotThrow(() -> parser.parseLines(lines));
    }

    @Test
    void repairsAndExtractsTruncatedEntryLineWithMissingClosingBrace() {
        // Missing closing quote and closing brace
        List<String> lines = List.of(
                "[2026-03-20 17:09:11] local.INFO: TibcoSubscription USSD ENTRY: {\"callID\":\"trunc123\",\"originalRequest\":\"*200*2#\",\"remoteURL\":\"http://10.55.245.100/api\",\"msisdn\":\"249123456789"
        );

        Map<String, UssdTransaction> result = parser.parseLines(lines);

        assertEquals(1, result.size());
        UssdTransaction tx = result.get("trunc123");
        assertNotNull(tx);
        assertEquals("249123456789", tx.getMsisdn());
        assertEquals("*200*2#", tx.getShortCode());
        assertEquals("http://10.55.245.100/api", tx.getRemoteUrl());
    }

    @Test
    void extractsTibcoUrlFromHttpRequestLineEvenWithExtraSpaces() {
        List<String> lines = List.of(
                "[2026-03-20 17:09:12] local.INFO: call999 249123456789  HTTP request: URL: http://172.31.22.131:10015/productorder Body: {\"test\":\"val\"}",
                "[2026-03-20 17:09:13] local.INFO: call999 249123456789 HTTP response: {\"reason\":\"Quota exceeded\",\"responseMessage\":\"Service unavailable\"}"
        );

        Map<String, UssdTransaction> result = parser.parseLines(lines);

        assertEquals(1, result.size());
        UssdTransaction tx = result.get("call999");
        assertNotNull(tx);
        assertEquals("http://172.31.22.131:10015/productorder", tx.getRemoteUrl());
        assertEquals("Quota exceeded", tx.getErrorReason());
        assertEquals("Service unavailable", tx.getSubscriberReply());
    }

    @Test
    void extractsExactUserSampleTibcoUrlWithoutBody() {
        List<String> lines = List.of(
                "[2026-03-20 17:09:11] local.INFO: TibcoSubscription USSD ENTRY: {\"callID\":\"call_tibco_1\",\"msisdn\":\"249123078460\"}",
                "[2026-03-20 17:09:12] local.INFO: HTTP request: URL:http://172.31.22.131:10015/productorderingmanagement/v1/productorder",
                "[2026-03-20 17:09:13] local.INFO: HTTP response: {\"reason\":\"Backend error\"}"
        );

        Map<String, UssdTransaction> result = parser.parseLines(lines);

        assertEquals(1, result.size());
        UssdTransaction tx = result.get("call_tibco_1");
        assertNotNull(tx);
        assertEquals("http://172.31.22.131:10015/productorderingmanagement/v1/productorder", tx.getRemoteUrl());
        assertEquals("Backend error", tx.getErrorReason());
    }

    @Test
    void extractsShortCodeFromServiceUrlLine() {
        List<String> lines = List.of(
                "[2026-03-20 17:09:11] local.INFO: serviceUrlId:17 call_service_1 249123078460 *200*2# New USSD DIRECT subscription",
                "[2026-03-20 17:09:12] local.INFO: call_service_1 249123078460 HTTP request: URL:http://172.31.22.131:10015/productorderingmanagement/v1/productorder"
        );

        Map<String, UssdTransaction> result = parser.parseLines(lines);

        assertEquals(1, result.size());
        UssdTransaction tx = result.get("call_service_1");
        assertNotNull(tx);
        assertEquals("*200*2#", tx.getShortCode());
        assertEquals("*200*2#", tx.getProductName());
        assertEquals("249123078460", tx.getMsisdn());
        assertEquals("http://172.31.22.131:10015/productorderingmanagement/v1/productorder", tx.getRemoteUrl());
    }

    @Test
    void extractsAnyArbitraryDynamicUrlFromLog() {
        List<String> lines = List.of(
                "[2026-03-20 17:09:11] local.INFO: TibcoSubscription USSD ENTRY: {\"callID\":\"call_custom_99\",\"msisdn\":\"249912345678\"}",
                "[2026-03-20 17:09:12] local.INFO: call_custom_99 249912345678 HTTP request: URL:https://10.200.55.12:9443/tibco/custom/service/v2/order?action=activate Body:{\"data\":\"payload\"}"
        );

        Map<String, UssdTransaction> result = parser.parseLines(lines);

        assertEquals(1, result.size());
        UssdTransaction tx = result.get("call_custom_99");
        assertNotNull(tx);
        assertEquals("https://10.200.55.12:9443/tibco/custom/service/v2/order?action=activate", tx.getRemoteUrl());
    }
}
