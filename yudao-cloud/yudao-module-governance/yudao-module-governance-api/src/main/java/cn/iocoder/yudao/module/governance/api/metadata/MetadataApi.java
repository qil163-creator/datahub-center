package cn.iocoder.yudao.module.governance.api.metadata;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataFileRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnUpdateReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataRuntimeMetricsReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableIngestReqDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkCommandDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataCatalogLinkResultDTO;
import cn.iocoder.yudao.module.governance.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 元数据跨模块查询与治理属性维护接口。
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 元数据")
public interface MetadataApi {

    String PREFIX = ApiConstants.PREFIX + "/metadata";

    /**
     * 获得可编目的已发布表元数据分页。
     */
    @GetMapping(PREFIX + "/published-table-page")
    @Operation(summary = "获得可编目的已发布表元数据分页")
    CommonResult<PageResult<MetadataTableRespDTO>> getPublishedTablePage(
            @RequestParam("pageNo") Integer pageNo,
            @RequestParam("pageSize") Integer pageSize,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "onlyUnlinked", required = false) Boolean onlyUnlinked);

    /**
     * 分页读取当前有效文件元数据，供资产注册使用。
     */
    @GetMapping(PREFIX + "/current-file-page")
    @Operation(summary = "分页读取当前有效文件元数据，供资产注册使用")
    CommonResult<PageResult<MetadataFileRespDTO>> getCurrentFilePage(
            @RequestParam("pageNo") Integer pageNo, @RequestParam("pageSize") Integer pageSize,
            @RequestParam(value = "keyword", required = false) String keyword);

    /**
     * 读取当前有效文件元数据的注册投影。
     */
    @GetMapping(PREFIX + "/current-file")
    @Operation(summary = "读取当前有效文件元数据的注册投影")
    CommonResult<MetadataFileRespDTO> getCurrentFile(@RequestParam("id") Long id);

    /**
     * 获得表元数据。
     */
    @GetMapping(PREFIX + "/table")
    @Operation(summary = "获得表元数据")
    CommonResult<MetadataTableRespDTO> getTable(@RequestParam("id") Long id);

    /**
     * 获得表字段元数据。
     */
    @GetMapping(PREFIX + "/column-list")
    @Operation(summary = "获得表字段元数据")
    CommonResult<List<MetadataColumnRespDTO>> getColumnList(@RequestParam("tableId") Long tableId);

    /**
     * 按编号读取当前字段治理详情，供消费方完成对象授权后维护。
     */
    @GetMapping(PREFIX + "/column")
    @Operation(summary = "按编号读取当前字段治理详情，供消费方完成对象授权后维护")
    CommonResult<MetadataColumnRespDTO> getColumn(@RequestParam("id") Long id);

    /**
     * 获得同一发布快照的字段集合；无可信快照时返回空。
     */
    @GetMapping(PREFIX + "/published-column-list")
    @Operation(summary = "获得同一发布快照的字段集合；无可信快照时返回空")
    CommonResult<List<MetadataColumnRespDTO>> getPublishedColumnList(@RequestParam("tableId") Long tableId);

    /**
     * 按指定已发布技术快照读取完整字段。
     */
    @GetMapping(PREFIX + "/column-list-by-snapshot")
    @Operation(summary = "按指定已发布技术快照读取完整字段")
    CommonResult<List<MetadataColumnRespDTO>> getColumnListBySnapshot(
            @RequestParam("snapshotId") Long snapshotId);

    /**
     * 维护字段业务属性。
     */
    @PutMapping(PREFIX + "/update-column-biz")
    @Operation(summary = "维护字段业务属性")
    CommonResult<Boolean> updateColumnBiz(
            @Valid @RequestBody MetadataColumnUpdateReqDTO reqDTO);

    /**
     * 更新元数据的资产目录挂接状态。
     */
    @PutMapping(PREFIX + "/catalog-link")
    @Operation(summary = "更新元数据的资产目录挂接状态")
    CommonResult<Boolean> updateCatalogLink(
            @RequestParam("tableId") Long tableId,
            @RequestParam("linked") Boolean linked,
            @RequestParam("catalogItemUrn") String catalogItemUrn);

    /**
     * 幂等交付目录挂接来源版本。
     */
    @PostMapping(PREFIX + "/catalog-link-operation")
    @Operation(summary = "幂等交付目录挂接来源版本")
    CommonResult<MetadataCatalogLinkResultDTO> applyCatalogLink(
            @Valid @RequestBody MetadataCatalogLinkCommandDTO command);

    /**
     * 查询目录挂接原始操作回执。
     */
    @GetMapping(PREFIX + "/catalog-link-operation")
    @Operation(summary = "查询目录挂接原始操作回执")
    CommonResult<MetadataCatalogLinkResultDTO> queryCatalogLinkOperation(
            @RequestParam("operationId") String operationId);

    /**
     * 回写表的行数/存储量等运行指标。
     */
    @PostMapping(PREFIX + "/update-runtime-metrics")
    @Operation(summary = "回写表的行数/存储量等运行指标")
    CommonResult<Integer> updateRuntimeMetrics(@Valid @RequestBody MetadataRuntimeMetricsReqDTO reqDTO);

    /**
     * 物理表回写入元数据（建模部署 / 主数据发布）。
     */
    @PostMapping(PREFIX + "/ingest-physical-table")
    @Operation(summary = "物理表回写入元数据（建模部署 / 主数据发布）")
    CommonResult<Long> ingestPhysicalTable(@Valid @RequestBody MetadataTableIngestReqDTO reqDTO);

}
