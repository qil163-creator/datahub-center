package cn.iocoder.yudao.module.dataforge.controller.admin.databasetype;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeListReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypePageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeStatusReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据库类型定义")
@RestController
@RequestMapping("/dataforge/database-type")
@Validated
public class DatabaseTypeController {

    @Resource
    private DatabaseTypeService databaseTypeService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeCapabilityService capabilityService;

    /**
     * 创建数据库类型定义。
     *
     * @param createReqVO 创建参数
     * @return 数据库类型
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-数据库类型定义", subType = "创建数据库类型定义", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建数据库类型定义", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建数据库类型定义")
    @PreAuthorize("@ss.hasPermission('dataforge:database-type:create')")
    public CommonResult<Long> createDatabaseType(@Valid @RequestBody DatabaseTypeSaveReqVO createReqVO) {
        return success(databaseTypeService.createDatabaseType(createReqVO));
    }

    /**
     * 更新数据库类型定义。
     *
     * @param updateReqVO 更新参数
     * @return 数据库类型
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-数据库类型定义", subType = "更新数据库类型定义", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "更新数据库类型定义", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新数据库类型定义")
    @PreAuthorize("@ss.hasPermission('dataforge:database-type:update')")
    public CommonResult<Boolean> updateDatabaseType(@Valid @RequestBody DatabaseTypeSaveReqVO updateReqVO) {
        databaseTypeService.updateDatabaseType(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-status")
    @LogRecord(type = "数据开发-数据库类型定义", subType = "更新数据库类型状态", bizNo = "{{(#request.id) ?: 0}}",
            success = "更新数据库类型状态", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新数据库类型状态")
    @PreAuthorize("@ss.hasPermission('dataforge:database-type:update')")
    public CommonResult<Boolean> updateDatabaseTypeStatus(@Valid @RequestBody DatabaseTypeStatusReqVO request) {
        databaseTypeService.updateDatabaseTypeStatus(request);
        return success(true);
    }

    /**
     * 删除数据库类型定义。
     *
     * @param id 记录编号
     * @return 数据库类型
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-数据库类型定义", subType = "删除数据库类型定义", bizNo = "{{(#id) ?: 0}}",
            success = "删除数据库类型定义", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除数据库类型定义")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:database-type:delete')")
    public CommonResult<Boolean> deleteDatabaseType(@RequestParam("id") Long id) {
        databaseTypeService.deleteDatabaseType(id);
        return success(true);
    }

    /**
     * 批量删除数据库类型定义。
     *
     * @param ids 记录编号集合
     * @return 数据库类型
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @LogRecord(type = "数据开发-数据库类型定义", subType = "批量删除数据库类型定义", bizNo = "{{(#ids) ?: 0}}",
            success = "批量删除数据库类型定义", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除数据库类型定义")
    @PreAuthorize("@ss.hasPermission('dataforge:database-type:delete')")
    public CommonResult<Boolean> deleteDatabaseTypeList(@RequestParam("ids") List<Long> ids) {
        databaseTypeService.deleteDatabaseTypeListByIds(ids);
        return success(true);
    }

    /**
     * 获得数据库类型定义。
     *
     * @param id 记录编号
     * @return 数据库类型响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据库类型定义")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('dataforge:database-type:query')")
    public CommonResult<DatabaseTypeRespVO> getDatabaseType(@RequestParam("id") Long id) {
        DatabaseTypeDO databaseType = databaseTypeService.getDatabaseType(id);
        return success(capabilityService.describe(databaseType));
    }

    /**
     * 获得数据库类型定义分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 数据库类型定义分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据库类型定义分页")
    @PreAuthorize("@ss.hasPermission('dataforge:database-type:query')")
    public CommonResult<PageResult<DatabaseTypeRespVO>> getDatabaseTypePage(@Valid DatabaseTypePageReqVO pageReqVO) {
        PageResult<DatabaseTypeDO> pageResult = databaseTypeService.getDatabaseTypePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, DatabaseTypeRespVO.class));
    }

    /**
     * 获得开启的数据库类型列表。
     *
     * @param listReqVO 列表请求参数
     * @return 开启的数据库类型列表结果集合
     */
    @GetMapping("/simple-list")
    @Operation(summary = "获得开启的数据库类型列表", description = "按连接分类筛选，用于新建连接选择")
    public CommonResult<List<DatabaseTypeRespVO>> getDatabaseTypeSimpleList(@Valid DatabaseTypeListReqVO listReqVO) {
        List<DatabaseTypeDO> list = databaseTypeService.getDatabaseTypeList(listReqVO);
        return success(list.stream().map(capabilityService::describe).toList());
    }

    /**
     * 导出数据库类型定义 Excel。
     *
     * @param pageReqVO 分页查询条件
     * @param response 响应对象
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据库类型定义 Excel")
    @PreAuthorize("@ss.hasPermission('dataforge:database-type:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportDatabaseTypeExcel(@Valid DatabaseTypePageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DatabaseTypeDO> list = databaseTypeService.getDatabaseTypePage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "数据库类型定义.xls", "数据", DatabaseTypeRespVO.class,
                BeanUtils.toBean(list, DatabaseTypeRespVO.class));
    }

}
