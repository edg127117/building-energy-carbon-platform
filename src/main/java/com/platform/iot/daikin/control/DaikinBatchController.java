package com.platform.iot.daikin.control;

import com.platform.audit.TraceContext;
import com.platform.framework.common.Result;
import com.platform.framework.web.PageResponse;
import com.platform.security.SecurityUser;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.platform.iot.daikin.control.DaikinBatchContracts.*;

@RestController
@RequestMapping("/v1/hvac-control/batches")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name="bearerAuth")
@Tag(name="空调批量人工控制")
/** 仅接受平台设备及共同动作；批次查询和取消复核本人、账号、建筑与控制权限。 */
public class DaikinBatchController {
    private final DaikinBatchService service;
    public DaikinBatchController(DaikinBatchService service) { this.service=service; }
    @PostMapping("/preview")
    public Result<Preview> preview(Authentication auth,@RequestBody PreviewRequest body) {
        return Result.success(service.preview(SecurityUser.userId(auth),body));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<Batch> submit(Authentication auth,@RequestBody SubmitBatch body,HttpServletRequest request) {
        return Result.success(service.submit(SecurityUser.userId(auth),body,TraceContext.from(request)));
    }
    @GetMapping("/{batchId}")
    public Result<Batch> get(Authentication auth,@PathVariable String batchId) {
        return Result.success(service.get(SecurityUser.userId(auth),batchId));
    }
    @GetMapping
    public Result<PageResponse<Batch>> history(Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size) {
        return Result.success(service.history(SecurityUser.userId(auth),page,size));
    }
    @PostMapping("/{batchId}/cancel")
    public Result<Batch> cancel(Authentication auth,@PathVariable String batchId) {
        return Result.success(service.cancel(SecurityUser.userId(auth),batchId));
    }
}
