package com.platform.relation.api;

import com.platform.framework.common.Result;
import com.platform.relation.importing.MeteringImportApplicationService;
import com.platform.relation.importing.MeteringTemplateService;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

import static com.platform.relation.api.RelationContracts.MeteringImportPreflightView;
import static com.platform.relation.api.RelationContracts.MeteringImportResult;

@Tag(name = "表计关系批量导入")
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 平台标准模板下载和“预检—确认”入口；不包含提交审核或自动生效。 */
public class MeteringImportController {
    private final MeteringTemplateService templateService;
    private final MeteringImportApplicationService importService;

    @GetMapping("/v1/relation-models/metering-import/template")
    public ResponseEntity<byte[]> template(
            @RequestParam(defaultValue = "V1") String templateVersion) {
        byte[] bytes = templateService.create(templateVersion);
        String fileName = "metering-relation-template-" + templateVersion.toUpperCase() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(bytes.length)
                .body(bytes);
    }

    @PostMapping(value = "/v1/relation-models/metering-import/preflight",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<MeteringImportPreflightView> preflight(
            Authentication authentication,
            @RequestParam String buildingId,
            @RequestParam String versionId,
            @RequestParam long expectedRevision,
            @RequestPart("file") MultipartFile file) {
        return Result.success(importService.preflight(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, versionId, expectedRevision, file));
    }

    @PostMapping(value = "/v1/relation-models/metering-import/confirm",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ENERGY_MANAGER')")
    public Result<MeteringImportResult> confirm(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam String buildingId,
            @RequestParam String versionId,
            @RequestParam long expectedRevision,
            @RequestPart("file") MultipartFile file) {
        return Result.success(importService.confirm(SecurityUser.userId(authentication),
                SecurityUser.roles(authentication), buildingId, versionId, expectedRevision,
                idempotencyKey, file));
    }
}
