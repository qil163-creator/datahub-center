package cn.iocoder.yudao.module.modeling.controller.admin.businessentity;

import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.*;
import cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 业务实体")
@RestController
@RequestMapping("/modeling/business-entity")
@Validated
public class BusinessEntityController {

    @Resource
    private BusinessEntityService service;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityVersionService versions;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityGraphService graphService;

    /**
     * 新增业务实体。
     *
     * @param request 请求参数
     * @return 业务实体
     */
    @LogRecord(type = "数据建模-业务实体", subType = "新增业务实体", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了业务实体【{{#request.name}}】，编码【{{#request.code}}】，编号【{{#_ret.data}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "新增业务实体")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:create')")
    public CommonResult<Long> create(@Valid @RequestBody BusinessEntitySaveReqVO request) {
        return success(service.create(request));
    }

    /**
     * 修改业务实体及关系。
     *
     * @param request 请求参数
     * @return 业务实体
     */
    @LogRecord(type = "数据建模-业务实体", subType = "修改业务实体及关系", bizNo = "{{(#request.id) ?: 0}}",
            success = "修改了业务实体【{{#request.name}}】（编号{{#request.id}}），编码【{{#request.code}}】，校验修订【{{#request.expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "修改业务实体及关系")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody BusinessEntityUpdateReqVO request) {
        service.update(request);
        return success(true);
    }

    /**
     * 上线或下线业务实体。
     *
     * @param request 请求参数
     * @return 业务实体
     */
    @PutMapping("/update-status")
    @Operation(summary = "上线或下线业务实体")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:publish')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody BusinessEntityStatusReqVO request) {
        service.updateStatus(request);
        return success(true);
    }

    /**
     * 原子批量上线或下线业务实体。
     *
     * @param request 请求参数
     * @return 业务实体
     */
    @PutMapping("/update-status-batch")
    @Operation(summary = "原子批量上线或下线业务实体")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:publish')")
    public CommonResult<Boolean> updateStatusBatch(@Valid @RequestBody BusinessEntityBatchReqVO request) {
        service.updateStatusBatch(request);
        return success(true);
    }

    /**
     * 删除业务实体。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 业务实体
     */
    @LogRecord(type = "数据建模-业务实体", subType = "删除业务实体", bizNo = "{{(#id) ?: 0}}",
            success = "删除了业务实体【编号{{#id}}】，校验修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除业务实体")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:delete')")
    public CommonResult<Boolean> delete(@RequestParam @Positive Long id,
            @RequestParam @PositiveOrZero Long expectedRevision) {
        service.delete(id, expectedRevision);
        return success(true);
    }

    /**
     * 原子批量删除业务实体。
     *
     * @param request 请求参数
     * @return 业务实体
     */
    @LogRecord(type = "数据建模-业务实体", subType = "原子批量删除业务实体", bizNo = "{{(#request.targets.![id]) ?: 0}}",
            success = "批量删除了业务实体，编号集合【{{#request.targets.![id]}}】，共【{{#request.targets.size()}}】项，按各项修订校验", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete-list")
    @Operation(summary = "原子批量删除业务实体")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:delete')")
    public CommonResult<Boolean> deleteBatch(@Valid @RequestBody BusinessEntityBatchReqVO request) {
        service.deleteBatch(request);
        return success(true);
    }

    /**
     * 获得业务实体及其关系。
     *
     * @param id 记录编号
     * @return 业务实体响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得业务实体及其关系")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:query')")
    public CommonResult<BusinessEntityRespVO> get(@RequestParam @Positive Long id) {
        return success(service.get(id));
    }

    /**
     * 获得业务实体分页。
     *
     * @param request 请求参数
     * @return 业务实体分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得业务实体分页")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:query')")
    public CommonResult<PageResult<BusinessEntityRespVO>> page(@Valid BusinessEntityPageReqVO request) {
        return success(service.page(request));
    }

    /** 获得按根节点分页的流程包含关系树，筛选条件逐实体生效。 */
    @GetMapping("/tree-page")
    @Operation(summary = "获得业务实体包含关系树分页")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:query')")
    public CommonResult<PageResult<BusinessEntityRespVO>> treePage(@Valid BusinessEntityPageReqVO request) {
        return success(service.treePage(request));
    }

    /**
     * 获得板块实体及流程占用约束。
     *
     * @param dataDomainId 数据域编号
     * @param excludeId 排除编号
     * @return 业务实体选项响应数据
     */
    @GetMapping("/options")
    @Operation(summary = "获得板块实体及流程占用约束")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:query')")
    public CommonResult<BusinessEntityOptionsRespVO> options(@RequestParam @Positive Long dataDomainId,
            @RequestParam(required = false) @Positive Long excludeId) {
        return success(service.options(dataDomainId, excludeId));
    }

    /**
     * 业务实体关系图。
     *
     * @param id 业务实体编号
     * @return 一跳范围内的实体关系与关联逻辑表
     */
    @GetMapping("/relation-graph")
    @Operation(summary = "获得业务实体关系图（一跳范围内的实体关系及关联逻辑表）")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:query')")
    public CommonResult<BusinessEntityGraphRespVO> relationGraph(@RequestParam @Positive Long id) {
        return success(graphService.graph(id));
    }

    /**
     * 业务实体的版本列表，最新在前。
     *
     * @param entityId 业务实体编号
     * @return 版本列表
     */
    @GetMapping("/version/list")
    @Operation(summary = "获得业务实体版本列表（每次上线生成，只读）")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:query')")
    public CommonResult<List<BusinessEntityVersionRespVO>> versionList(@RequestParam @Positive Long entityId) {
        service.get(entityId);
        return success(versions.listVersions(entityId));
    }

    /**
     * 业务实体的版本快照。
     *
     * @param id 版本编号
     * @return 版本快照
     */
    @GetMapping("/version/get")
    @Operation(summary = "获得业务实体版本快照")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:query')")
    public CommonResult<BusinessEntityVersionDetailRespVO> version(@RequestParam @Positive Long id) {
        return success(versions.getVersion(id));
    }

    /**
     * 比较两个版本，或某个版本与当前设计。
     *
     * @param entityId 业务实体编号
     * @param fromVersionId 基准版本编号
     * @param toVersionId 目标版本编号，为空时与当前设计比较
     * @return 比对结果
     */
    @GetMapping("/version/compare")
    @Operation(summary = "比较业务实体的两个版本，或版本与当前设计")
    @PreAuthorize("@ss.hasPermission('modeling:business-entity:query')")
    public CommonResult<BusinessEntityVersionCompareRespVO> compareVersion(@RequestParam @Positive Long entityId,
            @RequestParam @Positive Long fromVersionId, @RequestParam(required = false) @Positive Long toVersionId) {
        return success(versions.compare(entityId, fromVersionId, toVersionId));
    }

}
