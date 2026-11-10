package com.platform.carbon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 年度读取以一条轻量投影查询替代逐条/成对读取完整正文。 */
class DynamicElectricityStoredPairReadTest {
    @Test void chartRangeUsesOneProjectionQueryAndCannotSelectFullBodies() {
        var jdbc=mock(JdbcTemplate.class);
        doReturn(List.of()).when(jdbc).query(contains("LEFT JOIN biz_dynamic_electricity_chart_projection"),
                any(RowMapper.class),any(),any(),any());
        var repository=new DynamicElectricityRepository(jdbc,new ObjectMapper());

        assertThat(repository.results("B",Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"))).isEmpty();

        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),any(),any(),any());
        assertThat(sql.getValue()).contains("LEFT JOIN biz_dynamic_electricity_chart_projection")
                .contains("LIMIT 50001").doesNotContain("r.payload_json","OCTET_LENGTH");
        verifyNoMoreInteractions(jdbc);
    }
}
