package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@ActiveProfiles("test")
class JdbcProtocolProfileProviderTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcProtocolProfileProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JdbcProtocolProfileProvider(jdbcTemplate);
        provider.refresh();
    }

    @Test
    void resolvesEnabledProfileByTopicAndPayloadVersion() throws Exception {
        ResolvedProtocolProfile resolved = provider.resolve(
                "device/raw/energy/up",
                objectMapper.readTree("""
                        {"protocol_version":"1.0"}
                        """));

        assertThat(resolved.profile().profileId()).isEqualTo("PROFILE_V1");
        assertThat(resolved.mappings()).extracting(ProtocolFieldMapping::metricCode)
                .containsExactly("CURRENT_ENERGY", "CURRENT_CO2");
    }

    @Test
    void rejectsResolutionUntilFirstCompleteSnapshotIsLoaded() {
        JdbcProtocolProfileProvider unloadedProvider = new JdbcProtocolProfileProvider(jdbcTemplate);

        assertThatThrownBy(() -> unloadedProvider.resolve(
                "device/raw/energy/up",
                objectMapper.createObjectNode()))
                .isInstanceOf(ProtocolProfileUnavailableException.class);
    }

    @Test
    void rejectsUnknownAndAmbiguousProfiles() throws Exception {
        assertThatThrownBy(() -> provider.resolve(
                "device/raw/unknown/up",
                objectMapper.readTree("{}")))
                .isInstanceOf(ProtocolProfileResolutionException.class)
                .hasMessageContaining("未配置");

        assertThatThrownBy(() -> provider.resolve(
                "device/raw/energy/up",
                objectMapper.readTree("{}")))
                .isInstanceOf(ProtocolProfileResolutionException.class)
                .hasMessageContaining("无法唯一确定");
    }

    @Test
    void disabledProfileIsNotVisible() throws Exception {
        assertThatThrownBy(() -> provider.resolve(
                "device/raw/disabled/up",
                objectMapper.readTree("{}")))
                .isInstanceOf(ProtocolProfileResolutionException.class);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void refreshFailureKeepsLastCompleteSnapshot() throws Exception {
        jdbcTemplate.execute("DROP TABLE iot_protocol_field_mapping");

        provider.refresh();

        ResolvedProtocolProfile resolved = provider.resolve(
                "device/raw/energy/up",
                objectMapper.readTree("""
                        {"protocol_version":"2.0"}
                        """));
        assertThat(resolved.profile().profileId()).isEqualTo("PROFILE_V2");
    }
}
