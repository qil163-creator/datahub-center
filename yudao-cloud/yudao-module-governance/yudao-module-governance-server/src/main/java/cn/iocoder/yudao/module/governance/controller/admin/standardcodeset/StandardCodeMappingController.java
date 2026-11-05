package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.*;
import cn.iocoder.yudao.module.governance.service.standardcodeset.StandardCodeMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 码值映射")
@RestController
@RequestMapping("/governance/standard-code-set")
@Validated
@RequiredArgsConstructor
public class StandardCodeMappingController {

    private final StandardCodeMappingService service;

    /**
     * 查询关联配置分页。
     */
    @GetMapping("/mapping-page")
    @Operation(summary = "查询关联配置分页")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<PageResult<StandardCodeMappingRespVO>> page(@Valid StandardCodeMappingPageReqVO request) {
        return success(service.page(request));
    }

    /**
     * 查询关联配置详情。
     */
    @GetMapping("/mapping-get")
    @Operation(summary = "查询关联配置详情")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:query')")
    public CommonResult<StandardCodeMappingRespVO> get(@RequestParam Integer codeSetId, @RequestParam Long id) {
        return success(service.get(codeSetId, id));
    }

    /**
     * 创建关联配置。
     */
    @PostMapping("/mapping-create")
    @LogRecord(type = "数据治理-码值映射", subType = "创建关联配置",
            bizNo = "{{(#_ret.data) ?: 0}}", success = "为码表【{{(#request.codeSetId) ?: '未设置'}}】创建了码值映射【{{(#_ret.data) ?: 0}}】，目标码值编号【{{(#request.codeItemId) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建关联配置")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:update')")
    public CommonResult<Long> create(@Valid @RequestBody StandardCodeMappingSaveReqVO request) {
        return success(service.create(request));
    }

    /**
     * 更新关联配置。
     */
    @PutMapping("/mapping-update")
    @LogRecord(type = "数据治理-码值映射", subType = "更新关联配置",
            bizNo = "{{(#request.id) ?: 0}}", success = "修改了码表【{{(#request.codeSetId) ?: '未设置'}}】的码值映射【{{(#request.id) ?: 0}}】，目标码值编号【{{(#request.codeItemId) ?: '未设置'}}】，提交修订【{{(#request.revision) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新关联配置")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody StandardCodeMappingSaveReqVO request) {
        service.update(request);
        return success(true);
    }

    /**
     * 删除关联配置。
     */
    @DeleteMapping("/mapping-delete")
    @LogRecord(type = "数据治理-码值映射", subType = "删除关联配置",
            bizNo = "{{(#id) ?: 0}}", success = "删除了码表【{{(#codeSetId) ?: '未设置'}}】的码值映射【{{(#id) ?: '未设置'}}】",
            extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除关联配置")
    @PreAuthorize("@ss.hasPermission('governance:standard-code-set:update')")
    public CommonResult<Boolean> delete(@RequestParam Integer codeSetId, @RequestParam Long id) {
        service.delete(codeSetId, id);
        return success(true);
    }

}
