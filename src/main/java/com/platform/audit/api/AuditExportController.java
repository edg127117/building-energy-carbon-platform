package com.platform.audit.api;

import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.export.AuditExportJob;
import com.platform.audit.export.AuditExportService;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

@Tag(name = "审计证据脱敏导出")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/v1/audit-exports")
@RequiredArgsConstructor
/** 首版只提供默认脱敏 CSV；没有未脱敏参数或可绕过职责的公开下载路径。 */
public class AuditExportController {
    private final AuditExportService service;
    private final AuditGovernanceProperties properties;

    @PostMapping
    public Result<AuditExportContracts.JobView> create(
            Authentication authentication, @Valid @RequestBody AuditExportContracts.CreateRequest request) {
        AuditExportJob job = service.create(SecurityUser.userId(authentication), SecurityUser.roles(authentication),
                request.filter(), request.purpose());
        return Result.success(AuditExportContracts.JobView.from(job, properties.getExportMaxRows()));
    }

    @GetMapping("/{exportId}")
    public Result<AuditExportContracts.JobView> detail(
            Authentication authentication, @PathVariable String exportId) {
        AuditExportJob job = service.detail(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), exportId);
        return Result.success(AuditExportContracts.JobView.from(job, properties.getExportMaxRows()));
    }

    @GetMapping("/{exportId}/download")
    public ResponseEntity<Resource> download(Authentication authentication, @PathVariable String exportId)
            throws IOException {
        AuditExportService.Download download = service.download(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), exportId);
        FileSystemResource resource = new FileSystemResource(download.path());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .contentLength(resource.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }
}
