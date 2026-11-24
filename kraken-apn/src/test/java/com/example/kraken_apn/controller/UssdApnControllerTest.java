package com.example.kraken_apn.controller;

import com.example.kraken_apn.dto.ApnSendResponseDto;
import com.example.kraken_apn.service.ApnService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UssdApnControllerTest {

    @Test
    void testSendApnReturnsExpectedUssdEnvelope() throws Exception {
        ApnSendResponseDto mockDto = ApnSendResponseDto.builder()
                .responseCode(200)
                .transactionId("f5f551fa-71c1-40e0-a61c-c026a1973e03")
                .callID("6ad7be836eb7466e1d511084")
                .statusDetailsStatus("SUCCESS")
                .statusDetailsCode("0")
                .result("APN sent successfully")
                .msisdn("249123004940")
                .responseMessage("APN sent successfully")
                .success(true)
                .message("APN sent successfully")
                .data(Map.of("imsi", "634070587204271", "msisdn", "249123004940"))
                .build();

        ApnService stubService = new ApnService(null, null) {
            @Override
            public ApnSendResponseDto processUssdApnRequest(Map<String, Object> rawPayload) {
                return mockDto;
            }
        };

        UssdApnController controller = new UssdApnController(stubService);

        Map<String, Object> ussdPayload = Map.of(
                "callID", "6ad7be836eb7466e1d511084",
                "msisdn", "249123004940",
                "imsi", "634070587204271",
                "ussdstr", "*20182018#",
                "lang", "ar"
        );

        ResponseEntity<ApnSendResponseDto> response = controller.sendApn(ussdPayload);
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());

        ApnSendResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("6ad7be836eb7466e1d511084", body.getCallID());
        assertEquals(200, body.getResponseCode());
        assertEquals("APN sent successfully", body.getResult());
        assertEquals("SUCCESS", body.getStatusDetailsStatus());
        assertEquals("0", body.getStatusDetailsCode());

        // Validate JSON serialization contains exact property names
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(body);
        assertTrue(json.contains("\"callID\":\"6ad7be836eb7466e1d511084\""));
        assertTrue(json.contains("\"result\":\"APN sent successfully\""));
        assertTrue(json.contains("\"statusDetails_status\":\"SUCCESS\""));
        assertTrue(json.contains("\"statusDetails_code\":\"0\""));
        assertTrue(json.contains("\"responseCode\":200"));
    }

    @Test
    void testHealthEndpoint() {
        ApnService stubService = new ApnService(null, null);
        UssdApnController controller = new UssdApnController(stubService);
        ResponseEntity<Map<String, Object>> response = controller.health();
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("kraken-apn", response.getBody().get("service"));
    }
}
