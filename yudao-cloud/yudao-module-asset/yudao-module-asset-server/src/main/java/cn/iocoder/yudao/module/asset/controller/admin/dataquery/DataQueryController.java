package cn.iocoder.yudao.module.asset.controller.admin.dataquery;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryColumnRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryDatasourceRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryExecuteReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryResultRespVO;
import cn.iocoder.yudao.module.asset.service.dataquery.DataQueryService;
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

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 数据查询工作台")
@RestController
@RequestMapping("/asset/data-query")
@Validated
public class DataQueryController {

    @Resource
    private DataQueryService dataQueryService;

    /**
     * 获得可查询的数据源列表
     *
     *  @return 数据源列表
     */
    @GetMapping("/datasource-list")
    @Operation(summary = "获得可查询的数据源列表")
    @PreAuthorize("@ss.hasPermission('asset:data-query:query')")
    public CommonResult<List<DataQueryDatasourceRespVO>> getDatasourceList() {
        return success(dataQueryService.getDatasourceList());
    }

    /**
     * 获得数据源下的表名列表
     *
     *  @param datasourceId 数据源编号
     *  @return 表名列表
     */
    @GetMapping("/table-list")
    @Operation(summary = "获得数据源下的表名列表")
    @Parameter(name = "datasourceId", description = "数据源编号", required = true)
    @PreAuthorize("@ss.hasPermission('asset:data-query:query')")
    public CommonResult<List<String>> getTableList(
            @RequestParam("datasourceId") Long datasourceId) {
        return success(dataQueryService.getTableList(datasourceId));
    }

    /**
     * 获得数据源下指定表的字段列表
     *
     *  @param datasourceId 数据源编号
     *  @param tableName    表名
     *  @return 字段列表
     */
    @GetMapping("/column-list")
    @Operation(summary = "获得数据源下指定表的字段列表")
    @Parameter(name = "datasourceId", description = "数据源编号", required = true)
    @Parameter(name = "tableName", description = "表名", required = true)
    @PreAuthorize("@ss.hasPermission('asset:data-query:query')")
    public CommonResult<List<DataQueryColumnRespVO>> getColumnList(
            @RequestParam("datasourceId") Long datasourceId,
            @RequestParam("tableName") String tableName) {
        return success(dataQueryService.getColumnList(datasourceId, tableName));
    }

    /**
     * 只读执行单条 SELECT 查询
     *
     *  @param reqVO 查询请求
     *  @return 查询结果
     */
    @PostMapping("/execute")
    @Operation(summary = "只读执行单条 SELECT 查询")
    @PreAuthorize("@ss.hasPermission('asset:data-query:execute')")
    public CommonResult<DataQueryResultRespVO> executeQuery(
            @Valid @RequestBody DataQueryExecuteReqVO reqVO) {
        return success(dataQueryService.executeQuery(reqVO));
    }

}
