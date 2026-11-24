package com.example.ussdGateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class UssdGatewayApplicationTests {

	@MockBean
	private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

	@Test
	void contextLoads() {
	}

}
