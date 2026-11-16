package cn.iocoder.yudao.module.governance.controller.admin.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.service.master.MasterMatchService;
import cn.iocoder.yudao.module.governance.service.master.MasterModelService;
import com.mzt.logapi.starter.annotation.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 主数据相似匹配、去重合并与黄金记录（主数据在平台内共享，按接口权限控制）。
 */
@Tag(name = "管理后台 - 主数据相似匹配与合并")
@RestController
@RequestMapping("/governance/master-match")
@Validated
public class MasterMatchController {

    @Resource
    private MasterMatchService matchService;

    @Resource
    private MasterModelService modelService;

    @GetMapping("/model-page")
    @Operation(summary = "分页查询可进行相似匹配的已发布模型")
    @PreAuthorize("@ss.hasPermission('governance:master-match:query')")
    public CommonResult<PageResult<MasterModelRespVO>> getModelPage(@Valid MasterModelPageReqVO req) {
        return success(modelService.getMaintenanceModelPage(req));
    }

    @GetMapping("/model")
    @Operation(summary = "获取可进行相似匹配的模型详情（含字段）")
    @PreAuthorize("@ss.hasPermission('governance:master-match:query')")
    public CommonResult<MasterModelRespVO> getModel(@RequestParam("id") Long id) {
        return success(modelService.getMaintenanceModel(id));
    }

    @GetMapping("/rule")
    @Operation(summary = "获取模型的相似匹配规则")
    @PreAuthorize("@ss.hasPermission('governance:master-match:query')")
    public CommonResult<MasterMatchRuleVO> getRule(@RequestParam("modelId") Long modelId) {
        return success(matchService.getRule(modelId));
    }

    @PutMapping("/rule")
    @Operation(summary = "保存模型的相似匹配规则")
    @LogRecord(type = "数据治理-主数据相似匹配", subType = "保存匹配规则", bizNo = "{{#reqVO.modelId}}",
            success = "保存了模型【{{#reqVO.modelId}}】的相似匹配规则，阈值【{{#reqVO.threshold}}】",
            extra = "BUSINESS_OPERATION")
    @PreAuthorize("@ss.hasPermission('governance:master-match:rule')")
    public CommonResult<MasterMatchRuleVO> saveRule(@Valid @RequestBody MasterMatchRuleVO reqVO) {
        return success(matchService.saveRule(reqVO));
    }

    @PostMapping("/run")
    @Operation(summary = "执行一次相似匹配")
    @LogRecord(type = "数据治理-主数据相似匹配", subType = "执行相似匹配", bizNo = "{{(#_ret.data.id) ?: 0}}",
            success = "执行了模型【{{#modelId}}】的相似匹配，新增疑似重复组【{{(#_ret.data.groupCount) ?: 0}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    @PreAuthorize("@ss.hasPermission('governance:master-match:run')")
    public CommonResult<MasterMatchRunRespVO> run(@RequestParam("modelId") Long modelId) {
        return success(matchService.run(modelId));
    }

    @GetMapping("/run-page")
    @Operation(summary = "分页查询匹配执行记录")
    @PreAuthorize("@ss.hasPermission('governance:master-match:query')")
    public CommonResult<PageResult<MasterMatchRunRespVO>> getRunPage(@Valid MasterMatchRunPageReqVO req) {
        return success(matchService.runPage(req));
    }

    @GetMapping("/group-page")
    @Operation(summary = "分页查询疑似重复组")
    @PreAuthorize("@ss.hasPermission('governance:master-match:query')")
    public CommonResult<PageResult<MasterMatchGroupRespVO>> getGroupPage(@Valid MasterMatchGroupPageReqVO req) {
        return success(matchService.groupPage(req));
    }

    @GetMapping("/group")
    @Operation(summary = "获取疑似重复组详情与黄金记录建议")
    @PreAuthorize("@ss.hasPermission('governance:master-match:query')")
    public CommonResult<MasterMatchGroupRespVO> getGroup(@RequestParam("id") Long id) {
        return success(matchService.group(id));
    }

    @PostMapping("/merge")
    @Operation(summary = "提交疑似重复组合并，按模型审批配置生效")
    @LogRecord(type = "数据治理-主数据相似匹配", subType = "提交合并", bizNo = "{{(#_ret.data.id) ?: 0}}",
            success = "提交了疑似重复组【{{#reqVO.groupId}}】的合并，保留记录【{{#reqVO.survivorKey}}】，"
                    + "变更单编号【{{(#_ret.data.id) ?: 0}}】，受理状态【{{(#_ret.data.status) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0}}")
    @PreAuthorize("@ss.hasPermission('governance:master-match:merge')")
    public CommonResult<MasterDataMutationRespVO> merge(@Valid @RequestBody MasterMatchMergeReqVO reqVO) {
        return success(matchService.merge(reqVO));
    }

    @PostMapping("/ignore")
    @Operation(summary = "忽略疑似重复组")
    @LogRecord(type = "数据治理-主数据相似匹配", subType = "忽略疑似重复", bizNo = "{{#reqVO.groupId}}",
            success = "忽略了疑似重复组【{{#reqVO.groupId}}】，说明【{{(#reqVO.remark) ?: '无'}}】",
            extra = "BUSINESS_OPERATION")
    @PreAuthorize("@ss.hasPermission('governance:master-match:ignore')")
    public CommonResult<Boolean> ignore(@Valid @RequestBody MasterMatchIgnoreReqVO reqVO) {
        matchService.ignore(reqVO);
        return success(true);
    }

    @GetMapping("/golden-page")
    @Operation(summary = "分页查询黄金记录及合并来源")
    @PreAuthorize("@ss.hasPermission('governance:master-match:query')")
    public CommonResult<PageResult<MasterGoldenRespVO>> getGoldenPage(@Valid MasterGoldenPageReqVO req) {
        return success(matchService.goldenPage(req));
    }

}
