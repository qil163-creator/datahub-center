package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;

import java.util.List;

/**
 * 可视化 ETL 流程 Service 接口
 *
 * @author Li
 */
public interface EtlPipelineService {

    /**
     * 兼容人工核查入口（同样要求状态版本与完整证据）。
     *
     * @param reqVO 请求参数
     */
    void resolveUnknown(cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlResolveUnknownReqVO reqVO);

    /**
     * 保存流程（新建或更新，含 DAG 结构校验）
     *
     * @return 流程编号
     */
    Long savePipeline(EtlPipelineSaveReqVO saveReqVO);

    /**
     * 删除流程（已发布或运行中不可删除）
     */
    void deletePipeline(Long id);

    /**
     * 获得流程（含画布 JSON）
     */
    EtlPipelineRespVO getPipeline(Long id);

    /**
     * 获得流程分页
     */
    PageResult<EtlPipelineRespVO> getPipelinePage(EtlPipelinePageReqVO pageReqVO);

    /**
     * 任务上线：锁定配置；一次性任务上线即执行一次
     */
    void releasePipeline(Long id, Integer revision);

    /**
     * 任务下线（调度开启中不可下线）
     */
    void cancelPipeline(Long id);

    /**
     * 开启/关闭调度（仅周期任务，需先上线；开启时按 Cron 计算下次执行时间）
     */
    void updateScheduleStatus(Long id, Boolean enable);

    /**
     * 克隆流程（复制画布与配置，新流程为未上线状态）
     *
     * @return 新流程编号
     */
    Long copyPipeline(Long id);

    /**
     * 手动执行一次
     *
     * @return 执行记录编号
     */
    Long runPipeline(Long id);

    /**
     * 运行流程命令。
     *
     * @param id 记录编号
     * @param requestId 请求编号
     * @return 运行回执
     */
    cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt runPipelineCommand(Long id, String requestId);

    /**
     * 运行流程命令。
     *
     * @param id 记录编号
     * @param requestId 请求编号
     * @param expectedDefinitionRevision 预期定义修订号
     * @param parameters 参数定义或取值
     * @return 运行回执
     */
    cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt runPipelineCommand(
            Long id, String requestId, Integer expectedDefinitionRevision, java.util.Map<String, String> parameters);

    /**
     * 读取运行参数。
     *
     * @param id 记录编号
     * @param actorId 操作人编号
     * @return ETL运行参数定义
     */
    EtlRunParameterDefinition getRunParameters(Long id, Long actorId);

    /**
     * 运行流程定时。
     *
     * @param id 记录编号
     * @param generation 代次
     * @param scheduledTime 定时时间
     * @return 运行回执
     */
    cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt runPipelineScheduled(
            Long id, Long generation, java.time.LocalDateTime scheduledTime);

    /**
     * 调度触发执行（执行方式记为自动调度）
     *
     * @return 执行记录编号
     */
    Long runPipelineAuto(Long id);

    /**
     * 停止执行
     */
    void stopRecord(Long recordId);

    /**
     * 停止记录命令。
     *
     * @param recordId 记录编号
     * @param requestId 请求编号
     * @return 运行回执
     */
    cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt stopRecordCommand(Long recordId, String requestId);

    /**
     * 凭状态版本与退出、输出证据人工核查运行结果。
     *
     * @param request 请求参数
     * @return 运行回执
     */
    cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt resolveCommand(EtlResolveCommandReqVO request);

    /**
     * 查询执行记录的流程级日志与状态（设计器轮询用）
     */
    EtlRecordRespVO getRecordLog(Long recordId);

    /**
     * 查询执行记录的节点级状态与日志（设计器着色用）
     */
    List<EtlNodeRecordRespVO> getNodeRecords(Long recordId);

    /**
     * 字段契约推导（未保存的画布也可提交）
     */
    EtlSchemaResult resolveSchema(EtlSchemaReqVO reqVO);

    /**
     * Java 脚本编译检查
     *
     * @return 错误信息列表（空列表表示通过）
     */
    List<String> compileJavaScript(String code);

    /**
     * 执行记录分页
     */
    PageResult<EtlRecordRespVO> getRecordPage(EtlRecordPageReqVO pageReqVO);

    /**
     * 删除执行记录（含节点记录）
     */
    void deleteRecord(Long id);

}
