package com.example.ussdGateway.config;

import com.example.ussdGateway.api.response.ReportSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RedisSerializationTest {

    @Test
    void testSerializeReportSummaryResponse() {
        ReportSummaryResponse resp = ReportSummaryResponse.builder()
                .startDate("2026-10-31")
                .endDate("2026-10-31")
                .totalTransactions(10)
                .dailyBreakdown(List.of(new ReportSummaryResponse.DailyCount(LocalDate.of(2026, 10, 31), 10, 8, 2)))
                .build();

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);

        byte[] bytes = serializer.serialize(resp);
        assertNotNull(bytes);

        ReportSummaryResponse deserialized = (ReportSummaryResponse) serializer.deserialize(bytes);
        assertNotNull(deserialized);
        assertEquals(10, deserialized.getTotalTransactions());
        assertEquals(LocalDate.of(2026, 10, 31), deserialized.getDailyBreakdown().get(0).getDay());
    }
}
