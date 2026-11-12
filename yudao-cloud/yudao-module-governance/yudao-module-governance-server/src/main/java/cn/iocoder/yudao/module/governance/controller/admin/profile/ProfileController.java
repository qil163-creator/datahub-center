package cn.iocoder.yudao.module.governance.controller.admin.profile;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileLatestRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunCreateReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunRespVO;
import cn.iocoder.yudao.module.governance.service.profile.ProfileRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 数据探查。数据范围与元数据表查询一致（平台内全可见），由接口权限控制。
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据探查")
@RestController
@RequestMapping("/governance/profile")
@Validated
public class ProfileController {

    @Resource
    private ProfileRunService profileRunService;

    /**
     * 发起数据探查。
     */
    @PostMapping("/run")
    @Operation(summary = "发起数据探查", description = "插入一条等待执行的探查记录；同一张表同时只允许一条等待或执行中的记录")
    @PreAuthorize("@ss.hasPermission('governance:profile:run')")
    public CommonResult<Long> createRun(@Valid @RequestBody ProfileRunCreateReqVO reqVO) {
        return success(profileRunService.createRun(reqVO.getMetaTableId(), SecurityFrameworkUtils.getLoginUserId()));
    }

    /**
     * 获得最新探查结果与规则建议。
     */
    @GetMapping("/latest")
    @Operation(summary = "获得最新探查结果与规则建议")
    @Parameter(name = "metaTableId", description = "元数据表编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('governance:profile:query')")
    public CommonResult<ProfileLatestRespVO> getLatest(@RequestParam("metaTableId") Long metaTableId) {
        return success(profileRunService.getLatest(metaTableId));
    }

    /**
     * 获得探查记录分页。
     */
    @GetMapping("/run/page")
    @Operation(summary = "获得探查记录分页")
    @PreAuthorize("@ss.hasPermission('governance:profile:query')")
    public CommonResult<PageResult<ProfileRunRespVO>> getRunPage(@Valid ProfileRunPageReqVO reqVO) {
        return success(profileRunService.getRunPage(reqVO));
    }

}
