package cn.iocoder.yudao.module.dataforge.controller.admin.integration;

import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationComponentCapabilitiesRespVO;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationComponentCapabilitiesService;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@RestController
@Validated
@RequestMapping("/dataforge/integration")
@Tag(name = "管理后台 - 离线与实时数据集成")
public class IntegrationTaskController {

    @Resource
    private IntegrationTaskService service;

    @Resource
    private IntegrationComponentCapabilitiesService componentCapabilities;

    @Resource
    private cn.iocoder.yudao.module.dataforge.service.integration.IntegrationWebServiceMetadataService webServiceMetadata;

    /** 只读取控制面获精确授权的 WSDL/XSD，不触发远端业务操作。 */
    @PostMapping("/webservice-metadata")
    @Operation(summary = "分析当前任务已授权WebService的服务与操作")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<cn.iocoder.yudao.framework.etl.core.executor.support.EtlWebServiceRows.Metadata> webServiceMetadata(
            @RequestBody @Valid cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationWebServiceMetadataReqVO req) {
        return success(webServiceMetadata.inspect(req));
    }

    @GetMapping("/component-capabilities")
    @Operation(summary = "读取当前引擎与处理模式的组件执行能力")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<IntegrationComponentCapabilitiesRespVO> componentCapabilities(
            @RequestParam @Pattern(regexp = "SPARK|FLINK|DATAX") String engine,
            @RequestParam @Pattern(regexp = "OFFLINE|REALTIME") String mode) {
        return success(componentCapabilities.get(engine, mode));
    }

    /** 仅采样控制面获精确授权的无认证 GET 接口，不返回业务数据行。 */
    @PostMapping("/api-metadata")
    @Operation(summary = "采样当前任务已授权GET接口的字段")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<cn.iocoder.yudao.module.dataforge.service.integration.IntegrationWebServiceMetadataService.ApiMetadata> apiMetadata(
            @RequestBody @Valid cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationApiMetadataReqVO req) {
        return success(webServiceMetadata.sample(req));
    }

    /**
     * 分页查询当前平台共享的集成任务。
     *
     * @param req 请求参数
     * @return 分页查询当前平台共享的集成任务结果
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询当前平台共享的集成任务")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<PageResult<TaskResp>> page(@Valid PageReq req) {
        return success(service.page(req));
    }

    /**
     * 读取集成任务与只读源流程。
     *
     * @param id 记录编号
     * @return 任务
     */
    @GetMapping("/get")
    @Operation(summary = "读取集成任务与只读源流程")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<TaskResp> get(@RequestParam Long id) {
        return success(service.get(id));
    }

    /**
     * 读取共享集成类目树。
     *
     * @return 共享集成类目树结果集合
     */
    @GetMapping("/categories")
    @Operation(summary = "读取共享集成类目树")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<List<CategoryResp>> categories() {
        return success(service.categories());
    }

    /**
     * 新增共享集成类目。
     *
     * @param req 请求参数
     * @return 集成任务
     */
    @PostMapping("/category/create")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#result")
    @Operation(summary = "新增共享集成类目")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:create')")
    public CommonResult<Long> createCategory(@RequestBody @Valid CategorySaveReq req) {
        return success(service.saveCategory(req, false));
    }

    /**
     * 修改共享集成类目并防止循环引用。
     *
     * @param req 请求参数
     * @return 集成任务
     */
    @PutMapping("/category/update")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#req.id")
    @Operation(summary = "修改共享集成类目并防止循环引用")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:update')")
    public CommonResult<Boolean> updateCategory(@RequestBody @Valid CategorySaveReq req) {
        service.saveCategory(req, true);
        return success(true);
    }

    /**
     * 删除无子类目且无任务引用的共享类目。
     *
     * @param id 记录编号
     * @return 集成任务
     */
    @DeleteMapping("/category/delete")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#id")
    @Operation(summary = "删除无子类目且无任务引用的共享类目")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:delete')")
    public CommonResult<Boolean> deleteCategory(@RequestParam Long id) {
        service.deleteCategory(id);
        return success(true);
    }

    /**
     * 读取当前平台有效负责人。
     *
     * @return 当前平台有效负责人结果集合
     */
    @GetMapping("/owners")
    @Operation(summary = "读取当前平台有效负责人")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<List<OwnerResp>> owners() {
        return success(service.owners());
    }

    /**
     * 读取集成运行摘要，迁入数据显式标记为源系统快照。
     *
     * @param mode 处理模式
     * @return 统计
     */
    @GetMapping("/statistics")
    @Operation(summary = "读取集成运行摘要，迁入数据显式标记为源系统快照")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<StatisticsResp> statistics(@RequestParam @Pattern(regexp = "OFFLINE|REALTIME") String mode) {
        return success(service.statistics(mode));
    }

    /**
     * 新建集成任务基础信息。
     *
     * @param req 请求参数
     * @return 集成任务
     */
    @PostMapping("/create")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#result")
    @Operation(summary = "新建集成任务基础信息")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:create')")
    public CommonResult<Long> create(@RequestBody @Valid SaveReq req) {
        return success(service.create(req));
    }

    /**
     * 修改集成任务基础信息。
     *
     * @param req 请求参数
     * @return 集成任务
     */
    @PutMapping("/update")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#req.id")
    @Operation(summary = "修改集成任务基础信息")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:update')")
    public CommonResult<Boolean> update(@RequestBody @Valid SaveReq req) {
        service.update(req);
        return success(true);
    }

    /**
     * 删除已下线且关闭调度的集成任务。
     *
     * @param id 记录编号
     * @return 集成任务
     */
    @DeleteMapping("/delete")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#id")
    @Operation(summary = "删除已下线且关闭调度的集成任务")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:delete')")
    public CommonResult<Boolean> delete(@RequestParam Long id) {
        service.delete(id);
        return success(true);
    }

    /**
     * 克隆集成定义为未上线的新任务。
     *
     * @param id 记录编号
     * @return 集成任务
     */
    @PostMapping("/clone")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#result")
    @Operation(summary = "克隆集成定义为未上线的新任务")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:create')")
    public CommonResult<Long> cloneTask(@RequestParam Long id) {
        return success(service.cloneTask(id));
    }

    /**
     * 切换集成任务上下线状态。
     *
     * @param req 请求参数
     * @return 集成任务
     */
    @PutMapping("/status")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#req.id")
    @Operation(summary = "切换集成任务上下线状态")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:update')")
    public CommonResult<Boolean> status(@RequestBody @Valid StatusReq req) {
        service.status(req);
        return success(true);
    }

    /**
     * 保存调度周期或切换调度；开启要求绑定本平台可执行流程。
     *
     * @param req 请求参数
     * @return 集成任务
     */
    @PutMapping("/schedule")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#req.id")
    @Operation(summary = "保存调度周期或切换调度；开启要求绑定本平台可执行流程")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:update')")
    public CommonResult<Boolean> schedule(@RequestBody @Valid ScheduleReq req) {
        service.schedule(req);
        return success(true);
    }

    /**
     * 按上海时区预览 Quartz 调度周期。
     *
     * @param cronExpression Cron表达式
     * @return 按上海时区预览 Quartz 调度周期结果集合
     */
    @GetMapping("/cron-preview")
    @Operation(summary = "按上海时区预览 Quartz 调度周期")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<List<LocalDateTime>> cronPreview(@RequestParam String cronExpression) {
        return success(service.cronPreview(cronExpression));
    }

    /**
     * 分页读取执行实例；迁入实例与本平台真实执行记录区分展示。
     *
     * @param req 请求参数
     * @return 分页读取执行实例；迁入实例与本平台真实执行记录区分展示结果
     */
    @GetMapping("/records")
    @Operation(summary = "分页读取执行实例；迁入实例与本平台真实执行记录区分展示")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<PageResult<RecordResp>> records(@Valid RecordPageReq req) {
        return success(service.records(req));
    }

    /**
     * 读取指定集成任务的全量实例运行状态计数。
     *
     * @param taskId 任务编号
     * @return 记录统计
     */
    @GetMapping("/record-statistics")
    @Operation(summary = "读取指定集成任务的全量实例运行状态计数")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<RecordStatisticsResp> recordStatistics(@RequestParam Long taskId) {
        return success(service.recordStatistics(taskId));
    }

    /**
     * 委托已绑定的受管 ETL 流程执行，不把源流程快照当作可执行配置。
     *
     * @param id 记录编号
     * @return 集成任务
     */
    @PostMapping("/run")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#id")
    @Operation(summary = "委托已绑定的受管 ETL 流程执行，不把源流程快照当作可执行配置")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:run')")
    public CommonResult<Long> run(@RequestParam Long id) {
        return success(service.run(id));
    }

    /**
     * 请求停止本平台正在执行的实例。
     *
     * @param id 记录编号
     * @return 集成任务
     */
    @PostMapping("/stop")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#id")
    @Operation(summary = "请求停止本平台正在执行的实例")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:run')")
    public CommonResult<Boolean> stop(@RequestParam Long id) {
        service.stop(id);
        return success(true);
    }
    /** 保存流程草稿，或编译并登记到 DolphinScheduler。 */
    @PutMapping("/flow")
    @BusinessOperateLog(type = "数据开发-离线与实时数据集成", bizId = "#req.id")
    @Operation(summary = "保存集成流程并发布 DS 离线定义")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:update')")
    public CommonResult<TaskResp> saveFlow(@RequestBody @Valid FlowReq req) { return success(service.saveFlow(req)); }

    /** 执行与正式保存相同的编译准入检查。 */
    @PostMapping("/check")
    @Operation(summary = "检查集成流程的 DS 原生执行能力")
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<FlowCheckResp> checkFlow(@RequestBody @Valid FlowReq req) { return success(service.checkFlow(req)); }

    /** 只读字段元数据与执行形状分析，接口权限沿用集成任务查询。 */
    @PostMapping("/schema")
    @Operation(summary = "读取集成画布各节点的输入与输出字段")
    @cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog(requestEnable = false, responseEnable = false)
    @PreAuthorize("@ss.hasPermission('dataforge:integration:query')")
    public CommonResult<cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult> schema(@RequestBody @Valid FlowReq req) {
        return success(service.schema(req));
    }

}
