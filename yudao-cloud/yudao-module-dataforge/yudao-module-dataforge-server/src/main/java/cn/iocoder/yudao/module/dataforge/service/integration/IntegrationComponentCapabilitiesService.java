package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeConnectorCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeFileCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeMongoCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeOdpsCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeHttpCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeHdfsCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeHiveCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRabbitCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRocketCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRedisCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeFtpCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeTdengineCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCapabilities;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationComponentCapabilitiesRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationComponentCapabilitiesRespVO.Component;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationComponentCapabilitiesRespVO.Status;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.INTEGRATION_CONFIG_INVALID;

/** 调色板与实际编译能力对齐；任务参数、资源授权和运行结果仍由流程检查与执行链确认。 */
@Service
public class IntegrationComponentCapabilitiesService {
    static final Set<String> FLINK_COMPONENTS = Set.of("read-db", "read-kafka", "read-tdengine", "java-script", "custom-token", "data-clean",
            "field-split", "dedup-records", "row-to-column", "column-to-row", "value-map", "insert-db");
    private static final List<String> COMPONENTS = List.of(
            "read-db", "read-odps", "read-excel", "read-csv", "read-json", "read-kafka", "read-hive", "read-hdfs",
            "rest-api", "read-mongodb", "read-redis", "read-rabbitmq", "read-tdengine", "read-rocketmq", "read-ftp", "read-webservice",
            "java-script", "custom-token", "data-clean", "multi-join", "sort-records", "field-split", "field-derive",
            "dedup-records", "row-to-column", "column-to-row", "data-encrypt", "data-decrypt", "id-generator", "calculator",
            "value-map", "add-constant", "number-range", "string-operation", "string-replace", "column-map", "set-field-value",
            "data-condition", "sub-pipeline", "insert-db", "write-odps", "write-hive", "write-hdfs", "write-mongodb",
            "write-redis", "write-rabbitmq", "write-kafka", "write-rocketmq", "write-ftp");

    public IntegrationComponentCapabilitiesRespVO get(String engine, String mode) {
        if (!Set.of("SPARK", "FLINK", "DATAX").contains(engine == null ? "" : engine)
                || !Set.of("OFFLINE", "REALTIME").contains(mode == null ? "" : mode)) {
            throw exception(INTEGRATION_CONFIG_INVALID, "引擎或处理模式无效");
        }
        boolean streaming = "REALTIME".equals(mode);
        Map<String, String> nativeCapabilities = "DATAX".equals(engine) ? Map.of()
                : EtlNativeSqlCapabilities.componentLimitations(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming);
        return new IntegrationComponentCapabilitiesRespVO(engine, mode, COMPONENTS.stream()
                .map(code -> describe(code, engine, streaming, nativeCapabilities)).toList());
    }

    private Component describe(String code, String engine, boolean streaming, Map<String, String> nativeCapabilities) {
        if (streaming && !"FLINK".equals(engine)) return unavailable(code, "实时任务必须选择 Flink 引擎");
        if ("FLINK".equals(engine) && !FLINK_COMPONENTS.contains(code))
            return unavailable(code, "当前Flink任务未开放此组件，请选择可用组件或切换Spark离线引擎");
        if ("DATAX".equals(engine)) {
            if (Set.of("read-db", "insert-db").contains(code))
                return partial(code, "需要数据源执行绑定，仅支持直接连接的全量表输入和表输出；幂等写入还需配置对应工作组");
            if ("dedup-records".equals(code))
                return partial(code, "通过已授权PostgreSQL表读取执行真实去重；支持整行去重，按键与逐字段大小写去重需要明确排序及唯一字段");
            return unavailable(code, "此组件尚未接入 DataX 集成执行链");
        }
        if (EtlNativeConnectorCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, "需要当前任务获授权的连接与主题及受管连接器，当前仅支持显式无凭据绑定；SASL/TLS认证尚未适配，消息格式和位点须通过流程检查");
        if (EtlNativeMongoCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, "需要当前任务获授权的集合与原生连接器，字段类型、查询和写入键须通过流程检查");
        if (EtlNativeOdpsCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, "需要授权的项目、表分区、完整字段和受管凭据，支持有界读取及提交写入；云端真实读写尚待验收");
        if (EtlNativeHttpCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, "read-webservice".equals(code)
                    ? "需要授权WSDL、导入文档及服务端点，支持SOAP literal与HTTP绑定、显式字段和受管秘密请求头；encoded及复杂Schema语义需独立适配"
                    : "需要当前任务获授权的接口资源与执行环境地址白名单，支持有界分页与显式字段类型；认证须通过受管秘密适配");
        if (EtlNativeHdfsCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, "需要当前任务获授权的HDFS根目录及工作节点路径授权，支持CSV或文本读取与新文件写入，已有文件不覆盖");
        if (EtlNativeHiveCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, "需要已授权的Hive普通外部文本表与HDFS存储绑定，支持有界读取及追加写入；事务表和分区表须独立适配");
        if (EtlNativeRabbitCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, streaming ? "需要授权队列及秘密引用、受管连接器和启用checkpoint的工作环境，支持持续JSON消息与发布确认，采用至少一次语义" : "需要当前批引擎独立安装声明和授权队列，最多65535条启动基数独占浏览后重入队；生产并发时不是点时快照，输出确认后采用至少一次语义");
        if (EtlNativeRocketCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, streaming ? "需要授权的NameServer、Broker、主题、独立消费者组及秘密引用，支持持续JSON消息、checkpoint位点恢复和同步发送确认，采用至少一次语义" : "需要当前批引擎独立安装声明、授权NameServer/主题/组和秘密引用，固定各队列结束位点且不提交消费组位点；同步确认输出采用至少一次语义");
        if (EtlNativeRedisCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, "需要当前任务获授权的Redis数据库、键范围和受管秘密引用，支持有界string/hash/list读写；SCAN不保证快照，list追加采用至少一次语义");
        if (EtlNativeFtpCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, "需要锁定到专用账户虚拟根目录的FTP资源和受管秘密引用，支持有界CSV/文本读取、完整字段映射及STOU新文件输出");
        if (EtlNativeTdengineCompiler.supportedCodes(EtlNativeSqlCompiler.Engine.valueOf(engine), streaming).contains(code))
            return partial(code, streaming ? "需要授权的TMQ主题、消费者组及秘密引用，执行环境须安装受管连接器，TDengine版本和完整字段合同须通过核验；主题结构变化会明确失败" : "需要当前批引擎独立安装的TDengine JDBC适配及固定物理表/秘密授权，使用真实元数据投影进行有限结果读取；Spark不能无损表达纳秒时间戳时明确拒绝");
        if (!streaming && EtlNativeFileCompiler.supportedCodes().contains(code))
            return partial(code, "使用当前任务获授权的执行资源和相对路径，需显式声明源字段；上传附件仅用于字段解析");
        if (!streaming && "SPARK".equals(engine) && Set.of("java-script", "custom-token").contains(code))
            return partial(code, "需要任务专属mTLS脚本资源、隔离Docker工作进程和已发布源码版本；每个有界分区独立整批执行，Token只留受管运行上下文");
        if ("FLINK".equals(engine) && "custom-token".equals(code))
            return partial(code, "需要任务专属mTLS脚本资源与隔离worker；每次应用启动执行一次空输入初始化，opaque上下文只留受管作用域，checkpoint算子恢复不刷新Token");
        if ("FLINK".equals(engine) && "java-script".equals(code))
            return partial(code, streaming
                    ? "需要显式条数/时间窗口、checkpoint及任务专属mTLS脚本资源，支持有界状态恢复和批次缓存重放，外部输出采用至少一次语义"
                    : "需要任务专属mTLS脚本资源与隔离Docker工作进程，脚本按完整有界分区执行；Token提供者先取得opaque受管上下文，仅授权当前驱动规划期后续HTTP源，不持久化原令牌");
        if ("sub-pipeline".equals(code))
            return partial(code, "需要具有执行权限的已发布固定版本，内部组件和具名端口须通过当前引擎检查");
        if ("dedup-records".equals(code)) return streaming
                ? partial(code, "需要明确的处理时间窗口、行与状态字节预算及受管DH_WINDOW_PICK函数；窗内按确定排序保留首条，checkpoint恢复后不重置窗口")
                : partial(code, "支持整行去重；按键或忽略大小写须明确排序及唯一字段，运行时核验后保留首条完整行");
        if (streaming && Set.of("row-to-column", "column-to-row").contains(code))
            return partial(code, "宽转长按原始字段合同无状态展开；长转宽需要明确处理时间窗口、状态预算和受管DH_WINDOW_PICK，按窗口结束结果输出");
        if (streaming && "insert-db".equals(code))
            return partial(code, "增量更新需要真实PostgreSQL主键非空事实、同主键的Flink目录表及受管JDBC sink安装证明，流程检查会拒绝缺失或不一致的映射");
        String toolCode = Set.of("column-to-row", "row-to-column").contains(code) ? "row-column" : code;
        if (!nativeCapabilities.containsKey(toolCode)) {
            if (streaming && "multi-join".equals(code)) return unavailable(code, "实时关联需要事件时间、状态保留和窗口边界");
            if (streaming && "sort-records".equals(code)) return unavailable(code, "实时排序需要时间和窗口边界");
            return unavailable(code, "此组件尚未接入当前引擎的集成执行链");
        }
        String reason = nativeCapabilities.get(toolCode);
        return new Component(code, reason.isBlank() ? Status.SUPPORTED : Status.PARTIAL, reason);
    }

    private Component partial(String code, String reason) { return new Component(code, Status.PARTIAL, reason); }
    private Component unavailable(String code, String reason) { return new Component(code, Status.UNAVAILABLE, reason); }
}
