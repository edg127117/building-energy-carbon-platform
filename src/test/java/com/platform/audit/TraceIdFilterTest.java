package com.platform.audit;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {
    @Test
    void serverTraceIgnoresClientHeaderAndClearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/backoffice/change-requests/1");
        request.addHeader(TraceContext.HEADER, "client-controlled");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TraceIdFilter().doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader(TraceContext.HEADER);
        assertThat(traceId).hasSize(32).doesNotContain("client-controlled");
        assertThat(request.getAttribute(TraceContext.ATTRIBUTE)).isEqualTo(traceId);
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }
}
