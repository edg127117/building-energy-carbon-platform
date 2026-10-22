package com.platform.energy.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.common.Result;
import com.platform.framework.exception.BusinessException;
import com.platform.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/energy-reporting/buildings/{building}/batches/{batchId}")
@PreAuthorize("hasAnyRole('BUILDING_OWNER','ENERGY_MANAGER','PLATFORM_ADMIN')")
/** 工作簿仅下载或预览；采纳后仍通过原草稿、预检及独立审核链。 */
public class ReportingWorkbookController {
    private final ReportingWorkbookService service;private final ObjectMapper mapper;
    @GetMapping("/workbook-template")
    public ResponseEntity<byte[]> template(Authentication a,@PathVariable String building,@PathVariable String batchId){
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=monthly-energy-bills.xlsx")
            .body(service.template(SecurityUser.userId(a),SecurityUser.roles(a),building,batchId));
    }
    @PostMapping(value="/workbook-preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ReportingWorkbookService.Preview> preview(Authentication a,@PathVariable String building,@PathVariable String batchId,
            @RequestPart MultipartFile file,@RequestParam(required=false) String mapping){
        ReportingWorkbookService.Request request=null;
        if(mapping!=null){try{if(mapping.length()>100000)throw new IllegalArgumentException();request=mapper.readValue(mapping,ReportingWorkbookService.Request.class);}
            catch(Exception e){throw new BusinessException(400,"REPORTING_WORKBOOK_INVALID","单元格映射格式无效");}}
        return Result.success(service.preview(SecurityUser.userId(a),SecurityUser.roles(a),building,batchId,file,request));
    }
}
