package com.platform.hvac.asset.ledger;

import com.platform.framework.common.Result;
import com.platform.security.SecurityUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.platform.hvac.asset.ledger.LedgerContracts.*;

@RestController
@RequestMapping("/v1/assets")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
/** 平台管理员台账入口；复用资产权限，不传播空调模块的 TEST 授权例外。 */
public class LedgerController {
    private final LedgerService service;

    @GetMapping("/ledger")
    public Result<LedgerPage> ledger(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String buildingId,
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String systemGroupId,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "ALL") String professionalGroup,
            Authentication authentication) {
        return Result.success(service.query(page, size, buildingId, spaceId, systemGroupId, typeCode,
                keyword, professionalGroup, SecurityUser.roles(authentication)));
    }

    @GetMapping("/ledger/type-rules")
    public Result<List<TypeRule>> rules(Authentication authentication) {
        return Result.success(service.rules(SecurityUser.roles(authentication)));
    }

    @PutMapping("/equipment-types/{typeCode}/ledger-display-rule")
    public Result<TypeRule> saveRule(@PathVariable String typeCode, @Valid @RequestBody TypeRuleRequest request,
                                    Authentication authentication) {
        return Result.success(service.saveRule(typeCode, request, SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }

    @PutMapping("/equipment/{equipmentId}/ledger-classification")
    public Result<ClassificationView> saveClassification(@PathVariable String equipmentId,
            @Valid @RequestBody ClassificationRequest request, Authentication authentication) {
        return Result.success(service.saveClassification(equipmentId, request, SecurityUser.userId(authentication),
                SecurityUser.roles(authentication)));
    }
}
