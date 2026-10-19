package com.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    @Test
    void downstreamFailureDoesNotReplayAnAuthenticatedRequest() throws Exception {
        JwtService jwt = new JwtService();
        ReflectionTestUtils.setField(jwt, "secret", "test-only-jwt-secret-with-more-than-thirty-two-bytes");
        ReflectionTestUtils.setField(jwt, "expireSeconds", 3600L);
        String token = jwt.generateToken(7L, "operator", List.of("BUILDING_OWNER"));
        TokenCacheService tokens = mock(TokenCacheService.class);
        when(tokens.validateActiveToken(7L, token)).thenReturn(TokenValidationResult.ACTIVE);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, tokens, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(new ServletException("isolated downstream failure")).when(chain).doFilter(request, response);
        try {
            assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(ServletException.class);
            verify(chain, times(1)).doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
