package com.example.ussdGateway.config.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    // Key: IP_URI, Value: [Tokens remaining, Last refill timestamp ms]
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
            
            if (rateLimit == null) {
                rateLimit = handlerMethod.getBeanType().getAnnotation(RateLimit.class);
            }

            if (rateLimit != null) {
                String clientIp = request.getRemoteAddr();
                String key = clientIp + "_" + request.getRequestURI();
                long now = System.currentTimeMillis();
                long durationMs = rateLimit.durationSeconds() * 1000L;
                int maxCalls = rateLimit.calls();

                long[] state = buckets.compute(key, (k, v) -> {
                    if (v == null || (now - v[1] >= durationMs)) {
                        // Initialize or completely refill the bucket
                        return new long[]{maxCalls - 1, now}; 
                    }
                    long tokens = v[0];
                    long lastRefill = v[1];
                    
                    if (tokens > 0) {
                        // Consume one token, keep original refill timestamp
                        return new long[]{tokens - 1, lastRefill};
                    }
                    
                    // Out of tokens, bucket unchanged
                    return new long[]{0, lastRefill};
                });

                if (state[0] == 0 && (now - state[1] < durationMs)) {
                    // Provide a hint in the response
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setContentType("application/json");
                    long waitSecs = (durationMs - (now - state[1])) / 1000;
                    response.getWriter().write("{\"error\": \"Too many requests. Please wait " + waitSecs + " seconds.\"}");
                    return false;
                }
            }
        }
        return true;
    }
}
