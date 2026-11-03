package cn.iocoder.yudao.module.governance.controller.admin.standardassociation;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.controller.admin.standardassociation.vo.StandardAssociationCheckReqVO;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardAssociationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台 - 标准关联信息")
@RestController("governanceStandardAssociationController")
@RequestMapping("/governance/standard-association")
@Validated
@RequiredArgsConstructor
public class StandardAssociationController {

    private final StandardAssociationService service;

    /**
     * 检查标准是否存在当前有效落标字段。
     */
    @GetMapping("/has-columns")
    @Operation(summary = "检查标准是否存在当前有效落标字段")
    @PreAuthorize("@ss.hasPermission(#request.kind == 'element' ? 'governance:standard-element:query' : 'governance:standard-code-set:query')")
    public CommonResult<Boolean> hasColumns(@Valid StandardAssociationCheckReqVO request) {
        return CommonResult.success(!service.getColumns(request.getKind(), request.getId()).isEmpty());
    }
}
