package cn.iocoder.yudao.module.governance.api.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcFilter;
import cn.iocoder.yudao.module.governance.service.category.SafetyClassificationReferenceService;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
public class SafetyClassificationApiImpl implements SafetyClassificationApi {

    @Resource
    private SafetyClassificationReferenceService classificationReferenceService;

    @Resource
    private HttpServletRequest servletRequest;

    /**
     * 读取平台共享治理分类树的扁平投影。
     */
    @Override
    @PermitAll
    public CommonResult<List<SafetyClassificationRespDTO>> getClassifications() {
        StandardTermRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(classificationReferenceService.list());
    }

    /**
     * 核验敏感清单引用的治理分类，缺失时返回空。
     */
    @Override
    @PermitAll
    public CommonResult<SafetyClassificationRespDTO> getClassification(Long id) {
        StandardTermRpcFilter.requireVerified(servletRequest);
        return CommonResult.success(classificationReferenceService.get(id));
    }

}
