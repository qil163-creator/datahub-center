package cn.iocoder.yudao.module.dataforge.controller.admin.driver;

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
    @Operation(summary = "更新数据源驱动管理")
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:update')")
    public CommonResult<Boolean> updateDatasourceDriver(@Valid @RequestBody DatasourceDriverSaveReqVO updateReqVO) {
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
    @Operation(summary = "删除数据源驱动管理")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:delete')")
    public CommonResult<Boolean> deleteDatasourceDriver(@RequestParam("id") Long id) {
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
    @Operation(summary = "批量删除数据源驱动管理")
    @PreAuthorize("@ss.hasPermission('dataforge:datasource-driver:delete')")
    public CommonResult<Boolean> deleteDatasourceDriverList(@RequestParam("ids") List<Long> ids) {
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
