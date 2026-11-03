package cn.iocoder.yudao.module.dataforge.service.operations;

import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.ExecutionTopologyRespVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * qData 页面任务到平台执行器的只读映射。
 *
 * <p>这里只描述已存在的执行器，不负责创建任务或发起运行。真正的执行仍由
 * DataAccessExecutor、EtlPipelineExecutor 和 DevDsSubmitter 负责。</p>
 */
@Service
public class ExecutionTopologyService {

    private static final List<ExecutionTopologyRespVO> CATALOG = List.of(
            item("DATA_ACCESS_OFFLINE", "DBSWITCH", "XXL_JOB", "本平台受控执行器", true, true,
                    "离线整库、全量和增量同步由 DB Switch 读写器及恢复协调器执行"),
            item("DATA_ACCESS_REALTIME", "SEATUNNEL", "SEATUNNEL_REST", "SeaTunnel 集群", true, true,
                    "实时同步通过 SeaTunnel REST 提交和停止，不经过 DolphinScheduler"),
            item("DATA_ACCESS_API", "API_ACCESS", "XXL_JOB", "本平台 API 接入执行器", true, true,
                    "API 接入使用平台受控执行器并持久化运行记录"),
            item("BATCH_FILE_IMPORT", "LOCAL_FILE", "XXL_JOB", "本平台文件入库执行器", true, true,
                    "批量文件入库由文件任务执行器处理，XXL-Job 负责到期扫描和恢复"),
            item("DEVELOPMENT_SQL", "DOLPHIN_SCHEDULER", "DOLPHIN_SCHEDULER", "DolphinScheduler 工作流", true, true,
                    "SQL 任务由 DevDsSubmitter 创建、发布并触发 DolphinScheduler 工作流"),
            item("DEVELOPMENT_NATIVE", "DOLPHIN_SCHEDULER", "DOLPHIN_SCHEDULER", "DolphinScheduler 工作流", true, true,
                    "Flink、Spark、PyFlink 和 Shell 原生任务由 DolphinScheduler 承载"),
            item("ETL_PIPELINE", "LOCAL_ETL", "XXL_JOB", "本平台 DAG 执行器", true, true,
                    "可视化 ETL 在平台内按有界线程池执行，XXL-Job 只负责扫描和触发"),
            item("INTEGRATION_TASK", "LOCAL_ETL", "XXL_JOB", "本平台 ETL 流程", true, true,
                    "集成工作台绑定并委托本平台 ETL 流程，不直接创建 DolphinScheduler 工作流"),
            item("JOB_ORCHESTRATION", "DOLPHIN_SCHEDULER", "DOLPHIN_SCHEDULER", "DolphinScheduler 工作流",
                    true, true, "多任务依赖由开发任务的 DS 工作流和依赖快照控制"));

    /** 返回页面可使用的完整执行拓扑目录。 */
    public List<ExecutionTopologyRespVO> catalog() {
        return CATALOG;
    }

    /**
     * 根据页面任务类型解析执行拓扑。
     *
     * @param taskKind 任务类型编码
     * @return 执行拓扑；未知类型返回 null，由 Controller 统一返回参数错误
     */
    public ExecutionTopologyRespVO resolve(String taskKind) {
        if (taskKind == null) {
            return null;
        }
        String normalized = taskKind.trim().toUpperCase(Locale.ROOT);
        return CATALOG.stream().filter(item -> item.getTaskKind().equals(normalized)).findFirst().orElse(null);
    }

    private static ExecutionTopologyRespVO item(String taskKind, String engine, String scheduler,
            String executionBoundary, boolean scheduleSupported, boolean stopSupported, String description) {
        return ExecutionTopologyRespVO.builder().taskKind(taskKind).engine(engine).scheduler(scheduler)
                .executionBoundary(executionBoundary).scheduleSupported(scheduleSupported)
                .stopSupported(stopSupported).description(description).build();
    }
}
