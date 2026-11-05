package cn.iocoder.yudao.module.asset.controller.admin.market;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.service.market.MarketCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@RequestMapping("/asset/market/check")
@Validated
@Tag(name = "管理后台 - 服务审批")
public class MarketCheckController {

    @Resource
    private MarketCheckService checkService;

    /**
     * 服务审批分页；来源工单按审批权限平台可见，本地申请按提供方对象范围。
     */
    @GetMapping("/page")
    @Operation(summary = "服务审批分页；来源工单按审批权限平台可见，本地申请按提供方对象范围")
    @PreAuthorize("@ss.hasPermission('asset:market:check')")
    public CommonResult<PageResult<MarketCheckTicketRespVO>> page(@Valid MarketCheckPageReqVO req) {
        return success(checkService.getPage(req));
    }

    /**
     * 服务审批只读详情；来源快照不代表当前访问授权。
     */
    @GetMapping("/get")
    @Operation(summary = "服务审批只读详情；来源快照不代表当前访问授权")
    @PreAuthorize("@ss.hasPermission('asset:market:check')")
    public CommonResult<MarketCheckDetailRespVO> get(
            @RequestParam("id") @NotBlank @Size(max = 128) String id,
            @RequestParam("origin") @Pattern(regexp = "NATIVE|QDATA") String origin) {
        return success(checkService.get(id, origin));
    }

    /**
     * 可见工单申请人筛选，最多返回五百项；来源人员不作为本地身份。
     */
    @GetMapping("/applicants")
    @Operation(summary = "可见工单申请人筛选，最多返回五百项；来源人员不作为本地身份")
    @PreAuthorize("@ss.hasPermission('asset:market:check')")
    public CommonResult<List<MarketCheckApplicantRespVO>> applicants() {
        return success(checkService.applicants());
    }

    /**
     * 保存审批意见；来源工单不授予真实数据权限，本地申请复用原审批链。
     */
    @BusinessOperateLog(type = "数据资产-服务审批", action = "保存服务审批意见", bizId = "#req.origin == 'NATIVE' ? #req.id : 0")
    @PutMapping("/check")
    @Operation(summary = "保存审批意见；来源工单不授予真实数据权限，本地申请复用原审批链")
    @PreAuthorize("@ss.hasPermission('asset:market:check')")
    public CommonResult<Boolean> check(@Valid @RequestBody MarketCheckDecisionReqVO req) {
        checkService.check(req);
        return success(true);
    }
}
