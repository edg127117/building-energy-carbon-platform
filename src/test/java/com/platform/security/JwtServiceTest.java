package com.platform.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定同一账号在同一秒内重新登录时也必须获得唯一 Token。 */
class JwtServiceTest {

    private final JwtService service = new JwtService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "secret",
                "test-only-jwt-secret-with-more-than-thirty-two-bytes");
        ReflectionTestUtils.setField(service, "expireSeconds", 3600L);
    }

    @Test
    void consecutiveTokensHaveDifferentIdsAndSerializedValues() {
        String first = service.generateToken(7L, "operator", List.of("BUILDING_OWNER"));
        String second = service.generateToken(7L, "operator", List.of("BUILDING_OWNER"));

        Claims firstClaims = service.parseToken(first).getPayload();
        Claims secondClaims = service.parseToken(second).getPayload();
        assertThat(first).isNotEqualTo(second);
        assertThat(firstClaims.getId()).isNotBlank().isNotEqualTo(secondClaims.getId());
    }
}
