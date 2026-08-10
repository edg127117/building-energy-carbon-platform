package com.platform.iot.websocket;

import com.platform.cache.TokenCacheService;
import com.platform.cache.TokenValidationResult;
import com.platform.framework.exception.BusinessException;
import com.platform.security.JwtService;
import com.platform.system.service.BuildingScopeService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HvacRealtimeAccessServiceTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private TokenCacheService tokenCacheService;
    @Mock
    private BuildingScopeService buildingScopeService;
    @Mock
    private Jws<Claims> jws;
    @Mock
    private Claims claims;

    private HvacRealtimeAccessService service;

    @BeforeEach
    void setUp() {
        service = new HvacRealtimeAccessService(
                jwtService, tokenCacheService, buildingScopeService);
    }

    @Test
    void authenticatesFormalRoleActiveTokenAndAuthorizedBuilding() {
        stubClaims(7L, List.of("BUILDING_OWNER"), futureExpiry());
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.ACTIVE);

        HvacRealtimeSubscription result = service.authenticate("jwt", "BLD001");

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.roles()).containsExactly("BUILDING_OWNER");
        assertThat(result.buildingId()).isEqualTo("BLD001");
        assertThat(result.token()).isEqualTo("jwt");
        verify(buildingScopeService).checkAccess(
                7L, Set.of("BUILDING_OWNER"), "BLD001");
    }

    @Test
    void keepsHttpJwtFallbackWhenRedisIsUnavailable() {
        stubClaims(7L, List.of("BUILDING_OWNER"), futureExpiry());
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.CACHE_UNAVAILABLE);

        assertThat(service.authenticate("jwt", "BLD001").userId()).isEqualTo(7L);
    }

    @Test
    void mapsRejectedTokenTo4401AndBuildingDenialTo4403() {
        stubClaims(7L, List.of("BUILDING_OWNER"), futureExpiry());
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.REJECTED);
        assertFailure("jwt", "BLD001", 4401, "UNAUTHORIZED");

        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.ACTIVE);
        doThrow(new BusinessException(403, "无权访问该建筑"))
                .when(buildingScopeService)
                .checkAccess(7L, Set.of("BUILDING_OWNER"), "BLD001");
        assertFailure("jwt", "BLD001", 4403, "FORBIDDEN_BUILDING");
    }

    @Test
    void mapsUnavailableBuildingAuthorityTo1011WithoutGrantingAccess() {
        stubClaims(7L, List.of("BUILDING_OWNER"), futureExpiry());
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.ACTIVE);
        doThrow(new DataAccessResourceFailureException("mysql unavailable"))
                .when(buildingScopeService)
                .checkAccess(7L, Set.of("BUILDING_OWNER"), "BLD001");

        assertFailure("jwt", "BLD001", 1011, "REALTIME_AUTH_UNAVAILABLE");
    }

    @Test
    void rejectsInvalidJwtMissingIdentityRolesAndExpiredClaims() {
        when(jwtService.parseToken("invalid"))
                .thenThrow(new JwtException("invalid"));
        assertFailure("invalid", "BLD001", 4401, "UNAUTHORIZED");

        stubClaims(null, List.of("BUILDING_OWNER"), futureExpiry());
        assertFailure("jwt", "BLD001", 4401, "UNAUTHORIZED");

        stubClaims(7L, List.of("legacy-role"), futureExpiry());
        assertFailure("jwt", "BLD001", 4401, "UNAUTHORIZED");

        stubClaims(7L, List.of("BUILDING_OWNER"), new Date(System.currentTimeMillis() - 1));
        assertFailure("jwt", "BLD001", 4401, "UNAUTHORIZED");
    }

    @Test
    void rejectsBlankSubscriptionIdentity() {
        assertFailure(null, "BLD001", 4401, "UNAUTHORIZED");
        assertFailure("jwt", " ", 4401, "UNAUTHORIZED");
    }

    @Test
    void revalidatesTheOriginalUserAndRechecksBuildingPermission() {
        stubClaims(7L, List.of("BUILDING_OWNER"), futureExpiry());
        when(tokenCacheService.validateActiveToken(7L, "jwt"))
                .thenReturn(TokenValidationResult.ACTIVE);
        HvacRealtimeSubscription initial = service.authenticate("jwt", "BLD001");

        doThrow(new BusinessException(403, "无权访问该建筑"))
                .when(buildingScopeService)
                .checkAccess(7L, Set.of("BUILDING_OWNER"), "BLD001");

        assertThatThrownBy(() -> service.revalidate(initial))
                .isInstanceOf(HvacRealtimeAccessException.class)
                .satisfies(error -> assertThat(
                        ((HvacRealtimeAccessException) error).closeCode()).isEqualTo(4403));
    }

    private void stubClaims(Long userId, List<String> roles, Date expiry) {
        when(jwtService.parseToken("jwt")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(jwtService.getUserId(claims)).thenReturn(userId);
        when(jwtService.getRoles(claims)).thenReturn(roles);
        when(claims.getExpiration()).thenReturn(expiry);
    }

    private void assertFailure(String token, String buildingId, int closeCode, String errorCode) {
        assertThatThrownBy(() -> service.authenticate(token, buildingId))
                .isInstanceOf(HvacRealtimeAccessException.class)
                .satisfies(error -> {
                    HvacRealtimeAccessException accessError =
                            (HvacRealtimeAccessException) error;
                    assertThat(accessError.closeCode()).isEqualTo(closeCode);
                    assertThat(accessError.errorCode()).isEqualTo(errorCode);
                });
    }

    private Date futureExpiry() {
        return new Date(System.currentTimeMillis() + 60_000L);
    }
}
