package cn.iocoder.yudao.module.dataforge.controller.admin.filltable;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.*;
import cn.iocoder.yudao.module.dataforge.service.filltable.FillTableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据填报-填报表（模板）Controller
 *
 * 只承担模板定义管理；数据录入一律走在线填报审批链路
 * （fill-work-item 草稿 → 提交快照 → BPM 审批 → 合并入库），旧直写数据接口已移除
 *
 * @author Li
 */
@Tag(name = "管理后台 - 数据填报-填报表")
@RestController
@RequestMapping("/dataforge/fill-table")
@Validated
public class FillTableController {

    @Resource
    private FillTableService fillTableService;

    /**
     * 创建填报表。
     *
     * @param createReqVO 创建参数
     * @return 填报表
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据开发-数据填报-填报表", bizId = "#result")
    @Operation(summary = "创建填报表")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:create')")
    public CommonResult<Long> createFillTable(@Valid @RequestBody FillTableSaveReqVO createReqVO) {
        return success(fillTableService.createFillTable(createReqVO));
    }

    /**
     * 更新填报表（已发布需先取消发布）。
     *
     * @param updateReqVO 更新参数
     * @return 填报表
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据开发-数据填报-填报表", bizId = "#updateReqVO.id")
    @Operation(summary = "更新填报表（已发布需先取消发布）")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:update')")
    public CommonResult<Boolean> updateFillTable(@Valid @RequestBody FillTableSaveReqVO updateReqVO) {
        fillTableService.updateFillTable(updateReqVO);
        return success(true);
    }

    /**
     * 删除填报表（级联删除字段配置）。
     *
     * @param id 记录编号
     * @return 填报表
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据开发-数据填报-填报表", bizId = "#id")
    @Operation(summary = "删除填报表（级联删除字段配置）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:delete')")
    public CommonResult<Boolean> deleteFillTable(@RequestParam("id") Long id) {
        fillTableService.deleteFillTable(id);
        return success(true);
    }

    /**
     * 获得填报表。
     *
     * @param id 记录编号
     * @return 填报表响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得填报表")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:query')")
    public CommonResult<FillTableRespVO> getFillTable(@RequestParam("id") Long id) {
        return success(fillTableService.getFillTable(id));
    }

    /**
     * 获得填报表分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 填报表分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得填报表分页")
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:query')")
    public CommonResult<PageResult<FillTableRespVO>> getFillTablePage(@Valid FillTablePageReqVO pageReqVO) {
        return success(fillTableService.getFillTablePage(pageReqVO));
    }

    /**
     * 发布填报表（在目标数据源创建物理表）。
     *
     * @param id 记录编号
     * @return 填报表
     */
    @PutMapping("/release")
    @BusinessOperateLog(type = "数据开发-数据填报-填报表", bizId = "#id")
    @Operation(summary = "发布填报表（在目标数据源创建物理表）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:release')")
    public CommonResult<Boolean> releaseFillTable(@RequestParam("id") Long id) {
        fillTableService.releaseFillTable(id);
        return success(true);
    }

    /**
     * 取消发布（仅停用模板，不删除物理表；物理表退役走独立高权限流程）。
     *
     * @param id 记录编号
     * @return 填报表
     */
    @PutMapping("/cancel")
    @BusinessOperateLog(type = "数据开发-数据填报-填报表", bizId = "#id")
    @Operation(summary = "取消发布（仅停用模板，不删除物理表；物理表退役走独立高权限流程）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:release')")
    public CommonResult<Boolean> cancelFillTable(@RequestParam("id") Long id) {
        fillTableService.cancelFillTable(id);
        return success(true);
    }

    /**
     * 下载 Excel 模板。
     *
     * @param id 记录编号
     * @param response 响应对象
     */
    @GetMapping("/data/import-template")
    @Operation(summary = "下载 Excel 模板", description = "按字段配置生成含必填标记与字典下拉的模板；阶段 4 将改造为按模板版本生成")
    @Parameter(name = "id", description = "填报表编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:fill-table:query')")
    public void downloadImportTemplate(@RequestParam("id") Long id, HttpServletResponse response) {
        fillTableService.downloadImportTemplate(id, response);
    }

}
