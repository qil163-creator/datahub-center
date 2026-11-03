package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.connection.*;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileStore;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_FILE_STATE_CHANGED;

/** 历史文件的来源完整性凭据；DB Switch 负责 SQL 与物理身份，biz-etl 负责 I/O 与编解码。 */
@Service
public class EtlFileSourceProofService {

    // 保留已有快照字段名称；凭据只核对查询、连接和物理内容，不再包含治理策略。
    static final String KEY = "fileSourcePolicyProof";

    static final Set<String> FILE_TOOLS = Set.of("write-csv", "write-json", "write-excel", "write-text");

    // 这些内置功能仅处理已读取数据行或明确的常量；不能仅按名称允许任意代码、其他连接器、
    // 治理计划查询、SQL 副作用、解密还原或未展开的子流程。
    private static final Set<String> PROVEN_TOOLS = Set.of("read-db", "write-csv", "write-json", "write-excel",
            "write-text",
            "column-map", "add-constant", "value-map", "field-split", "split-to-rows", "flatten-row",
            "sort-records", "sort-merge", "data-merge", "multi-join", "cartesian-product", "row-column",
            "generate-records", "random-records", "number-range", "id-generator", "data-filter", "data-condition");

    @Resource
    private DatabaseQuerySupport queries;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private EtlConnectionResolverAdapter connections;

    @Resource
    private EtlRecordMapper records;

    @Resource
    private EtlRunCoordinator coordinator;

    @Resource
    private EtlFileNativeSourceGuard nativeGuard;

    private record Source(String node, Long database, String revision, String connectionHash,
                          String queryHash, String authorizedHash,
                          String relationHash, String storageHash) {

    }

    private record PreparedSource(Source source, String originalSql, String authorizedSql, String schema) {

    }

    /** 在执行副作用之前，且在任何来源凭据对运行快照计算哈希之前调用；旧重放凭据绝不沿用。 */
    public Session prepare(EtlRecordDO run, EtlDag dag, Map<String, String> parameters) {
        requireIdentity(run);
        var snapshot = JSONUtil.parseObj(run.getConfigJson());
        if (snapshot.containsKey(KEY)) {
            snapshot.remove(KEY);
            persist(run, run.getConfigJson(), snapshot.toString());
        }
        boolean hasFile = dag.getNodes().stream().anyMatch(node -> FILE_TOOLS.contains(node.getToolCode()));
        boolean hasDb = dag.getNodes().stream().anyMatch(node -> "read-db".equals(node.getToolCode()));
        if (!hasFile || !hasDb || !eligible(dag, snapshot)) {
            return new Session(run, dag, parameters, Map.of());
        }
        Map<String, PreparedSource> sources = new LinkedHashMap<>();
        try {
            for (var node : dag.getNodes()) {
                if ("read-db".equals(node.getToolCode())) {
                    if (sources.size() >= 64) {
                        throw unavailable();
                    }
                    sources.put(node.getNo(), freeze(node, run.getExecutionUserId(), parameters));
                }
            }
        } catch (EtlFileNativeSourceGuard.Unproven unsupported) {
            // 可选下载凭据不得因其他数据库产品、视图、RLS、
            // 不支持的 SQL 或不可用的元数据而影响普通 ETL；本次运行中的任何源端均不能仅获部分授权。
            return new Session(run, dag, parameters, Map.of());
        }
        return new Session(run, dag, parameters, sources);
    }

    /** 下载准入绝不重新生成文件，而是证明所有原始源端仍受相同规则约束。 */
    public void verify(EtlRecordDO run, EtlDag dag, Map<String, String> parameters) {
        try {
            requireIdentity(run);
            var snapshot = JSONUtil.parseObj(run.getConfigJson());
            if (!eligible(dag, snapshot) || !(snapshot.get(KEY) instanceof cn.hutool.json.JSONObject proof)
                    || !Objects.equals(proof.getInt("version"), 2) || !"SEALED".equals(proof.getStr("state"))
                    || !Objects.equals(strictId(proof.get("actor")), run.getExecutionUserId())
                    || !Objects.equals(strictId(proof.get("run")), run.getId())
                    || !Objects.equals(strictId(proof.get("pipeline")), run.getPipelineId())
                    || !Objects.equals(proof.getStr("graphHash"), graphHash(dag))
                    || !Objects.equals(proof.getStr("parametersHash"), parameterHash(parameters))) {
                throw unavailable();
            }
            var reads = dag.getNodes().stream().filter(node -> "read-db".equals(node.getToolCode())).toList();
            var entries = proof.getJSONArray("sources");
            if (entries == null || entries.size() != reads.size() || entries.isEmpty() || entries.size() > 64) {
                throw unavailable();
            }
            Map<String, Source> old = new LinkedHashMap<>();
            for (Object entry : entries) {
                if (!(entry instanceof cn.hutool.json.JSONObject value)) {
                    throw unavailable();
                }
                Source source = new Source(value.getStr("node"), strictId(value.get("database")),
                        value.getStr("revision"),
                        value.getStr("connectionHash"), value.getStr("queryHash"),
                        value.getStr("authorizedHash"),
                        value.getStr("relationHash"), value.getStr("storageHash"));
                if (source.node() == null || source.revision() == null || source.revision().isBlank()
                        || !hashValue(source.connectionHash()) || !hashValue(source.queryHash())
                        || !hashValue(source.authorizedHash()) || !hashValue(source.relationHash()) || !hashValue(
                        source.storageHash())
                        || old.put(source.node(), source) != null) {
                    throw unavailable();
                }
            }
            for (var node : reads) {
                if (!Objects.equals(old.get(node.getNo()),
                        freeze(node, run.getExecutionUserId(), parameters).source())) {
                    throw unavailable();
                }
            }
        } catch (RuntimeException rejected) {
            // 提供方、网络或解析器异常可能包含 SQL、URL 或凭据，绝不暴露其原因。
            throw unavailable();
        }
    }

    public final class Session {

        private final EtlRecordDO run;

        private final EtlDag dag;

        private final Map<String, String> parameters;

        private final Map<String, PreparedSource> sources;

        private final String originalConfig, originalGraphHash, originalParameterHash;

        private final Set<String> observed = ConcurrentHashMap.newKeySet();

        private final Set<String> completed = ConcurrentHashMap.newKeySet();

        private final Set<String> openedConnections = ConcurrentHashMap.newKeySet();

        private final Set<String> drainedConnections = ConcurrentHashMap.newKeySet();

        private boolean sealed;

        /**
         * 创建会话实例。
         *
         * @param run 运行
         * @param dag DAG
         * @param parameters 参数定义或取值
         * @param sources 来源
         */
        private Session(EtlRecordDO run, EtlDag dag, Map<String, String> parameters,
                Map<String, PreparedSource> sources) {
            this.run = run;
            this.dag = dag;
            this.parameters = Map.copyOf(parameters);
            this.sources = Map.copyOf(sources);
            this.originalConfig = run.getConfigJson();
            this.originalGraphHash = graphHash(dag);
            this.originalParameterHash = parameterHash(parameters);
        }

        /** 校验引擎即将使用的同一个连接对象，不能另行解析一个无关连接。 */
        public EtlConnectionInfo checkConnection(Long database, EtlConnectionInfo connection) {
            if (!sources.isEmpty()) {
                for (var source : sources.values()) {
                    if (Objects.equals(source.source().database(), database)) {
                        requireConnection(source.source(), connection);
                        requireNative(source, connection, run.getExecutionUserId());
                    }
                }
            }
            return connection;
        }

        /**
         * 包装读取。
         *
         * @param delegate 委托
         * @return ETL读取策略端口
         */
        public EtlReadPolicyPort wrapRead(EtlReadPolicyPort delegate) {
            if (sources.isEmpty()) {
                return delegate;
            }
            return new EtlReadPolicyPort() {
                /**
                 * 校验读取连接。
                 *
                 * @param node 节点
                 * @param database 数据源
                 * @param sql SQL
                 * @param connection 数据库连接
                 * @param stage 阶段
                 */
                public void validateReadConnection(String node, Long database, String sql,
                        java.sql.Connection connection, ReadConnectionStage stage) {
                    var source = sources.get(node);
                    if (source == null || !Objects.equals(database, source.source().database()) || !observed.contains(
                            node)
                            || !Objects.equals(sql, source.authorizedSql()) || stage == null) {
                        throw unavailable();
                    }
                    try {
                        nativeGuard.validateActual(connection, sql, source.schema(),
                                new EtlFileNativeSourceGuard.Identity(source.source().relationHash(),
                                        source.source().storageHash()));
                    } catch (RuntimeException denied) {
                        throw unavailable();
                    }
                    if (stage == ReadConnectionStage.OPENED) {
                        if (!openedConnections.add(node)) {
                            throw unavailable();
                        }
                    } else {
                        if (!openedConnections.contains(node) || !drainedConnections.add(node)) {
                            throw unavailable();
                        }
                    }
                }

                /**
                 * 获取会话的重写。
                 *
                 * @param node 节点
                 * @param database 数据源
                 * @param sql SQL
                 * @return 会话的重写文本
                 */
                public String rewrite(String node, Long database, String sql) {
                    var source = expected(node, database, sql);
                    try {
                        access.requireDatabase(database);
                        var connection = connections.resolve(database);
                        requireConnection(source.source(), connection);
                        String actual = queries.normalizeSelect(database, sql);
                        if (!Objects.equals(actual, source.authorizedSql())) {
                            throw unavailable();
                        }
                        requireNative(source, connection, run.getExecutionUserId());
                        observed.add(node);
                        return actual;
                    } catch (RuntimeException denied) {
                        throw unavailable();
                    }
                }

                /**
                 * 按条件筛选会话。
                 *
                 * @param node 节点
                 * @param database 数据源
                 * @param sql SQL
                 * @param rows 待处理的行数据
                 * @return 按条件筛选会话结果集合
                 */
                public List<Map<String, Object>> filter(String node, Long database, String sql,
                        List<Map<String, Object>> rows) {
                    throw unavailable(); // 凭据必须绑定实际原始 SQL 与归一化 SQL，即使输入为空也不例外。
                }

                /**
                 * 按条件筛选会话。
                 *
                 * @param node 节点
                 * @param database 数据源
                 * @param sql SQL
                 * @param rows 待处理的行数据
                 * @param original 原始记录
                 * @return 按条件筛选会话结果集合
                 */
                public List<Map<String, Object>> filter(String node, Long database, String sql,
                        List<Map<String, Object>> rows, String original) {
                    var source = expected(node, database, original);
                    if (!observed.contains(node) || !Objects.equals(sql, source.authorizedSql())) {
                        throw unavailable();
                    }
                    try {
                        access.requireDatabase(database);
                        var connection = connections.resolve(database);
                        requireConnection(source.source(), connection);
                        requireNative(source, connection, run.getExecutionUserId());
                        return rows;
                    } catch (RuntimeException denied) {
                        throw unavailable();
                    }
                }
            };
        }

        /**
         * 获取会话的预期。
         *
         * @param node 节点
         * @param database 数据源
         * @param sql SQL
         * @return 已准备来源
         */
        private PreparedSource expected(String node, Long database, String sql) {
            var source = sources.get(node);
            if (source == null || !Objects.equals(source.source().database(), database)
                    || !Objects.equals(source.originalSql(), sql)) {
                throw unavailable();
            }
            return source;
        }

        /** 引擎在执行及关闭后报告成功，包括成功的零行查询；仅完成 SQL 改写并不足够。 */
        public EtlRunListener wrapListener(EtlRunListener delegate) {
            if (sources.isEmpty()) {
                return delegate;
            }
            return new EtlRunListener() {
                /**
                 * 处理流程日志。
                 *
                 * @param line 行
                 */
                public void onPipelineLog(String line) {
                    delegate.onPipelineLog(line);
                }

                /**
                 * 处理节点开始。
                 *
                 * @param node 节点
                 */
                public void onNodeStart(String node) {
                    delegate.onNodeStart(node);
                }

                /**
                 * 处理节点日志。
                 *
                 * @param node 节点
                 * @param line 行
                 */
                public void onNodeLog(String node, String line) {
                    delegate.onNodeLog(node, line);
                }

                /**
                 * 处理节点成功。
                 *
                 * @param node 节点
                 * @param read 读取
                 * @param written 已写入
                 */
                public void onNodeSuccess(String node, long read, long written) {
                    if (sources.containsKey(node)) {
                        if (!observed.contains(node) || !drainedConnections.contains(node) || read < 0 || written < 0) {
                            throw unavailable();
                        }
                        delegate.onNodeSuccess(node, read, written);
                        completed.add(node);
                    } else {
                        delegate.onNodeSuccess(node, read, written);
                    }
                }

                /**
                 * 处理节点失败。
                 *
                 * @param node 节点
                 * @param error 失败原因
                 */
                public void onNodeFail(String node, String error) {
                    completed.remove(node);
                    delegate.onNodeFail(node, error);
                }

                /**
                 * 处理节点指标。
                 *
                 * @param node 节点
                 * @param read 读取
                 * @param written 已写入
                 * @param ports 端口
                 */
                public void onNodeMetrics(String node, long read, long written, Map<String, Long> ports) {
                    delegate.onNodeMetrics(node, read, written, ports);
                }

                /**
                 * 处理运行时样本。
                 *
                 * @param sample 样本
                 */
                public void onRuntimeSample(cn.iocoder.yudao.framework.etl.core.runtime.EtlRuntimeSample sample) {
                    delegate.onRuntimeSample(sample);
                }
            };
        }

        /**
         * 包装文件。
         *
         * @param delegate 委托
         * @return ETL 文件输出端口
         */
        public EtlFileOutputPort wrapFiles(EtlFileOutputPort delegate) {
            if (sources.isEmpty() || delegate == null) {
                return delegate;
            }
            return new EtlFileOutputPort() {
                /**
                 * 预留当前操作所需的资源或领域占用，并返回对应凭据。
                 *
                 * @param node 节点
                 * @param directory 目录
                 * @param name 名称
                 * @return 绑定
                 */
                public EtlFileStore.Binding reserve(String node, String directory, String name) throws IOException {
                    return delegate.reserve(node, directory, name);
                }

                /**
                 * 在创建前记录创建。
                 *
                 * @param binding 绑定
                 */
                public void beforeCreate(EtlFileStore.Binding binding) {
                    delegate.beforeCreate(binding);
                }

                /**
                 * 记录已创建会话。
                 *
                 * @param binding 绑定
                 * @param key 标识键
                 */
                public void created(EtlFileStore.Binding binding, String key) {
                    delegate.created(binding, key);
                }

                /**
                 * 记录临时意图。
                 *
                 * @param binding 绑定
                 * @param name 名称
                 */
                public void temporaryIntent(EtlFileStore.Binding binding, String name) {
                    delegate.temporaryIntent(binding, name);
                }

                /**
                 * 记录临时已创建。
                 *
                 * @param binding 绑定
                 * @param name 名称
                 * @param key 标识键
                 */
                public void temporaryCreated(EtlFileStore.Binding binding, String name, String key) {
                    delegate.temporaryCreated(binding, name, key);
                }

                /**
                 * 记录临时已清理。
                 *
                 * @param binding 绑定
                 * @param name 名称
                 * @param key 标识键
                 */
                public void temporaryCleaned(EtlFileStore.Binding binding, String name, String key) {
                    delegate.temporaryCleaned(binding, name, key);
                }

                /**
                 * 记录就绪会话。
                 *
                 * @param output 输出目标
                 */
                public void ready(EtlFileStore.Prepared output) {
                    delegate.ready(output);
                }

                /**
                 * 批准发布。
                 *
                 * @param outputs 输出
                 */
                public void approvePublish(List<EtlFileStore.Prepared> outputs) {
                    seal();
                    delegate.approvePublish(outputs);
                }

                /**
                 * 记录已发布会话。
                 *
                 * @param output 输出目标
                 */
                public void published(EtlFileStore.Prepared output) {
                    delegate.published(output);
                }

                /**
                 * 记录已丢弃会话。
                 *
                 * @param binding 绑定
                 * @param state 状态
                 */
                public void discarded(EtlFileStore.Binding binding, String state) {
                    delegate.discarded(binding, state);
                }
            };
        }

        /**
         * 封存会话。
         */
        private synchronized void seal() {
            if (sealed || !completed.equals(sources.keySet()) || !Objects.equals(run.getConfigJson(), originalConfig)
                    || !Objects.equals(graphHash(dag), originalGraphHash)) {
                throw unavailable();
            }
            try {
                for (var node : dag.getNodes()) {
                    if (sources.containsKey(node.getNo())) {
                        if (!Objects.equals(sources.get(node.getNo()).source(),
                                freeze(node, run.getExecutionUserId(), parameters).source())) {
                            throw unavailable();
                        }
                    }
                }
            } catch (RuntimeException changed) {
                throw unavailable();
            }
            var snapshot = JSONUtil.parseObj(run.getConfigJson());
            if (snapshot.containsKey(KEY)) {
                throw unavailable();
            }
            Map<String, Object> proof = new TreeMap<>();
            proof.put("version", 2);
            proof.put("state", "SEALED");
            // 凭据中的编号始终使用十进制字符串，包括超出 JavaScript 安全整数范围的运行编号。
            proof.put("actor", run.getExecutionUserId().toString());
            proof.put("run", run.getId().toString());
            proof.put("pipeline", run.getPipelineId().toString());
            proof.put("graphHash", originalGraphHash);
            proof.put("parametersHash", originalParameterHash);
            proof.put("sources", sources.values().stream().sorted(Comparator.comparing(value -> value.source().node()))
                    .map(value -> {
                        var source = value.source();
                        Map<String, Object> item = new TreeMap<>();
                        item.put("node", source.node());
                        item.put("database", source.database().toString());
                        item.put("revision", source.revision());
                        item.put("connectionHash", source.connectionHash());
                        item.put("queryHash", source.queryHash());
                        item.put("authorizedHash", source.authorizedHash());
                        item.put("relationHash", source.relationHash());
                        item.put("storageHash", source.storageHash());
                        return item;
                    }).toList());
            String encoded = EtlJsonValues.write(proof);
            if (encoded.length() > 128 * 1024) {
                throw unavailable();
            }
            snapshot.set(KEY, JSONUtil.parseObj(encoded));
            persist(run, run.getConfigJson(), snapshot.toString());
            sealed = true;
        }
    }

    /**
     * 解析节点参数并校验数据源及归一化 SQL，固定文件成果所需的来源证明。
     *
     * @param node 节点
     * @param actor 操作人编号
     * @param parameters 参数定义或取值
     * @return 已准备来源
     */
    private PreparedSource freeze(EtlNodeDef node, Long actor, Map<String, String> parameters) {
        try {
            var config = JSONUtil.parseObj(new EtlVariableContext(parameters).resolveJson(node.getNodeJson()));
            Long database = config.getLong("databaseId");
            access.requireDatabase(database);
            var connection = connections.resolve(database);
            if (connection == null || connection.getConfigurationRevision() == null || connection.getConfigurationRevision()
                    .isBlank()) {
                throw unavailable();
            }
            String original = config.getInt("ifSql", 0) == 1 ? config.getStr("sql")
                    : "SELECT * FROM " + EtlTableReference.resolve(connection, config.getStr("tableName"),
                    config.getStr("schemaName")).quoted();
            if (original == null || original.isBlank()) {
                throw unavailable();
            }
            String normalized = queries.normalizeSelect(database, original);
            var physical = nativeGuard.inspect(connection, normalized, actor);
            if (physical == null || !hashValue(physical.relationHash()) || !hashValue(physical.storageHash())) {
                throw new EtlFileNativeSourceGuard.Unproven();
            }
            Source source = new Source(node.getNo(), database, connection.getConfigurationRevision(),
                    EtlRowOriginCapture.connectionHash(connection),
                    hash(original), hash(normalized), physical.relationHash(),
                    physical.storageHash());
            // 固定引擎实际使用的连接，拒绝读取过程中连接修订变化。
            access.requireDatabase(database);
            requireConnection(source, connections.resolve(database));
            return new PreparedSource(source, original, normalized, connection.getSchema());
        } catch (EtlFileNativeSourceGuard.Unproven unsupported) {
            throw unsupported;
        } catch (RuntimeException denied) {
            throw unavailable();
        }
    }

    /**
     * 重新检查原生关系及存储指纹，拒绝来源物理状态已变化的证明。
     *
     * @param source 来源数据
     * @param connection 数据库连接
     * @param actor 操作人编号
     */
    private void requireNative(PreparedSource source, EtlConnectionInfo connection, Long actor) {
        try {
            var current = nativeGuard.inspect(connection, source.authorizedSql(), actor);
            if (current == null || !Objects.equals(current.relationHash(), source.source().relationHash())
                    || !Objects.equals(current.storageHash(), source.source().storageHash())) {
                throw unavailable();
            }
        } catch (RuntimeException changed) {
            throw unavailable();
        }
    }

    /**
     * 在当前有效执行令牌下按原配置 CAS 保存新来源证明，拒绝停止或快照并发变化。
     *
     * @param run 运行
     * @param previous 先前
     * @param next 下次
     */
    private void persist(EtlRecordDO run, String previous, String next) {
        if (run.getExecutionToken() == null || !coordinator.writeOwned(run.getId(), run.getExecutionToken(), () -> {
            if (coordinator.shouldStop(run.getId(), run.getExecutionToken())) {
                throw unavailable();
            }
            int changed = records.update(null,
                    Wrappers.lambdaUpdate(EtlRecordDO.class).eq(EtlRecordDO::getId, run.getId())
                            .eq(EtlRecordDO::getExecutionToken, run.getExecutionToken())
                            .apply("config_json = CAST({0} AS jsonb)", previous)
                            .set(EtlRecordDO::getConfigJson, next,
                                    "typeHandler=cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler")
                            .setSql("row_version=row_version+1"));
            if (changed != 1) {
                throw unavailable();
            }
        })) {
            throw unavailable();
        }
        // 仅在经代理执行的自有事务提交后更新；后续完成状态更新仅涉及指定字段。
        run.setConfigJson(next);
    }

    /**
     * 核对证明中的数据源编号、连接修订及连接摘要仍与当前配置一致。
     *
     * @param source 来源数据
     * @param connection 数据库连接
     */
    private static void requireConnection(Source source, EtlConnectionInfo connection) {
        if (connection == null || !Objects.equals(connection.getDatabaseId(), source.database())
                || !Objects.equals(connection.getConfigurationRevision(), source.revision())
                || !Objects.equals(EtlRowOriginCapture.connectionHash(connection), source.connectionHash())) {
            throw unavailable();
        }
    }

    /**
     * 判断图规模、工具集合及治理计划是否属于可证明来源的支持范围。
     *
     * @param dag DAG
     * @param snapshot 快照
     * @return 处理是否成功
     */
    private static boolean eligible(EtlDag dag, cn.hutool.json.JSONObject snapshot) {
        Object plans = snapshot.get("governancePlans");
        return dag.getNodes().size() <= 256 && dag.getNodes().stream()
                .allMatch(node -> PROVEN_TOOLS.contains(node.getToolCode()))
                && (plans == null || plans instanceof Map<?, ?> map && map.isEmpty());
    }

    /**
     * 校验来源证明所属运行、执行用户、流程及冻结配置的持久身份。
     *
     * @param run 运行
     */
    private static void requireIdentity(EtlRecordDO run) {
        if (run == null || run.getId() == null || run.getId() <= 0 || run.getExecutionUserId() == null || run.getExecutionUserId() <= 0
                || run.getPipelineId() == null || run.getPipelineId() <= 0 || run.getConfigJson() == null) {
            throw unavailable();
        }
    }

    /**
     * 按稳定节点及边内容计算执行图摘要。
     *
     * @param dag DAG
     * @return 计算后的摘要或签名字符串
     */
    private static String graphHash(EtlDag dag) {
        var nodes = dag.getNodes().stream().map(node -> {
            Map<String, Object> item = new TreeMap<>();
            item.put("no", node.getNo());
            item.put("tool", node.getToolCode());
            item.put("type", node.getToolType());
            item.put("config", node.getNodeJson());
            item.put("failGoOn", node.isFailGoOn());
            item.put("timeoutMinutes", Objects.requireNonNullElse(node.getOverTimeMinutes(), 0));
            return item;
        }).toList();
        var edges = dag.getEdges().stream().map(edge -> {
            Map<String, Object> item = new TreeMap<>();
            item.put("source", edge.getSourceNo());
            item.put("target", edge.getTargetNo());
            item.put("control", edge.isControl());
            item.put("output", edge.resolveOutputPort());
            item.put("input", edge.getInputPort());
            return item;
        }).toList();
        return hash(EtlJsonValues.write(Map.of("nodes", nodes, "edges", edges)));
    }

    /**
     * 按参数名排序后编码并计算冻结参数摘要。
     *
     * @param parameters 参数定义或取值
     * @return 计算后的摘要或签名字符串
     */
    private static String parameterHash(Map<String, String> parameters) {
        return hash(EtlJsonValues.write(new TreeMap<>(parameters)));
    }

    /**
     * 计算输入内容摘要。
     *
     * @param text 待处理文本
     * @return 计算后的摘要或签名字符串
     */
    private static String hash(String text) {
        return DigestUtil.sha256Hex(text);
    }

    /**
     * 判断输入是否为 64 位小写十六进制摘要。
     *
     * @param value 待处理的值
     * @return 判断结果
     */
    private static boolean hashValue(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    /**
     * 严格解析并校验记录编号。
     *
     * @param value 待处理的值
     * @return 严格编号数值
     */
    private static Long strictId(Object value) {
        if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) {
            throw unavailable();
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            throw unavailable();
        }
    }

    /**
     * 构造当前依赖或资源不可用时使用的异常。
     *
     * @return 服务异常
     */
    private static cn.iocoder.yudao.framework.common.exception.ServiceException unavailable() {
        return exception(ETL_FILE_STATE_CHANGED);
    }
}
