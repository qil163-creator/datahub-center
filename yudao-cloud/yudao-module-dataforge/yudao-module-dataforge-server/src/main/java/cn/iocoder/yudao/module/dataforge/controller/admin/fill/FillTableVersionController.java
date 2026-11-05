package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTableVersionPublishReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillTableVersionRespVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTableVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-模板版本 Controller
 *
 * @author Li
 */
@Tag(name = "管理后台 - 在线填报模板版本")
@RestController
@RequestMapping("/dataforge/fill-table-version")
@Validated
public class FillTableVersionController {

    @Resource
    private FillTableVersionService tableVersionService;

    /**
     * 发布模板版本。
     *
     * @param reqVO 请求参数
     * @return 填报表版本
     */
    @PostMapping("/publish")
    @LogRecord(type = "数据开发-在线填报模板版本", subType = "发布模板版本", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "发布模板版本", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "发布模板版本", description = "从填报表当前字段配置固化不可变版本，模板进入审批流工作模式")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:release')")
    public CommonResult<Long> publishVersion(@Valid @RequestBody FillTableVersionPublishReqVO reqVO) {
        return success(tableVersionService.publishVersion(reqVO));
    }

    /**
     * 获得模板版本。
     *
     * @param id 记录编号
     * @return 填报表版本响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得模板版本")
    @Parameter(name = "id", description = "版本编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:query')")
    public CommonResult<FillTableVersionRespVO> getTableVersion(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(tableVersionService.getTableVersion(id), FillTableVersionRespVO.class));
    }

    /**
     * 获得填报表的版本列表。
     *
     * @param tableId 表编号
     * @return 填报表的版本列表结果集合
     */
    @GetMapping("/list-by-table")
    @Operation(summary = "获得填报表的版本列表")
    @Parameter(name = "tableId", description = "填报表编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:query')")
    public CommonResult<List<FillTableVersionRespVO>> getTableVersionListByTableId(
            @RequestParam("tableId") Long tableId) {
        return success(BeanUtils.toBean(tableVersionService.getTableVersionListByTableId(tableId),
                FillTableVersionRespVO.class));
    }

    /**
     * 停用模板版本。
     *
     * @param id 记录编号
     * @return 填报表版本
     */
    @PutMapping("/disable")
    @LogRecord(type = "数据开发-在线填报模板版本", subType = "停用模板版本", bizNo = "{{(#id) ?: 0}}",
            success = "停用模板版本", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "停用模板版本", description = "仅禁止新任务引用，不影响进行中任务")
    @Parameter(name = "id", description = "版本编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:release')")
    public CommonResult<Boolean> disableTableVersion(@RequestParam("id") Long id) {
        tableVersionService.disableTableVersion(id);
        return success(true);
    }

}
