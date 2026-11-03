package cn.iocoder.yudao.module.dataforge.controller.admin.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - ETL 生产流程")
@RestController
@RequestMapping("/dataforge/etl-pipeline")
@Validated
public class EtlPipelineController {

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.etl.EtlDesignService designService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.etl.EtlPreviewSessionService previewSessions;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.etl.EtlQualityEvidenceService evidenceService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.etl.EtlVersionService versionService;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.etl.EtlSubflowService subflowService;

    /**
     * 读取已发布子流程的输入、输出与参数契约。
     *
     * @param versionId 版本编号
     * @return 契约
     */
    @GetMapping("/subflow-contract")
    @Operation(summary = "读取已发布子流程的输入、输出与参数契约")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.etl.EtlSubflowService.Contract> subflowContract(
            @RequestParam("versionId") Long versionId) {
        return success(subflowService.contract(versionId,
                cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId()));
    }

    /**
     * 分页查看发布与恢复历史。
     *
     * @param req 请求参数
     * @return 分页查看发布与恢复历史结果
     */
    @GetMapping("/version/page")
    @Operation(summary = "分页查看发布与恢复历史")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<PageResult<EtlVersionRespVO>> versions(@Valid EtlVersionPageReqVO req) {
        return success(versionService.page(req));
    }

    /**
     * 读取不可变历史快照。
     *
     * @param id 记录编号
     * @return ETL版本响应数据
     */
    @GetMapping("/version/get")
    @Operation(summary = "读取不可变历史快照")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlVersionRespVO> version(@RequestParam("id") Long id) {
        return success(versionService.get(id));
    }

    /**
     * 比较历史版本与当前草稿的业务定义。
     *
     * @param id 记录编号
     * @return 比较历史版本与当前草稿的业务定义结果集合
     */
    @GetMapping("/version/diff")
    @Operation(summary = "比较历史版本与当前草稿的业务定义")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<List<cn.iocoder.yudao.module.dataforge.service.etl.EtlVersionService.Change>> versionDiff(
            @RequestParam("id") Long id) {
        return success(versionService.diff(id));
    }

    /**
     * 比较同一流程的两个不可变历史版本，仅返回变化分类和统计。
     *
     * @param request 请求参数
     * @return ETL版本比较响应数据
     */
    @GetMapping("/version/compare")
    @Operation(summary = "比较同一流程的两个不可变历史版本，仅返回变化分类和统计")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlVersionCompareRespVO> compareVersions(@Valid EtlVersionCompareReqVO request) {
        return success(versionService.compare(request.getSourceVersionId(), request.getTargetVersionId()));
    }

    /**
     * 恢复历史发布配置为新草稿，保留现有发布版本和调度。
     *
     * @param req 请求参数
     * @return ETL 流程
     */
    @PostMapping("/version/restore")
    @Operation(summary = "恢复历史发布配置为新草稿，保留现有发布版本和调度")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save') and @ss.hasPermission('dataforge:etl:query')")
    public CommonResult<Integer> restoreVersion(@Valid @RequestBody EtlVersionRestoreReqVO req) {
        return success(versionService.restore(req));
    }

    /**
     * 校验流程配置、端口及治理引用。
     *
     * @param request 请求参数
     * @return ETL校验结果
     */
    @PostMapping("/validate")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "校验流程配置、端口及治理引用")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<cn.iocoder.yudao.framework.etl.core.validation.EtlValidationResult> validateGraph(
            @Valid @RequestBody EtlSchemaReqVO request) {
        return success(designService.validate(request));
    }

    /**
     * 生成来源或授权 PostgreSQL 整表采样预览，可经受控治理与纯内存转换节点；不执行 SQL、脚本、加解密或读写外部系统的节点。
     *
     * @param request 请求参数
     * @return 结果
     */
    @PostMapping("/preview")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "生成来源或授权 PostgreSQL 整表采样预览，可经受控治理与纯内存转换节点；不执行 SQL、脚本、加解密或读写外部系统的节点")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run') and @ss.hasPermission('dataforge:etl:query')")
    public CommonResult<cn.iocoder.yudao.framework.etl.core.engine.EtlPreviewEngine.Result> preview(
            @Valid @RequestBody EtlPreviewReqVO request) {
        return success(designService.preview(request));
    }

    /**
     * 分页搜索本平台拥有已发布子流程定义的流程。
     *
     * @param req 请求参数
     * @return 分页搜索本平台拥有已发布子流程定义的流程结果
     */
    @GetMapping("/subflow/pipeline-page")
    @Operation(summary = "分页搜索本平台拥有已发布子流程定义的流程")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<PageResult<EtlSubflowPipelineRespVO>> subflowPipelines(@Valid EtlSubflowPipelinePageReqVO req) {
        return success(subflowService.pipelinePage(req,
                cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId()));
    }

    /**
     * 分页读取可引用的固定子流程发布版本摘要。
     *
     * @param req 请求参数
     * @return 分页读取可引用的固定子流程发布版本摘要结果
     */
    @GetMapping("/subflow/version-page")
    @Operation(summary = "分页读取可引用的固定子流程发布版本摘要")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<PageResult<EtlVersionRespVO>> subflowVersions(@Valid EtlSubflowVersionPageReqVO req) {
        return success(subflowService.versionPage(req,
                cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId()));
    }

    /**
     * 读取当前实例短时预览会话边界与受理时间窗。
     *
     * @return 能力
     */
    @GetMapping("/preview-session/capabilities")
    @Operation(summary = "读取当前实例短时预览会话边界与受理时间窗")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run') and @ss.hasPermission('dataforge:etl:query')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.etl.EtlPreviewSessionService.Capabilities> previewCapabilities() {
        return success(previewSessions.capabilities());
    }

    /**
     * 在明确指定的当前实例创建有界预览会话。
     *
     * @param request 请求参数
     * @return ETL 预览会话响应数据
     */
    @PostMapping("/preview-session")
    @Operation(summary = "在明确指定的当前实例创建有界预览会话")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run') and @ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlPreviewSessionRespVO> createPreview(@Valid @RequestBody EtlPreviewSessionReqVO request) {
        return success(previewSessions.create(request));
    }

    /**
     * 查询本人预览会话；返回样本前重新核验当前权限。
     *
     * @param request 请求参数
     * @return ETL 预览会话响应数据
     */
    @GetMapping("/preview-session")
    @Operation(summary = "查询本人预览会话；返回样本前重新核验当前权限")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run') and @ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlPreviewSessionRespVO> getPreview(@Valid EtlPreviewSessionKeyReqVO request) {
        return success(previewSessions.get(request));
    }

    /**
     * 请求取消本人会话并读取真实资源退出状态；请求取消不代表已退出。
     *
     * @param request 请求参数
     * @return ETL 预览会话响应数据
     */
    @PostMapping("/preview-session/cancel")
    @Operation(summary = "请求取消本人会话并读取真实资源退出状态；请求取消不代表已退出")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlPreviewSessionRespVO> cancelPreview(@Valid @RequestBody EtlPreviewSessionKeyReqVO request) {
        return success(previewSessions.cancel(request));
    }

    /**
     * 查询本平台运行的内联质量批次。
     *
     * @param recordId 记录编号
     * @return 本平台运行的内联质量批次结果集合
     */
    @GetMapping("/quality-batches")
    @Operation(summary = "查询本平台运行的内联质量批次")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<List<EtlQualityBatchRespVO>> qualityBatches(@RequestParam("recordId") Long recordId) {
        return success(evidenceService.batches(recordId));
    }

    /**
     * 分页查询质量问题，不包含原始行。
     *
     * @param request 请求参数
     * @return 分页查询质量问题，不包含原始行结果
     */
    @GetMapping("/quality-issues")
    @Operation(summary = "分页查询质量问题，不包含原始行")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<PageResult<EtlQualityIssueRespVO>> qualityIssues(@Valid EtlQualityIssuePageReqVO request) {
        return success(evidenceService.issues(request));
    }

    /**
     * 查看合成来源隔离证据；外部来源仅开放问题原因。
     *
     * @param id 记录编号
     * @return ETL质量问题响应数据
     */
    @GetMapping("/quality-issue")
    @Operation(summary = "查看合成来源隔离证据；外部来源仅开放问题原因")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlQualityIssueRespVO> qualityIssue(@RequestParam("id") Long id) {
        return success(evidenceService.issue(id));
    }

    /**
     * 人工重试治理登记，不重跑 ETL。
     *
     * @param batchId 批次编号
     * @return ETL 流程
     */
    @PostMapping("/retry-quality-registration")
    @Operation(summary = "人工重试治理登记，不重跑 ETL")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run') and @ss.hasPermission('dataforge:etl:query')")
    public CommonResult<Boolean> retryQualityRegistration(@RequestParam("batchId") Long batchId) {
        evidenceService.retryRegistration(batchId);
        return success(true);
    }

    /**
     * 查询质量批次的治理报告及可见工单关联。
     *
     * @param batchId 批次编号
     * @return ETL治理报告响应数据
     */
    @GetMapping("/quality-report")
    @Operation(summary = "查询质量批次的治理报告及可见工单关联")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query') and @ss.hasPermission('governance:quality-audit-log:query')")
    public CommonResult<EtlGovernanceReportRespVO> qualityReport(@RequestParam("batchId") Long batchId) {
        return success(evidenceService.governanceReport(batchId));
    }

    /**
     * 为内联质量报告创建治理工单，超时可查询报告确认关联。
     *
     * @param request 请求参数
     * @return ETL 流程
     */
    @PostMapping("/quality-work-order")
    @Operation(summary = "为内联质量报告创建治理工单，超时可查询报告确认关联")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query') and @ss.hasPermission('governance:quality-audit-log:query') and @ss.hasPermission('governance:quality-work-order:create')")
    public CommonResult<Long> qualityWorkOrder(@Valid @RequestBody EtlQualityWorkOrderReqVO request) {
        return success(evidenceService.createWorkOrder(request));
    }

    @Resource
    private EtlPipelineService pipelineService;

    /**
     * 保存流程（新建或更新）。
     *
     * @param saveReqVO 保存参数
     * @return ETL 流程
     */
    @PostMapping("/save")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "保存流程（新建或更新）")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Long> savePipeline(@Valid @RequestBody EtlPipelineSaveReqVO saveReqVO) {
        return success(pipelineService.savePipeline(saveReqVO));
    }

    /**
     * 删除流程。
     *
     * @param id 记录编号
     * @return ETL 流程
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除流程")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:delete')")
    public CommonResult<Boolean> deletePipeline(@RequestParam("id") Long id) {
        pipelineService.deletePipeline(id);
        return success(true);
    }

    /**
     * 获得流程（含画布 JSON）。
     *
     * @param id 记录编号
     * @return ETL 流程响应数据
     */
    @GetMapping("/get")
    @Operation(summary = "获得流程（含画布 JSON）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlPipelineRespVO> getPipeline(@RequestParam("id") Long id) {
        return success(pipelineService.getPipeline(id));
    }

    /**
     * 获得流程分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 流程分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "获得流程分页")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<PageResult<EtlPipelineRespVO>> getPipelinePage(
            @Valid EtlPipelinePageReqVO pageReqVO) {
        return success(pipelineService.getPipelinePage(pageReqVO));
    }

    /**
     * 发布当前草稿版本（运行使用发布快照；一次性任务立即执行）。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @return ETL 流程
     */
    @PutMapping("/release")
    @Operation(summary = "发布当前草稿版本（运行使用发布快照；一次性任务立即执行）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:release')")
    public CommonResult<Boolean> releasePipeline(@RequestParam("id") Long id,
            @RequestParam("revision") Integer revision) {
        pipelineService.releasePipeline(id, revision);
        return success(true);
    }

    /**
     * 任务下线（调度开启中不可下线）。
     *
     * @param id 记录编号
     * @return ETL 流程
     */
    @PutMapping("/cancel-release")
    @Operation(summary = "任务下线（调度开启中不可下线）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:release')")
    public CommonResult<Boolean> cancelPipeline(@RequestParam("id") Long id) {
        pipelineService.cancelPipeline(id);
        return success(true);
    }

    /**
     * 开启/关闭调度（仅周期任务，需先上线）。
     *
     * @param id 记录编号
     * @param enable 启用
     * @return ETL 流程
     */
    @PutMapping("/schedule")
    @Operation(summary = "开启/关闭调度（仅周期任务，需先上线）")
    @Parameter(name = "id", description = "编号", required = true)
    @Parameter(name = "enable", description = "是否开启", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:release')")
    public CommonResult<Boolean> updateScheduleStatus(@RequestParam("id") Long id,
            @RequestParam("enable") Boolean enable) {
        pipelineService.updateScheduleStatus(id, enable);
        return success(true);
    }

    /**
     * 克隆流程（复制画布与配置，新流程为未上线状态）。
     *
     * @param id 记录编号
     * @return ETL 流程
     */
    @PostMapping("/copy")
    @Operation(summary = "克隆流程（复制画布与配置，新流程为未上线状态）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<Long> copyPipeline(@RequestParam("id") Long id) {
        return success(pipelineService.copyPipeline(id));
    }

    /**
     * 手动执行一次，返回执行记录编号。
     *
     * @param id 记录编号
     * @return ETL 流程
     */
    @PostMapping("/run")
    @Operation(summary = "手动执行一次，返回执行记录编号")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run')")
    public CommonResult<Long> runPipeline(@RequestParam("id") Long id) {
        return success(pipelineService.runPipeline(id));
    }

    /**
     * 停止执行。
     *
     * @param recordId 记录编号
     * @return ETL 流程
     */
    @PostMapping("/stop")
    @Operation(summary = "停止执行")
    @Parameter(name = "recordId", description = "执行记录编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run')")
    public CommonResult<Boolean> stopRecord(@RequestParam("recordId") Long recordId) {
        pipelineService.stopRecord(recordId);
        return success(true);
    }

    /**
     * 持久受理手动执行（使用稳定请求标识重放回执）。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @PostMapping("/run-command")
    @Operation(summary = "持久受理手动执行（使用稳定请求标识重放回执）")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> runCommand(
            @Valid @RequestBody EtlRunCommandReqVO request) {
        return success(pipelineService.runPipelineCommand(request.getPipelineId(), request.getRequestId(),
                request.getExpectedDefinitionRevision(), request.getParameters()));
    }

    /**
     * 读取本次运行实际采用的参数定义及修订号。
     *
     * @param pipelineId 流程编号
     * @return ETL运行参数定义
     */
    @GetMapping("/run-parameters")
    @Operation(summary = "读取本次运行实际采用的参数定义及修订号")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run') and @ss.hasPermission('dataforge:etl:query')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.etl.EtlRunParameterDefinition> runParameters(
            @RequestParam("pipelineId") Long pipelineId) {
        return success(pipelineService.getRunParameters(pipelineId,
                cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId()));
    }

    /**
     * 持久受理停止意图（回执不表示执行已退出）。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @PostMapping("/stop-command")
    @Operation(summary = "持久受理停止意图（回执不表示执行已退出）")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:run')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> stopCommand(
            @Valid @RequestBody EtlStopCommandReqVO request) {
        return success(pipelineService.stopRecordCommand(request.getRecordId(), request.getRequestId()));
    }

    /**
     * 凭状态版本与退出、输出证据人工核查运行结果。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    @PostMapping("/resolve-command")
    @Operation(summary = "凭状态版本与退出、输出证据人工核查运行结果")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:resolve')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt> resolveCommand(
            @Valid @RequestBody EtlResolveCommandReqVO request) {
        return success(pipelineService.resolveCommand(request));
    }

    /**
     * 兼容人工核查入口（同样要求状态版本与完整证据）。
     *
     * @param reqVO 请求参数
     * @return ETL 流程
     */
    @PostMapping("/resolve-unknown")
    @Operation(summary = "兼容人工核查入口（同样要求状态版本与完整证据）")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:resolve')")
    public CommonResult<Boolean> resolveUnknown(@Valid @RequestBody EtlResolveUnknownReqVO reqVO) {
        pipelineService.resolveUnknown(reqVO);
        return success(true);
    }

    /**
     * 查询执行记录的流程级日志与状态（设计器轮询）。
     *
     * @param recordId 记录编号
     * @return ETL 运行记录响应数据
     */
    @GetMapping("/record-log")
    @Operation(summary = "查询执行记录的流程级日志与状态（设计器轮询）")
    @Parameter(name = "recordId", description = "执行记录编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlRecordRespVO> getRecordLog(@RequestParam("recordId") Long recordId) {
        return success(pipelineService.getRecordLog(recordId));
    }

    /**
     * 查询执行记录的节点级状态与日志。
     *
     * @param recordId 记录编号
     * @return 执行记录的节点级状态与日志结果集合
     */
    @GetMapping("/node-records")
    @Operation(summary = "查询执行记录的节点级状态与日志")
    @Parameter(name = "recordId", description = "执行记录编号", required = true)
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<List<EtlNodeRecordRespVO>> getNodeRecords(@RequestParam("recordId") Long recordId) {
        return success(pipelineService.getNodeRecords(recordId));
    }

    /**
     * 字段契约推导（授权整表或已验证数据库的单表受限 SELECT，不执行复杂 SQL）。
     *
     * @param reqVO 请求参数
     * @return ETL结构结果
     */
    @PostMapping("/resolve-schema")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "字段契约推导（授权整表或已验证数据库的单表受限 SELECT，不执行复杂 SQL）")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:query')")
    public CommonResult<EtlSchemaResult> resolveSchema(@Valid @RequestBody EtlSchemaReqVO reqVO) {
        return success(pipelineService.resolveSchema(reqVO));
    }

    /**
     * Java 脚本编译检查（返回错误列表，空为通过）。
     *
     * @param reqVO 请求参数
     * @return Java 脚本编译检查（返回错误列表，空为通过）结果集合
     */
    @PostMapping("/compile-java")
    @Operation(summary = "Java 脚本编译检查（返回错误列表，空为通过）")
    @PreAuthorize("@ss.hasPermission('dataforge:etl:save')")
    public CommonResult<List<String>> compileJava(@Valid @RequestBody EtlJavaCompileReqVO reqVO) {
        return success(pipelineService.compileJavaScript(reqVO.getCode()));
    }

}
