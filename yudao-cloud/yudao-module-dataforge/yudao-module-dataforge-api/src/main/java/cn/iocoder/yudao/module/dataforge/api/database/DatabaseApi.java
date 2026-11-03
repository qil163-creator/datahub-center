package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.module.dataforge.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 数据源 RPC 接口：供其它模块获取数据源实例信息
 *
 * @author Li
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 数据源")
public interface DatabaseApi {

    String PREFIX = ApiConstants.PREFIX + "/database";

    /**
     * 获得数据源精简列表（不含密码）。
     *
     * @param usageType 用途类型
     * @return 数据源精简列表（不含密码）结果集合
     */
    @GetMapping(PREFIX + "/list")
    @Operation(summary = "获得数据源精简列表（不含密码）")
    CommonResult<List<DatabaseConnectionRespDTO>> getDatabaseList(
            @RequestParam(value = "usageType", required = false) Integer usageType);

    /**
     * 获得数据源连接信息（含解密密码）。
     *
     * @param id 记录编号
     * @return 数据源连接响应数据
     */
    @GetMapping(PREFIX + "/get-connection")
    @Operation(summary = "获得数据源连接信息（含解密密码）")
    CommonResult<DatabaseConnectionRespDTO> getDatabaseConnection(@RequestParam("id") Long id);

    /**
     * 获得数据源下的表名列表。
     *
     * @param id 记录编号
     * @return 数据源下的表名列表结果集合
     */
    @GetMapping(PREFIX + "/table-list")
    @Operation(summary = "获得数据源下的表名列表")
    CommonResult<List<String>> getTableNameList(@RequestParam("id") Long id);

    /**
     * 列出配置 schema 内的精确查询表身份（当前仅 PostgreSQL，最多 2000）。
     *
     * @param id 记录编号
     * @return 配置 schema 内的精确查询表身份（当前仅 PostgreSQL，最多 2000）结果集合
     */
    @GetMapping(PREFIX + "/query-table-list")
    @Operation(summary = "列出配置 schema 内的精确查询表身份（当前仅 PostgreSQL，最多 2000）")
    CommonResult<List<DatabaseQueryTableRespDTO>> getQueryTableList(@RequestParam("id") Long id);

    /**
     * 解析单独的 table/schema.table 引用，不接受 SQL 片段。
     *
     * @param id 记录编号
     * @param tableReference 表引用
     * @return 数据源精确查询表响应数据
     */
    @GetMapping(PREFIX + "/resolve-query-table")
    @Operation(summary = "解析单独的 table/schema.table 引用，不接受 SQL 片段")
    CommonResult<DatabaseQueryTableRespDTO> resolveQueryTable(@RequestParam("id") Long id,
            @RequestParam("tableReference") String tableReference);

    /**
     * 按物理 schema/table 和列投影读取零行元数据（不回退默认 schema）。
     *
     * @param request 请求参数
     * @return 按物理 schema/table 和列投影读取零行元数据（不回退默认 schema）结果集合
     */
    @PostMapping(PREFIX + "/query-table-columns")
    @Operation(summary = "按物理 schema/table 和列投影读取零行元数据（不回退默认 schema）")
    CommonResult<List<DatabaseColumnRespDTO>> getQueryTableColumns(
            @Valid @RequestBody DatabaseQueryColumnsReqDTO request);

    /**
     * 只读预览数据表（最多 100 行，不接受任意 SQL）。
     *
     * @param reqDTO 请求参数
     * @return 数据源预览响应数据
     */
    @PostMapping(PREFIX + "/preview-table")
    @Operation(summary = "只读预览数据表（最多 100 行，不接受任意 SQL）")
    CommonResult<DatabasePreviewRespDTO> previewTable(
            @Valid @RequestBody DatabasePreviewReqDTO reqDTO);

    /**
     * 获得数据源下指定表的字段列表。
     *
     * @param id 记录编号
     * @param tableName 表名
     * @return 数据源下指定表的字段列表结果集合
     */
    @GetMapping(PREFIX + "/table-column-list")
    @Operation(summary = "获得数据源下指定表的字段列表")
    CommonResult<List<DatabaseColumnRespDTO>> getTableColumnList(
            @RequestParam("id") Long id,
            @RequestParam("tableName") String tableName);

    /**
     * 只读执行单条 SELECT 查询（服务端只读校验 + 最大行数限制）。
     *
     * @param reqDTO 请求参数
     * @return 数据源预览响应数据
     */
    @PostMapping(PREFIX + "/execute-query")
    @Operation(summary = "只读执行单条 SELECT 查询（服务端只读校验 + 最大行数限制）")
    CommonResult<DatabasePreviewRespDTO> executeQuery(
            @Valid @RequestBody DatabaseQueryReqDTO reqDTO);

    /**
     * 解析并固定受限单表 SELECT 的物理 schema/table。
     *
     * @param reqDTO 请求参数
     * @return 受限 SELECT响应数据
     */
    @PostMapping(PREFIX + "/parse-restricted-select")
    @Operation(summary = "解析并固定受限单表 SELECT 的物理 schema/table",
            description = "当前精确定位仅验证 PostgreSQL；未限定表名使用数据源配置 schema。调用方须执行返回的 normalizedSql。")
    CommonResult<RestrictedSelectRespDTO> parseRestrictedSelect(
            @Valid @RequestBody DatabaseQueryReqDTO reqDTO);

}
