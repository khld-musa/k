package com.example.ussdGateway.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache configuration using Redis for routing rules, statistics, and other frequently accessed data.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    // Cache names
    public static final String ROUTING_RULES_CACHE = "routingRules";
    public static final String SERVICES_CACHE = "services";
    public static final String USSD_CODES_CACHE = "ussdCodes";
    public static final String STATISTICS_CACHE = "statistics";
    public static final String USER_CACHE = "users";
    /** [FIX PERF-2] Report results cache — 17 sequential DB queries, expensive to regenerate */
    public static final String REPORTS_CACHE = "reports";

    private GenericJackson2JsonRedisSerializer createJsonRedisSerializer() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator.instance,
                com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    @Bean
    @org.springframework.context.annotation.Profile("!test")
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        GenericJackson2JsonRedisSerializer serializer = createJsonRedisSerializer();
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Routing rules cache - longer TTL as they change infrequently
        cacheConfigurations.put(ROUTING_RULES_CACHE, 
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Services cache
        cacheConfigurations.put(SERVICES_CACHE, 
                defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // USSD codes cache
        cacheConfigurations.put(USSD_CODES_CACHE, 
                defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // Statistics cache - short TTL for fresh data
        cacheConfigurations.put(STATISTICS_CACHE, 
                defaultConfig.entryTtl(Duration.ofSeconds(30)));

        // User cache
        cacheConfigurations.put(USER_CACHE, 
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // [FIX PERF-2] Reports cache — same date range reuse without 17 sequential DB round-trips
        cacheConfigurations.put(REPORTS_CACHE,
                defaultConfig.entryTtl(Duration.ofMinutes(10)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }

    @Bean
    @org.springframework.context.annotation.Profile("test")
    public CacheManager testCacheManager() {
        return new org.springframework.cache.concurrent.ConcurrentMapCacheManager(
                ROUTING_RULES_CACHE, SERVICES_CACHE, USSD_CODES_CACHE, STATISTICS_CACHE, USER_CACHE, REPORTS_CACHE
        );
    }
}
