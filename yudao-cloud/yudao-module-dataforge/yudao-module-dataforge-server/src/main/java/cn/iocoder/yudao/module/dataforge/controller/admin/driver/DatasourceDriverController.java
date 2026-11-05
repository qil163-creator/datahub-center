package cn.iocoder.yudao.module.dataforge.controller.admin.driver;

import com.mzt.logapi.starter.annotation.LogRecord;
import com.mzt.logapi.context.LogRecordContext;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo.DatasourceDriverPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo.DatasourceDriverRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo.DatasourceDriverSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.driver.DatasourceDriverDO;
import cn.iocoder.yudao.module.dataforge.service.driver.DatasourceDriverService;
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

@Tag(name = "管理后台 - 数据源驱动管理")
@RestController
@RequestMapping("/dataforge/datasource-driver")
@Validated
public class DatasourceDriverController {

    @Resource
    private DatasourceDriverService datasourceDriverService;

    /**
     * 创建数据源驱动管理。
     *
     * @param createReqVO 创建参数
     * @return 数据源驱动
     */
    @PostMapping("/create")
    @LogRecord(type = "数据开发-数据源驱动管理", subType = "创建数据源驱动管理", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了数据源驱动【{{(#createReqVO.driverName) ?: '未设置'}}】，驱动编码【{{(#createReqVO.driverCode) ?: '未设置'}}】，产品类型【{{(#createReqVO.productType) ?: '未设置'}}】，状态【{{(#createReqVO.status) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "创建数据源驱动管理")
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:create')")
    public CommonResult<Long> createDatasourceDriver(@Valid @RequestBody DatasourceDriverSaveReqVO createReqVO) {
        return success(datasourceDriverService.createDatasourceDriver(createReqVO));
    }

    /**
     * 更新数据源驱动管理。
     *
     * @param updateReqVO 更新参数
     * @return 数据源驱动
     */
    @PutMapping("/update")
    @LogRecord(type = "数据开发-数据源驱动管理", subType = "更新数据源驱动管理", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了数据源驱动【{{(#operateName) ?: '未设置'}}】，名称从【{{(#operateName) ?: '未设置'}}】改为【{{(#updateReqVO.driverName) ?: '未设置'}}】，驱动编码【{{(#updateReqVO.driverCode) ?: '未设置'}}】，产品类型【{{(#updateReqVO.productType) ?: '未设置'}}】，状态【{{(#updateReqVO.status) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "更新数据源驱动管理")
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:update')")
    public CommonResult<Boolean> updateDatasourceDriver(@Valid @RequestBody DatasourceDriverSaveReqVO updateReqVO) {
        String operateName = "编号" + updateReqVO.getId();
        try {
            var operateObject = datasourceDriverService.getDatasourceDriver(updateReqVO.getId());
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getDriverName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        datasourceDriverService.updateDatasourceDriver(updateReqVO);
        return success(true);
    }

    /**
     * 删除数据源驱动管理。
     *
     * @param id 记录编号
     * @return 数据源驱动
     */
    @DeleteMapping("/delete")
    @LogRecord(type = "数据开发-数据源驱动管理", subType = "删除数据源驱动管理", bizNo = "{{(#id) ?: 0}}",
            success = "删除了数据源驱动【{{(#operateName) ?: '未设置'}}】（编号【{{(#id) ?: '未设置'}}】）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "删除数据源驱动管理")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:delete')")
    public CommonResult<Boolean> deleteDatasourceDriver(@RequestParam("id") Long id) {
        String operateName = "编号" + id;
        try {
            var operateObject = datasourceDriverService.getDatasourceDriver(id);
            if (operateObject != null) {
                operateName = java.util.Objects.toString(operateObject.getDriverName(), operateName);
            }
        } catch (RuntimeException ignored) {
            // 审计名称读取失败时保留编号，不改变原有业务权限、校验和写入结果。
        }
        LogRecordContext.putVariable("operateName", operateName);

        datasourceDriverService.deleteDatasourceDriver(id);
        return success(true);
    }

    /**
     * 批量删除数据源驱动管理。
     *
     * @param ids 记录编号集合
     * @return 数据源驱动
     */
    @DeleteMapping("/delete-list")
    @Parameter(name = "ids", description = "编号", required = true)
    @LogRecord(type = "数据开发-数据源驱动管理", subType = "批量删除数据源驱动管理", bizNo = "{{(#ids) ?: 0}}",
            success = "批量删除了数据源驱动【{{(#operateNames) ?: '未设置'}}】，请求编号【{{(#ids) ?: '未设置'}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @Operation(summary = "批量删除数据源驱动管理")
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:delete')")
    public CommonResult<Boolean> deleteDatasourceDriverList(@RequestParam("ids") List<Long> ids) {
        var operateNames = new java.util.ArrayList<String>();
        for (Long operateId : ids) {
            try {
                var operateObject = datasourceDriverService.getDatasourceDriver(operateId);
                if (operateObject != null) {
                    operateNames.add(java.util.Objects.toString(operateObject.getDriverName(), "编号" + operateId) + "（编号" + operateId + "）");
                }
            } catch (RuntimeException ignored) {
                // 仅尽力读取日志名称，业务批量入口继续负责缺失对象的处理。
            }
        }
        LogRecordContext.putVariable("operateNames", operateNames);

        datasourceDriverService.deleteDatasourceDriverListByIds(ids);
        return success(true);
    }

    /**
     * 获得数据源驱动管理。
     *
     * @param id 记录编号
     * @return 数据源驱动响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得数据源驱动管理")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:query')")
    public CommonResult<DatasourceDriverRespVO> getDatasourceDriver(@RequestParam("id") Long id) {
        DatasourceDriverDO datasourceDriver = datasourceDriverService.getDatasourceDriver(id);
        return success(BeanUtils.toBean(datasourceDriver, DatasourceDriverRespVO.class));
    }

    /**
     * 获得数据源驱动管理分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 数据源驱动管理分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得数据源驱动管理分页")
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:query')")
    public CommonResult<PageResult<DatasourceDriverRespVO>> getDatasourceDriverPage(
            @Valid DatasourceDriverPageReqVO pageReqVO) {
        PageResult<DatasourceDriverDO> pageResult = datasourceDriverService.getDatasourceDriverPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, DatasourceDriverRespVO.class));
    }

    /**
     * 导出数据源驱动管理 Excel。
     *
     * @param pageReqVO 分页查询条件
     * @param response 响应对象
     */
    @GetMapping("/export-excel")
    @Operation(summary = "导出数据源驱动管理 Excel")
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:export')")
    @ApiAccessLog(operateType = EXPORT)
    public void exportDatasourceDriverExcel(@Valid DatasourceDriverPageReqVO pageReqVO,
            HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<DatasourceDriverDO> list = datasourceDriverService.getDatasourceDriverPage(pageReqVO).getList();
        // 导出 Excel
        ExcelUtils.write(response, "数据源驱动管理.xls", "数据", DatasourceDriverRespVO.class,
                BeanUtils.toBean(list, DatasourceDriverRespVO.class));
    }

}
