package cn.iocoder.yudao.module.asset.controller.admin.standardassociation;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.standardassociation.vo.StandardAssociationPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.standardassociation.vo.StandardAssociationRespVO;
import cn.iocoder.yudao.module.asset.service.dataasset.StandardAssociationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台 - 标准关联资产")
@RestController
@RequestMapping("/asset/standard-association")
@Validated
@RequiredArgsConstructor
public class StandardAssociationController {

    private final StandardAssociationService service;

    /**
     * 获得当前用户可维护的标准关联资产分页。
     */
    @GetMapping("/page")
    @Operation(summary = "获得当前用户可维护的标准关联资产分页")
    @PreAuthorize("@ss.hasAnyPermissions('governance:standard-element:query', 'governance:standard-code-set:query')")
    public CommonResult<PageResult<StandardAssociationRespVO>> page(@Valid StandardAssociationPageReqVO request) {
        return CommonResult.success(service.getPage(request));
    }
}
