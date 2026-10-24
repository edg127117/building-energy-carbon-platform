package com.platform.energy.reporting;

import com.platform.audit.SecurityAuditService;
import com.platform.framework.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportingUploadErrorsTest {
    @RestController static class UploadFailure {
        @PostMapping({"/v1/energy-reporting/upload-test","/legacy-upload-test"})
        void upload(){throw new MaxUploadSizeExceededException(1024*1024);}
    }
    @Test void oversizedWorkbookIsAStableInputErrorAndKeepsLegacyPrefixSeparate() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new UploadFailure())
                .setControllerAdvice(new GlobalExceptionHandler(mock(SecurityAuditService.class))).build();
        mvc.perform(post("/v1/energy-reporting/upload-test")).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("REPORTING_WORKBOOK_TOO_LARGE"))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/legacy-upload-test")).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errorCode").value("UPLOAD_TOO_LARGE"));
    }
}
