package cn.iocoder.yudao.module.modeling.controller.admin.model;

import com.mzt.logapi.context.LogRecordContext;
import com.mzt.logapi.starter.annotation.LogRecord;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.service.model.LogicModelService;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;

@Tag(name = "管理后台 - 数据建模-逻辑模型")
@RestController
@RequestMapping({"/modeling/table-model", "/modeling/logic-model"})
@Validated
public class LogicModelController {

    @Resource
    private LogicModelService logicModelService;

    @Resource
    private ModelHierarchyResolver hierarchyResolver;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.LogicModelDefinitionService definitionService;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.ModelingDatabaseResolver databaseResolver;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.LogicModelVersionService versionService;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.LogicModelSourceSyncService sourceSyncService;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.ModelSqlParseService sqlParseService;

    /**
     * 创建逻辑模型（正向/逆向建模）。
     *
     * @param createReqVO 创建参数
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "创建逻辑模型（正向/逆向建模）", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "创建了逻辑模型【{{#createReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/create")
    @Operation(summary = "创建逻辑模型（正向/逆向建模）")
    @PreAuthorize("@ss.hasPermission('modeling:model:save')")
    public CommonResult<Long> createModel(@Valid @RequestBody LogicModelCreateReqVO createReqVO) {
        return success(logicModelService.createModel(createReqVO));
    }

    /**
     * 修改逻辑模型基础信息。
     *
     * @param updateReqVO 更新参数
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "修改逻辑模型基础信息", bizNo = "{{(#updateReqVO.id) ?: 0}}",
            success = "修改了逻辑模型【{{#auditObjectName}}】，名称由【{{#auditObjectName}}】改为【{{#updateReqVO.name}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update")
    @Operation(summary = "修改逻辑模型基础信息")
    @PreAuthorize("@ss.hasPermission('modeling:model:save')")
    public CommonResult<Boolean> updateModel(@Valid @RequestBody LogicModelCreateReqVO updateReqVO) {
        var auditBefore = logicModelService.getModel(updateReqVO.getId());
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + updateReqVO.getId() : auditBefore.getName());

        logicModelService.updateModel(updateReqVO);
        return success(true);
    }

    /**
     * 启用或禁用逻辑模型。
     *
     * @param id 记录编号
     * @param enabled 启用状态
     * @param expectedRevision 预期修订号
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "启用或禁用逻辑模型", bizNo = "{{(#id) ?: 0}}",
            success = "将逻辑模型【{{#auditObjectName}}】设为【{{#enabled == 1 ? '启用' : '停用'}}】，校验修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/update-enabled")
    @Operation(summary = "启用或禁用逻辑模型")
    @PreAuthorize("@ss.hasPermission('modeling:model:save')")
    public CommonResult<Boolean> updateEnabled(@RequestParam("id") Long id,
            @RequestParam("enabled") Integer enabled,
            @RequestParam("expectedRevision") Long expectedRevision) {
        var auditBefore = logicModelService.getModel(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        logicModelService.updateEnabled(id, enabled, expectedRevision);
        return success(true);
    }

    /**
     * 原子保存统一模型草稿、字段与业务口径。
     *
     * @param reqVO 请求参数
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "原子保存统一模型草稿、字段与业务口径", bizNo = "{{(#reqVO.modelId) ?: 0}}",
            success = "保存了逻辑模型【{{#reqVO.name}}】（编号{{#reqVO.modelId}}）的当前设计，编码【{{#reqVO.code}}】，校验修订【{{#reqVO.expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/draft-save")
    @Operation(summary = "原子保存统一模型草稿、字段与业务口径")
    @PreAuthorize("@ss.hasPermission('modeling:model:save')")
    public CommonResult<Boolean> saveDraft(@Valid @RequestBody ModelDraftSaveReqVO reqVO) {
        logicModelService.saveDraft(reqVO);
        return success(true);
    }

    /**
     * 删除逻辑模型。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "删除逻辑模型", bizNo = "{{(#id) ?: 0}}",
            success = "删除了逻辑模型【{{#auditObjectName}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/delete")
    @Operation(summary = "删除逻辑模型")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:delete')")
    public CommonResult<Boolean> deleteModel(@RequestParam("id") Long id,
            @RequestParam("expectedRevision") Long expectedRevision) {
        var auditBefore = logicModelService.getModel(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        logicModelService.deleteModel(id, expectedRevision);
        return success(true);
    }

    /**
     * 获得逻辑模型。
     *
     * @param id 记录编号
     * @return 逻辑模型响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得逻辑模型")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<LogicModelRespVO> getModel(@RequestParam("id") Long id) {
        LogicModelDO model = logicModelService.getModel(id);
        LogicModelRespVO resp = BeanUtils.toBean(model, LogicModelRespVO.class);
        hierarchyResolver.fillHierarchy(List.of(resp));
        definitionService.present(model, resp);
        return success(resp);
    }

    /**
     * 预览汇总逻辑表的时间、粒度和派生指标字段。
     *
     * @param request 请求参数
     * @return 汇总逻辑表的时间、粒度和派生指标字段预览结果集合
     */
    @PostMapping("/summary-preview")
    @Operation(summary = "预览汇总逻辑表的时间、粒度和派生指标字段")
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<List<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem>> summaryPreview(
            @Valid @RequestBody LogicModelSummaryPreviewReqVO request) {
        return success(definitionService.summaryFields(request.getDataDomainId(), request.getSubjectDomainId(),
                request.getDataTimeliness(), request.getGrainEntityIds()));
    }

    /**
     * 逻辑模型分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 逻辑模型分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "逻辑模型分页")
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<PageResult<LogicModelRespVO>> getModelPage(
            @Valid LogicModelPageReqVO pageReqVO) {
        PageResult<LogicModelDO> page = logicModelService.getModelPage(pageReqVO);
        PageResult<LogicModelRespVO> result = BeanUtils.toBean(page, LogicModelRespVO.class);
        hierarchyResolver.fillHierarchy(result.getList());
        definitionService.presentAll(page.getList(), result.getList());
        return success(result);
    }

    // ==================== 当前设计 ====================

    /** 获得当前设计及其乐观锁标记。 */
    @GetMapping("/design-get")
    @Operation(summary = "获得模型当前设计")
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<ModelDesignRespVO> getDesign(@RequestParam("modelId") Long modelId) {
        return success(logicModelService.getDesign(modelId));
    }

    /** 发布当前设计，不保留模型历史版本。 */
    @LogRecord(type = "数据建模-逻辑模型", subType = "发布模型当前设计", bizNo = "{{(#modelId) ?: 0}}",
            success = "发布了逻辑模型【{{#auditObjectName}}】（编号{{#modelId}}）的当前设计，发布前修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/publish")
    @Operation(summary = "发布模型当前设计")
    @PreAuthorize("@ss.hasPermission('modeling:model:publish')")
    public CommonResult<Boolean> publishModel(@RequestParam("modelId") Long modelId,
            @RequestParam("expectedRevision") Long expectedRevision) {
        var auditBefore = logicModelService.getModel(modelId);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + modelId : auditBefore.getName());

        logicModelService.publishModel(modelId, expectedRevision);
        return success(true);
    }

    /**
     * 废弃逻辑模型。
     *
     * @param id 记录编号
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "废弃逻辑模型", bizNo = "{{(#id) ?: 0}}",
            success = "废弃了逻辑模型【{{#auditObjectName}}】（编号{{#id}}）", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/discard")
    @Operation(summary = "废弃逻辑模型")
    @Parameter(name = "id", description = "模型编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:publish')")
    public CommonResult<Boolean> discardModel(@RequestParam("id") Long id) {
        var auditBefore = logicModelService.getModel(id);
        LogRecordContext.putVariable("auditObjectName",
                auditBefore == null || auditBefore.getName() == null || auditBefore.getName().isBlank()
                        ? "编号" + id : auditBefore.getName());

        logicModelService.discardModel(id);
        return success(true);
    }

    // ==================== 影响分析 ====================

    /**
     * 影响分析（设计关系、部署目标）。
     *
     * @param id 记录编号
     * @return 逻辑影响响应数据
     */
    @GetMapping("/impact")
    @Operation(summary = "影响分析（设计关系、部署目标）")
    @Parameter(name = "id", description = "逻辑模型编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<LogicImpactRespVO> impactAnalysis(@RequestParam("id") Long id) {
        return success(logicModelService.impactAnalysis(id));
    }

    // ==================== ER 图 ====================

    /**
     * 获得主题 ER 图（模型 + 当前字段 + 关系）。
     *
     * @param layerId 分层编号
     * @return 逻辑ER 图响应数据
     */
    @GetMapping("/er-diagram")
    @Operation(summary = "获得主题 ER 图（模型 + 当前字段 + 关系）")
    @Parameter(name = "layerId", description = "数仓分层编号")
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<LogicErDiagramRespVO> getErDiagram(
            @RequestParam(value = "layerId", required = false) Long layerId) {
        return success(logicModelService.getErDiagram(layerId));
    }

    /**
     * 新增/修改逻辑模型关系。
     *
     * @param reqVO 请求参数
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "新增/修改逻辑模型关系", bizNo = "{{(#_ret.data) ?: 0}}",
            success = "保存了模型关系【{{#reqVO.name}}】（编号{{#_ret.data}}），源模型【{{#reqVO.sourceModelId}}】，目标模型【{{#reqVO.targetModelId}}】，关系类型【{{#reqVO.relationType}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PostMapping("/relation-save")
    @Operation(summary = "新增/修改逻辑模型关系")
    @PreAuthorize("@ss.hasPermission('modeling:model:save')")
    public CommonResult<Long> saveRelation(@Valid @RequestBody LogicRelationSaveReqVO reqVO) {
        return success(logicModelService.saveRelation(reqVO));
    }

    /**
     * 删除逻辑模型关系。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "删除逻辑模型关系", bizNo = "{{(#id) ?: 0}}",
            success = "删除了模型关系【编号{{#id}}】，校验修订【{{#expectedRevision}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @DeleteMapping("/relation-delete")
    @Operation(summary = "删除逻辑模型关系")
    @Parameter(name = "id", description = "关系编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:delete')")
    public CommonResult<Boolean> deleteRelation(@RequestParam("id") Long id,
            @RequestParam("expectedRevision") Long expectedRevision) {
        logicModelService.deleteRelation(id, expectedRevision);
        return success(true);
    }

    /**
     * 批量保存模型 ER 画布坐标。
     *
     * @param reqVO 请求参数
     * @return 逻辑模型
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "批量保存模型 ER 画布坐标", bizNo = "{{(#reqVO.positions.![modelId]) ?: 0}}",
            success = "保存了模型ER画布坐标，模型编号集合【{{#reqVO.positions.![modelId]}}】，共【{{#reqVO.positions.size()}}】个模型", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
    @PutMapping("/er-layout-save")
    @Operation(summary = "批量保存模型 ER 画布坐标")
    @PreAuthorize("@ss.hasPermission('modeling:model:save')")
    public CommonResult<Boolean> saveErLayout(@Valid @RequestBody LogicErLayoutSaveReqVO reqVO) {
        logicModelService.saveErLayout(reqVO);
        return success(true);
    }

    // ==================== 数据源元数据（供逻辑模型逆向；原挂在 physical-model） ====================

    /**
     * 数据源精简列表。
     *
     * @return 数据源列表
     */
    @GetMapping("/database-list")
    @Operation(summary = "数据源精简列表")
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<java.util.List<cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO>> getDatabaseList() {
        java.util.List<cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO> list = databaseResolver.getDatabaseList();
        list.forEach(item -> {
            String endpoint = jdbcEndpoint(item.getJdbcUrl());
            if (endpoint != null) {
                item.setHost(endpoint);
            }
            item.setJdbcUrl(null);
            item.setUsername(null);
            item.setPassword(null);
        });
        return success(list);
    }

    /**
     * 数据源下的表名列表（逆向建模选用）。
     *
     * @param databaseId 数据源编号
     * @return 数据源下的表名列表（逆向建模选用）结果集合
     */
    @GetMapping("/table-list")
    @Operation(summary = "数据源下的表名列表（逆向建模选用）")
    @Parameter(name = "databaseId", description = "数据源编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<java.util.List<String>> getTableList(@RequestParam("databaseId") Long databaseId) {
        return success(databaseResolver.getTableNameList(databaseId));
    }

    /**
     * 从数据源表拉取字段（逻辑模型建表前预览）。
     *
     * @param databaseId 数据源编号
     * @param tableName 表名
     * @return 从数据源表拉取字段（逻辑模型建表前预览）结果集合
     */
    @GetMapping("/import-fields")
    @Operation(summary = "从数据源表拉取字段（逻辑模型建表前预览）")
    @Parameter(name = "databaseId", description = "数据源编号", required = true)
    @Parameter(name = "tableName", description = "源表名", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<java.util.List<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem>> importFields(
            @RequestParam("databaseId") Long databaseId,
            @RequestParam("tableName") String tableName) {
        return success(databaseResolver.importFields(databaseId, tableName));
    }

    /**
     * 逻辑模型的版本列表，最新在前。
     *
     * @param modelId 逻辑模型编号
     * @return 版本列表
     */
    @GetMapping("/version/list")
    @Operation(summary = "获得逻辑模型版本列表（每次发布生成，只读）")
    @Parameter(name = "modelId", description = "逻辑模型编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<List<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.LogicModelVersionRespVO>>
            getVersionList(@RequestParam("modelId") Long modelId) {
        logicModelService.getModel(modelId);
        return success(versionService.listVersions(modelId));
    }

    /**
     * 逻辑模型的版本快照。
     *
     * @param id 版本编号
     * @return 版本快照
     */
    @GetMapping("/version/get")
    @Operation(summary = "获得逻辑模型版本快照")
    @Parameter(name = "id", description = "版本编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.LogicModelVersionDetailRespVO>
            getVersion(@RequestParam("id") Long id) {
        return success(versionService.getVersion(id));
    }

    /**
     * 比较两个版本，或某个版本与当前设计。
     *
     * @param modelId 逻辑模型编号
     * @param fromVersionId 基准版本编号
     * @param toVersionId 目标版本编号，为空时与当前设计比较
     * @return 比对结果
     */
    @GetMapping("/version/compare")
    @Operation(summary = "比较逻辑模型的两个版本，或版本与当前设计")
    @Parameter(name = "modelId", description = "逻辑模型编号", required = true)
    @Parameter(name = "fromVersionId", description = "基准版本编号", required = true)
    @Parameter(name = "toVersionId", description = "目标版本编号，为空时与当前设计比较")
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO>
            compareVersion(@RequestParam("modelId") Long modelId, @RequestParam("fromVersionId") Long fromVersionId,
                           @RequestParam(value = "toVersionId", required = false) Long toVersionId) {
        return success(versionService.compare(modelId, fromVersionId, toVersionId));
    }

    /**
     * 贴源表：预览源表当前结构与当前设计的差异。
     *
     * @param modelId 逻辑模型编号
     * @return 差异预览
     */
    @GetMapping("/source-sync/preview")
    @Operation(summary = "贴源表同步结构：预览源表当前结构与当前设计的差异")
    @Parameter(name = "modelId", description = "逻辑模型编号", required = true)
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.SourceSyncPreviewRespVO>
            previewSourceSync(@RequestParam("modelId") Long modelId) {
        return success(sourceSyncService.preview(modelId));
    }

    /**
     * 贴源表：按源表当前结构更新当前设计，原本已发布的模型重新发布并生成新版本。
     *
     * @param request 模型编号与预览时的修订号
     * @return 保存与重新发布结果
     */
    @LogRecord(type = "数据建模-逻辑模型", subType = "贴源表同步源表结构", bizNo = "{{#request.modelId}}",
            success = "按源表结构同步了贴源表【{{#request.modelId}}】", extra = "BUSINESS_OPERATION",
            successCondition = "{{#_ret.code == 0}}")
    @PutMapping("/source-sync/apply")
    @Operation(summary = "贴源表同步结构：按源表当前结构更新设计并重新发布")
    @PreAuthorize("@ss.hasPermission('modeling:model:save') and @ss.hasPermission('modeling:model:publish')")
    public CommonResult<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.SourceSyncApplyRespVO>
            applySourceSync(@Valid @RequestBody
                            cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.SourceSyncApplyReqVO request) {
        return success(sourceSyncService.apply(request));
    }

    /**
     * 字段设计：把建表语句解析为字段，只返回解析结果，不写入设计。
     *
     * @param request 方言与建表语句
     * @return 解析出的字段
     */
    @PostMapping("/sql-parse-fields")
    @Operation(summary = "字段设计：解析建表语句为字段（不写入设计）")
    @PreAuthorize("@ss.hasPermission('modeling:model:query')")
    public CommonResult<SqlParseFieldsRespVO> parseSqlFields(@Valid @RequestBody SqlParseFieldsReqVO request) {
        return success(sqlParseService.parse(request));
    }

    /**
     * 获取逻辑模型的JDBC端点。
     *
     * @param jdbcUrl JDBCURL
     * @return 逻辑模型的JDBC端点文本
     */
    static String jdbcEndpoint(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String rest = jdbcUrl;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int q = rest.indexOf('?');
        if (q >= 0) {
            rest = rest.substring(0, q);
        }
        return rest.isBlank() ? null : rest;
    }

}
