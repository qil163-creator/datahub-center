package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.connection.EtlApiPort;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionResolver;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 一次流程执行的参数
 *
 * @author Li
 */
@Data
@Builder
public class EtlRunOptions {

    /** 可选的取消功能及真实工作进程/资源退出观测。 */
    private cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl executionControl;

    private cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort fileOutputPort;

    /** 不可变的数据库行数、字节数、LOB 和参数预算，并受流程限制进一步约束。 */
    @Builder.Default
    private cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits databaseBatchLimits =
            cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits.defaults();

    /**
     * 流程参数值（name → value，用于 ${params.xxx} 替换）
     */
    private Map<String, String> params;

    /**
     * 数据源解析回调（必填）
     */
    private EtlConnectionResolver connectionResolver;

    /** 节点编号对应的预检计划；非空映射下脚本节点必须使用已授权的条目。 */
    private Map<String, EtlSqlScriptBinding> sqlScriptPlans;

    /**
     * API 数据端口（可空，Rest API 输入节点必需）
     */
    private EtlApiPort apiPort;

    /** 已授权且冻结的字典、质量策略适配器。 */
    private cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort rowPolicyPort;

    /** 可选通用读取策略；多租户业务调用方须显式安装其授权适配器。 */
    private cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort readPolicyPort;

    /** 按需启用源行溯源信息；绝不作为数据列暴露，也不由转换器自动传播。 */
    private boolean captureSourceOrigins;

    private cn.iocoder.yudao.framework.etl.core.connection.EtlStagedOutputPort stagedOutputPort;

    private cn.iocoder.yudao.framework.etl.core.runtime.EtlRowObserver rowObserver;

    /**
     * 执行监听器（可空）
     */
    private EtlRunListener listener;

    /** 服务端显式传入的目录策略；为空时采用引擎配置，节点 JSON 无法授权目录。 */
    private cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy fileOutputPolicy;

    /** 轻量执行边界，超过边界应交给外部计算引擎。 */
    @Builder.Default
    private int maxNodes = 32;

    @Builder.Default
    private long timeoutSeconds = 3600;

    @Builder.Default
    private long maxRows = 100_000;

    @Builder.Default
    private long maxBytes = 64L * 1024 * 1024;

    @Builder.Default
    private long maxRowBytes = 1024L * 1024;

    /** 运行观测采样间隔（毫秒），回调监听器的 onRuntimeSample；0 表示关闭，开启时允许 500—60000。 */
    @Builder.Default
    private long runtimeSampleMillis = 0;

    /** 持久停止请求或租约丢失探测。 */
    private java.util.function.BooleanSupplier stopRequested;

    /** 调用方上下文传播，框架不依赖租户或业务模型。 */
    private java.util.function.UnaryOperator<Runnable> taskWrapper;

}
