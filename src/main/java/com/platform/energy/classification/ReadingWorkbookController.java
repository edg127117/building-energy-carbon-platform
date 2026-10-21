package com.platform.energy.classification;
import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/energy-classification/buildings/{buildingId}/readings/workbook")
@PreAuthorize("hasAnyRole('ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 标准工作簿下载与只读预检入口；导入及独立审核仍由分类来源契约执行。 */
public class ReadingWorkbookController {
    private final ReadingWorkbookService service;
    public ReadingWorkbookController(ReadingWorkbookService service){this.service=service;}
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(Authentication a,@PathVariable String buildingId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=manual-energy-readings.xlsx")
                .body(service.template(SecurityUser.userId(a),SecurityUser.roles(a),buildingId));
    }
    @PostMapping(value="/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ReadingWorkbookService.Preview> preview(Authentication a,@PathVariable String buildingId,@RequestPart MultipartFile file,
            @RequestParam String idempotencyKey,@RequestParam String reason) {
        return Result.success(service.preview(SecurityUser.userId(a),SecurityUser.roles(a),buildingId,file,idempotencyKey,reason));
    }
}
