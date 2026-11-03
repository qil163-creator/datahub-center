package cn.iocoder.yudao.module.governance.controller.admin.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.security.SensitiveClassificationReferenceApi;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
import cn.iocoder.yudao.module.governance.service.category.SafetyClassificationReferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 共享治理分类只读入口；验证当前令牌和敏感清单权限，不提供执行脱敏能力。 */
@RestController
@RequiredArgsConstructor
public class SensitiveClassificationReferenceController implements SensitiveClassificationReferenceApi {

    private final SafetyClassificationReferenceService classificationReferenceService;

    /**
     * 为有敏感清单权限的用户返回平台共享分类树。
     */
    @Override
    @PreAuthorize("@ss.hasAnyPermissions('asset:sensitive-list:query','asset:sensitive-list:create','asset:sensitive-list:update')")
    public CommonResult<List<SafetyClassificationRespDTO>> getClassifications(String authorization) {
        return CommonResult.success(classificationReferenceService.list());
    }

    /**
     * 为有敏感清单权限的用户返回当前分类事实。
     */
    @Override
    @PreAuthorize("@ss.hasAnyPermissions('asset:sensitive-list:query','asset:sensitive-list:create','asset:sensitive-list:update')")
    public CommonResult<SafetyClassificationRespDTO> getClassification(Long id, String authorization) {
        return CommonResult.success(classificationReferenceService.get(id));
    }
}
