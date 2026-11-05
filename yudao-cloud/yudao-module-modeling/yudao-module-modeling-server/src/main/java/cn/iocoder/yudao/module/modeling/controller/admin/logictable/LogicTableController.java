package cn.iocoder.yudao.module.modeling.controller.admin.logictable;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo.*;
import cn.iocoder.yudao.module.modeling.service.logictable.LogicTableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据建模-逻辑表")
@RestController
@RequestMapping("/modeling/logic-table")
@Validated
public class LogicTableController {

    @Resource
    private LogicTableService logicTableService;

    /**
     * 获得逻辑表分页。
     *
     * @param reqVO 请求参数
     * @return 逻辑表分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得逻辑表分页")
    @PreAuthorize("@ss.hasPermission('modeling:logic-table:query')")
    public CommonResult<PageResult<LogicTableRespVO>> getLogicTablePage(@Valid LogicTablePageReqVO reqVO) {
        return success(logicTableService.getLogicTablePage(reqVO));
    }

    /**
     * 获得逻辑表类目树。
     *
     * @param tableType 表类型
     * @return 逻辑表类目树结果集合
     */
    @GetMapping("/cat-tree")
    @Operation(summary = "获得逻辑表类目树")
    @Parameter(name = "tableType", description = "表类型", required = true, example = "SOURCE")
    @PreAuthorize("@ss.hasPermission('modeling:logic-table:query')")
    public CommonResult<List<LogicTableCatNodeRespVO>> getCatTree(@RequestParam("tableType")
    @Pattern(regexp = "SOURCE|DIMENSION|DETAIL|SUMMARY|APPLICATION", message = "表类型不合法") String tableType) {
        return success(logicTableService.getCatTree(tableType));
    }

    /**
     * 获得启用的来源系统。
     *
     * @return 来源系统列表
     */
    @GetMapping("/source-system/list")
    @Operation(summary = "获得启用的来源系统")
    @PreAuthorize("@ss.hasPermission('modeling:logic-table:query')")
    public CommonResult<List<SourceSystemSimpleRespVO>> getSourceSystemList() {
        return success(logicTableService.getSourceSystemList());
    }

    /**
     * 启用或停用逻辑表。
     *
     * @param id 记录编号
     * @param enabled 启用状态
     * @return 逻辑表
     */
    @LogRecord(type = "数据建模-逻辑表", subType = "启用或停用逻辑表", bizNo = "{{(#id) ?: 0}}",
            success = "启用或停用逻辑表", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-enabled")
    @Operation(summary = "启用或停用逻辑表")
    @PreAuthorize("@ss.hasPermission('modeling:logic-table:update')")
    public CommonResult<Boolean> updateEnabled(@RequestParam("id") Long id,
            @RequestParam("enabled") @Min(0) @Max(1) Integer enabled) {
        logicTableService.updateEnabled(id, enabled);
        return success(true);
    }

    /**
     * 批量删除前检查可删除的逻辑表。
     *
     * @param ids 记录编号集合
     * @return 逻辑表删除检查响应数据
     */
    @GetMapping("/delete-check")
    @Operation(summary = "批量删除前检查可删除的逻辑表")
    @Parameter(name = "ids", description = "编号列表", required = true, example = "1,2")
    @PreAuthorize("@ss.hasPermission('modeling:logic-table:delete')")
    public CommonResult<LogicTableDeleteCheckRespVO> checkDelete(@RequestParam("ids") @NotEmpty List<Long> ids) {
        return success(logicTableService.checkDelete(ids));
    }

    /**
     * 删除逻辑表（支持批量）。
     *
     * @param ids 记录编号集合
     * @return 逻辑表
     */
    @LogRecord(type = "数据建模-逻辑表", subType = "删除逻辑表（支持批量）", bizNo = "{{(#ids) ?: 0}}",
            success = "删除逻辑表（支持批量）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除逻辑表（支持批量）")
    @Parameter(name = "ids", description = "编号列表", required = true, example = "1,2")
    @PreAuthorize("@ss.hasPermission('modeling:logic-table:delete')")
    public CommonResult<Boolean> deleteLogicTables(@RequestParam("ids") @NotEmpty List<Long> ids) {
        logicTableService.deleteLogicTables(ids);
        return success(true);
    }

}
