package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dolphinscheduler.model.DataxTaskParameters;
import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters;
import cn.iocoder.yudao.framework.etl.core.connection.EtlTableReference;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander;
import cn.iocoder.yudao.framework.etl.core.compile.EtlTableReadConfig;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.EngineConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlConnectionResolverAdapter;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlSubflowService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

/** 现有 ETL 图到 DS 原生任务的领域映射；未支持的行转换明确拒绝，绝不改投本地引擎。 */
@Component
public class IntegrationFlowCompiler {
    @Resource private IntegrationRuntimeProperties properties;
    @Resource private EtlConnectionResolverAdapter connections;
    @Resource private ExecutionAccessService access;
    @Resource private cn.iocoder.yudao.module.dataforge.service.database.DatabaseService databases;
    @Resource private IntegrationDataxBindingsProperties dataxBindings;
    @Resource private EtlSubflowService subflows;
    @Resource private IntegrationHiveStorageSupport hiveStorage;
    @Resource private IntegrationTdengineBindingSupport tdengineBindings;
    @Resource private IntegrationRedisBindingSupport redisBindings;
    @Resource private IntegrationRabbitBindingSupport rabbitBindings;
    @Resource private IntegrationOdpsBindingSupport odpsBindings;
    @Resource private IntegrationRocketBindingSupport rocketBindings;
    @Resource private IntegrationNativeScriptBindingSupport scripts;

    public record Step(String id, String name, String taskType, Map<String, Object> parameters,
                       List<String> upstream) { }

    public JSONObject graph(String json) {
        if (json == null || json.length() > 1_000_000) throw invalid("流程超过允许大小");
        JSONObject graph;
        try { graph = JSONUtil.parseObj(json); } catch (RuntimeException malformed) { throw invalid("流程 JSON 无效"); }
        if (graph.getJSONArray("nodes") == null || graph.getJSONArray("edges") == null
                || graph.getJSONArray("nodes").size() > 200 || graph.getJSONArray("edges").size() > 400) {
            throw invalid("流程必须包含有界的 nodes 和 edges 数组");
        }
        var ids = new HashSet<String>();
        graph.getJSONArray("nodes").forEach(item -> {
            var node = JSONUtil.parseObj(item);
            String id = node.getStr("id");
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}") || !ids.add(id)) throw invalid("节点编号缺失或重复");
        });
        for (Object item : graph.getJSONArray("edges")) {
            var edge = JSONUtil.parseObj(item);
            if (!ids.contains(edge.getStr("sourceNodeId")) || !ids.contains(edge.getStr("targetNodeId"))) {
                throw invalid("连线引用不存在的节点");
            }
        }
        // 任务中禁止落连接凭据；数据源必须通过平台编号和受管 DS 绑定解析。
        rejectGraphSecrets(graph);
        // 仅在已完成大小和秘密扫描的执行副本中移除双列编辑器状态；保存方仍持久化原始流程。
        var graphNodes = graph.getJSONArray("nodes");
        for (int index = 0; index < graphNodes.size(); index++) {
            var node = JSONUtil.parseObj(graphNodes.get(index)); var properties = node.getJSONObject("properties");
            if (properties == null || properties.get("nodeJson") == null) continue;
            String tool = properties.getStr("toolCode", "");
            if ("read-db".equals(tool)) EtlTableReadConfig.validateDraft(JSONUtil.parseObj(properties.get("nodeJson")));
            if (!"insert-db".equals(tool) && !tool.startsWith("write-")) continue;
            var configuration = JSONUtil.parseObj(properties.get("nodeJson"));
            boolean changed = false;
            for (String key : List.of("fieldMappingColumns", "fieldMappingDraft", "fieldMappingLayout"))
                if (configuration.containsKey(key)) { configuration.remove(key); changed = true; }
            if (changed) { properties.set("nodeJson", configuration); node.set("properties", properties); graphNodes.set(index, node); }
        }
        return graph;
    }

    /** 产品引擎的组件白名单对草稿、正式保存、检查与运行保持一致，底层通用适配不构成入口授权。 */
    public void validateEngineComponents(IntegrationTaskDO task, String flowJson) {
        validateEngineComponents(task, graph(flowJson));
    }

    private static void validateEngineComponents(IntegrationTaskDO task, JSONObject graph) {
        if ("REALTIME".equals(task.getMode()) && !"FLINK".equals(task.getEngine())) throw invalid("实时任务只支持Flink引擎");
        if ("FLINK".equals(task.getEngine())) {
            for (Object item : graph.getJSONArray("nodes")) {
                var properties = JSONUtil.parseObj(item).getJSONObject("properties");
                String tool = properties == null ? "" : properties.getStr("toolCode", "");
                String component = properties == null ? "" : properties.getStr("componentCode", "");
                boolean compatible;
                if ("row-column".equals(tool)) compatible = component.isBlank() || Set.of("row-to-column", "column-to-row").contains(component);
                else if ("data-clean".equals(tool)) compatible = component.isBlank() || Set.of("data-clean", "dedup-records").contains(component);
                else compatible = IntegrationComponentCapabilitiesService.FLINK_COMPONENTS.contains(tool) && (component.isBlank() || component.equals(tool));
                if (!compatible) throw invalid("当前Flink任务仅开放12个组件，此节点不可用");
            }
            return;
        }
        if (!"DATAX".equals(task.getEngine())) return;
        if (!"OFFLINE".equals(task.getMode())) throw invalid("DataX只支持离线任务");
        for (Object item : graph.getJSONArray("nodes")) {
            var properties = JSONUtil.parseObj(item).getJSONObject("properties");
            String tool = properties == null ? "" : properties.getStr("toolCode", "");
            String component = properties == null ? "" : properties.getStr("componentCode", "");
            boolean valid = "read-db".equals(tool) && (component.isBlank() || component.equals("read-db"))
                    || "insert-db".equals(tool) && (component.isBlank() || component.equals("insert-db"))
                    || "data-clean".equals(tool) && (component.isBlank() || component.equals("dedup-records"))
                    && properties.get("nodeJson") != null && JSONUtil.parseObj(properties.get("nodeJson")).getBool("enableDeduplication", false);
            if (!valid) throw invalid("DataX仅支持表输入、去除重复记录和表输出组件");
        }
    }

    /** 检查不登记或激活用户源码版本。 */
    public List<Step> compile(IntegrationTaskDO task, String flowJson) {
        return compile(task, flowJson, IntegrationNativeScriptBindingSupport.Purpose.CHECK).steps();
    }

    public List<Step> compileForExecution(IntegrationTaskDO task, String flowJson) {
        return compile(task, flowJson, IntegrationNativeScriptBindingSupport.Purpose.RUN).steps();
    }

    public record Publication(List<Step> steps, IntegrationNativeScriptBindingSupport.Prepared scripts) {
        public Publication { steps = List.copyOf(steps); }
    }

    /** 只准备不可执行的有界临时版本，成功持久化后才可激活。 */
    public Publication compileForPublication(IntegrationTaskDO task, String flowJson) {
        return compile(task, flowJson, IntegrationNativeScriptBindingSupport.Purpose.PUBLICATION);
    }

    public void completePublication(Publication publication) {
        if (publication == null || publication.scripts() == null) return;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() { scripts.complete(publication.scripts()); }
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) scripts.abort(publication.scripts());
                }
            });
        } else scripts.complete(publication.scripts());
    }

    public void abortPublication(Publication publication) {
        if (publication != null && publication.scripts() != null) scripts.abort(publication.scripts());
    }

    private Publication compile(IntegrationTaskDO task, String flowJson, IntegrationNativeScriptBindingSupport.Purpose purpose) {
        var graph = graph(flowJson);
        validateEngineComponents(task, graph);
        List<Map<String, Object>> rawNodes = graph.getJSONArray("nodes").toList(Map.class).stream()
                .map(value -> (Map<String, Object>) value).toList();
        List<Map<String, Object>> rawEdges = graph.getJSONArray("edges").toList(Map.class).stream()
                .map(value -> (Map<String, Object>) value).toList();
        var expanded = expandSubflows(task, EtlGraphConverter.toNodeDefs(rawNodes), EtlGraphConverter.toEdgeDefs(rawEdges));
        for (var node : expanded.nodes())
            if ("read-db".equals(node.getToolCode())) EtlTableReadConfig.requireSupportedExecution(JSONUtil.parseObj(node.getNodeJson()));
        var prepared = prepareNative(task, expanded.nodes(), expanded.edges());
        var nodes = prepared.nodes();
        var edges = expanded.edges();
        if (nodes.isEmpty()) throw invalid("请添加并配置流程节点");
        for (var node : nodes) {
            // 钩子只能交给具有原子发布和恢复标记的受管写入器，其他引擎不能静默忽略。
            if ("insert-db".equals(node.getToolCode())) {
                var target = JSONUtil.parseObj(node.getNodeJson());
                if ((!target.getStr("preSql", "").isBlank() || !target.getStr("postSql", "").isBlank())
                        && (!"DATAX".equals(task.getEngine()) || target.getInt("dataSyncType", 1) != 2))
                    throw invalid("前后置 SQL 需要 PostgreSQL DataX 幂等写入，其他执行路径不支持");
            }
            if (node.isFailGoOn() || node.getOverTimeMinutes() != null && node.getOverTimeMinutes() > 0)
                throw invalid("节点【" + node.getName() + "】尚不支持独立超时或失败继续，请使用任务级运行策略");
        }
        Map<String, List<String>> upstream = new LinkedHashMap<>();
        nodes.forEach(node -> upstream.put(node.getNo(), new ArrayList<>()));
        for (var edge : edges) {
            upstream.get(edge.getTargetNo()).add(edge.getSourceNo());
        }
        var visited = new HashSet<String>();
        while (visited.size() < nodes.size()) {
            int before = visited.size();
            nodes.stream().filter(node -> visited.containsAll(upstream.get(node.getNo())))
                    .forEach(node -> visited.add(node.getNo()));
            if (before == visited.size()) throw invalid("流程包含循环依赖");
        }
        var config = JSONUtil.toBean(task.getEngineConfigJson(), EngineConfig.class);
        if ("DATAX".equals(task.getEngine())) return new Publication(datax(task, nodes, upstream, config), null);
        if (nodes.stream().anyMatch(node -> "sql-script".equals(node.getToolCode())))
            throw invalid("集成流程不接受独立 SQL 脚本，请使用具有受管数据源绑定的业务组件");
        if (hasScripts(expanded.nodes())) {
            // 完整初始化先通过与普通 SQL 相同的受管参数护栏，JAVA launcher 不获得任意会话命令入口。
            nativeStep(task, "native_validation", task.getName(), prepared.initializationSql() + "SELECT 1;", config, List.of());
            var program = scripts.compile(task, prepared.nodes(), edges, nativeResolver(task, prepared),
                    managedInitialization(task) + "\n" + prepared.initializationSql(), expanded.nodes(), purpose);
            try { return new Publication(List.of(scriptStep(task, program.plan().program(), config)), program); }
            catch (RuntimeException failure) { if (purpose == IntegrationNativeScriptBindingSupport.Purpose.PUBLICATION) scripts.abort(program); throw failure; }
        }
        String sql = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.compile(
                cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.valueOf(task.getEngine()),
                "REALTIME".equals(task.getMode()), nodes, edges,
                nativeResolver(task, prepared));
        sql = prepared.initializationSql() + sql;
        return new Publication(List.of(nativeStep(task, "native_flow", task.getName(), sql, config, List.of())), null);
    }

    /** 字段展示复用实际原生编译器；不提交、发布或执行 DS 任务。 */
    public cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Analysis analyzeNative(IntegrationTaskDO task,
            List<EtlNodeDef> nodes, List<cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef> edges) {
        validateEngineComponents(task, new JSONObject().set("nodes", nodes.stream().map(node -> Map.of("properties",
                Map.of("toolCode", node.getToolCode(), "nodeJson", node.getNodeJson()))).toList()));
        var expanded = expandSubflows(task, nodes, edges);
        var prepared = prepareNative(task, expanded.nodes(), expanded.edges());
        cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Analysis analysis;
        if (hasScripts(expanded.nodes())) {
            var config = JSONUtil.toBean(task.getEngineConfigJson(), EngineConfig.class);
            nativeStep(task, "native_validation", task.getName(), prepared.initializationSql() + "SELECT 1;", config, List.of());
            var program = scripts.compile(task, prepared.nodes(), expanded.edges(), nativeResolver(task, prepared),
                    managedInitialization(task) + "\n" + prepared.initializationSql(), expanded.nodes(), IntegrationNativeScriptBindingSupport.Purpose.CHECK);
            validateScriptProgram(task, program.plan().program(), config); analysis = program.plan().analysis();
        } else analysis = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.analyze(
                cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.valueOf(task.getEngine()),
                "REALTIME".equals(task.getMode()), prepared.nodes(), expanded.edges(), nativeResolver(task, prepared));
        analysis = new cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Analysis(
                prepared.initializationSql() + analysis.sql(), analysis.nodes());
        if (expanded.dependencies().isEmpty()) return analysis;
        var shapes = new LinkedHashMap<>(analysis.nodes());
        for (var call : nodes) {
            if (!EtlSubflowExpander.CALL.equals(call.getToolCode())) continue;
            var members = expanded.sourceMap().entrySet().stream()
                    .filter(entry -> call.getNo().equals(entry.getValue().designNodeNo()))
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
            var input = new LinkedHashMap<String, String>();
            var output = new LinkedHashMap<String, String>();
            for (var edge : expanded.edges()) {
                if (!members.contains(edge.getSourceNo()) && members.contains(edge.getTargetNo()))
                    mergeShape(input, analysis.nodes().get(edge.getTargetNo()).inputFields());
                if (members.contains(edge.getSourceNo()) && !members.contains(edge.getTargetNo()))
                    mergeShape(output, analysis.nodes().get(edge.getSourceNo()).outputFields());
            }
            shapes.put(call.getNo(), new cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.NodeFields(
                    Collections.unmodifiableMap(input), Collections.unmodifiableMap(output)));
        }
        return new cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Analysis(analysis.sql(), Collections.unmodifiableMap(shapes));
    }

    /** 多端口同名字段只有类型一致时可展示确定类型。 */
    private static void mergeShape(Map<String, String> target, Map<String, String> source) {
        source.forEach((name, type) -> {
            if (!target.containsKey(name)) target.put(name, type);
            else if (!Objects.equals(target.get(name), type)) target.put(name, null);
        });
    }

    private record NativeReference(Long databaseId, String tableName) { }
    private record RocketSource(String nameServers, String topic, String group) { }
    private record NativePreparation(List<EtlNodeDef> nodes,
            Map<NativeReference, cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Table> tables,
            String initializationSql) { }

    /** 外部连接器仅在参数完整校验后降为会话表，原始节点不能引用内部别名绕过资源授权。 */
    private NativePreparation prepareNative(IntegrationTaskDO task, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        if ("DATAX".equals(task.getEngine())) return new NativePreparation(nodes, Map.of(), "");
        var result = new ArrayList<EtlNodeDef>();
        var tables = new LinkedHashMap<NativeReference, cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Table>();
        var sql = new StringBuilder();
        var rocketSources = new HashSet<RocketSource>();
        var engine = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.valueOf(task.getEngine());
        boolean streaming = "REALTIME".equals(task.getMode());
        for (var node : nodes) {
            if (Set.of("read-csv", "read-excel", "read-json").contains(node.getToolCode()))
                throw invalid("当前引擎暂不支持上传附件执行");
            var config = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeConfigJson.normalize(JSONUtil.parseObj(node.getNodeJson()));
            if (Set.of("read-db", "insert-db").contains(node.getToolCode())
                    && config.getStr("tableName", "").startsWith("dh_native_"))
                throw invalid("原始表组件不能引用内部连接器会话表");
            if (engine == cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.FLINK && streaming
                    && "insert-db".equals(node.getToolCode()) && config.getInt("dataSyncType", 1) == 2) {
                Long databaseId = connectorDatabaseId(config);
                var plan = nativeUpsertTable(databaseId, config);
                var reference = new NativeReference(databaseId, config.getStr("tableName"));
                var previous = tables.putIfAbsent(reference, plan.table());
                if (previous != null && !previous.equals(plan.table())) throw invalid("同一目标表不能使用冲突的增量目录合同");
                if (previous == null) sql.append(plan.initializationSql());
                result.add(node); continue;
            }
            cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Table table;
            Long databaseId;
            String initialization;
            Map<String, Object> nodeConfig = null;
            List<Map<String, Object>> nativeMappings = List.of();
            if (Set.of("read-kafka", "write-kafka").contains(node.getToolCode())) {
                databaseId = connectorDatabaseId(config); access.requireDatabase(databaseId);
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeConnectorCompiler.kafka(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, properties.getNativeKafkaBindings().get(databaseId));
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if (Set.of("read-rocketmq", "write-rocketmq").contains(node.getToolCode())) {
                databaseId = connectorDatabaseId(config);
                var binding = rocketBindings.require(task, databaseId, node.getToolCode(), config);
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRocketCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, binding, managedSecretReferences(task));
                String addresses = String.join(";", Arrays.stream(binding.nameServers().split(";")).map(value -> value.toLowerCase(Locale.ROOT)).sorted().toList());
                if ("read-rocketmq".equals(node.getToolCode()) && !rocketSources.add(new RocketSource(addresses, config.getStr("topic"), config.getStr("groupId"))))
                    throw invalid("同一任务不能重复使用相同 RocketMQ 地址、主题及消费组创建多个源");
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if (Set.of("read-odps", "write-odps").contains(node.getToolCode())) {
                databaseId = connectorDatabaseId(config);
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeOdpsCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, odpsBindings.require(task, databaseId),
                        properties.getManagedFunctions(), managedSecretReferences(task));
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if (Set.of("read-rabbitmq", "write-rabbitmq").contains(node.getToolCode())) {
                databaseId = connectorDatabaseId(config);
                var binding = rabbitBindings.require(task, databaseId, node.getToolCode(), config);
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRabbitCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, binding, managedSecretReferences(task));
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if (Set.of("read-redis", "write-redis").contains(node.getToolCode())) {
                databaseId = connectorDatabaseId(config);
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeRedisCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, redisBindings.require(task, databaseId),
                        properties.getManagedFunctions(), managedSecretReferences(task));
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if ("read-tdengine".equals(node.getToolCode())) {
                databaseId = connectorDatabaseId(config);
                var binding = tdengineBindings.require(task, databaseId, config);
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeTdengineCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), config, binding, managedSecretReferences(task));
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if (Set.of("read-mongodb", "write-mongodb").contains(node.getToolCode())) {
                databaseId = connectorDatabaseId(config); access.requireDatabase(databaseId);
                var binding = dataxBindings.require(task.getId(), node.getToolCode(), databaseId, config.getStr("resourceBindingId"));
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeMongoCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, binding);
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if (Set.of("read-ftp", "write-ftp").contains(node.getToolCode())) {
                databaseId = config.get("databaseId") == null ? Long.MIN_VALUE : connectorDatabaseId(config);
                if (databaseId != Long.MIN_VALUE) access.requireDatabase(databaseId);
                var binding = dataxBindings.require(task.getId(), node.getToolCode(), databaseId == Long.MIN_VALUE ? null : databaseId, config.getStr("resourceBindingId"));
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeFtpCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, binding, properties.getManagedFunctions(), managedSecretReferences(task));
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if (Set.of("read-hdfs", "write-hdfs").contains(node.getToolCode())) {
                databaseId = config.get("databaseId") == null ? Long.MIN_VALUE : connectorDatabaseId(config);
                if (databaseId != Long.MIN_VALUE) access.requireDatabase(databaseId);
                var binding = dataxBindings.require(task.getId(), node.getToolCode(), databaseId == Long.MIN_VALUE ? null : databaseId, config.getStr("resourceBindingId"));
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeHdfsCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, binding);
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else if (Set.of("read-hive", "write-hive").contains(node.getToolCode())) {
                databaseId = connectorDatabaseId(config);
                var physical = nativeTable(task.getEngine(), databaseId, config.getStr("tableName"), config.getStr("schemaName"));
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeHiveCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, physical);
                table = plan.table(); initialization = plan.initializationSql(); nodeConfig = plan.nodeConfig();
            } else if (Set.of("rest-api", "read-webservice").contains(node.getToolCode())) {
                databaseId = Long.MIN_VALUE;
                var binding = dataxBindings.require(task.getId(), node.getToolCode(), null, config.getStr("resourceBindingId"));
                var plan = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeHttpCompiler.compile(engine, streaming,
                        task.getId() + ":" + node.getNo(), node.getToolCode(), config, binding, properties.getManagedFunctions(), managedSecretReferences(task),
                        cn.iocoder.yudao.framework.etl.core.compile.EtlNativeScriptCompiler.contextKeys(node.getNo(), nodes, edges));
                table = plan.table(); initialization = plan.initializationSql(); nativeMappings = plan.fieldMappings();
            } else { result.add(node); continue; }
            var adapted = cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(node, EtlNodeDef.class);
            boolean output = node.getToolCode().startsWith("write-");
            adapted.setToolCode(output ? "insert-db" : "read-db");
            adapted.setToolType(output ? EtlNodeDef.TOOL_TYPE_OUTPUT : EtlNodeDef.TOOL_TYPE_INPUT);
            var adaptedConfig = nodeConfig == null ? new JSONObject().set("databaseId", databaseId).set("tableName", table.physicalTable()) : new JSONObject(nodeConfig);
            if (output && nodeConfig == null) adaptedConfig.set("fieldMappings", nativeMappings.isEmpty() ? table.columns().keySet().stream()
                    .map(field -> Map.of("source", field, "target", field, "enabled", true)).toList() : nativeMappings);
            adapted.setNodeJson(adaptedConfig.toString()); result.add(adapted);
            var previous = tables.putIfAbsent(new NativeReference(databaseId, table.physicalTable()), table);
            if (previous != null && !previous.equals(table))
                throw invalid("连接器内部表别名冲突");
            sql.append(initialization);
        }
        return new NativePreparation(List.copyOf(result), Map.copyOf(tables), sql.toString());
    }

    private static Long connectorDatabaseId(JSONObject config) {
        String value = String.valueOf(config.get("databaseId"));
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid("连接器数据源编号必须为无损的正整数");
        try { return Long.valueOf(value); } catch (NumberFormatException malformed) { throw invalid("连接器数据源编号超出范围"); }
    }

    private cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Table resolveNative(IntegrationTaskDO task,
            NativePreparation prepared, Long databaseId, String tableName, String schema) {
        var connector = prepared.tables().get(new NativeReference(databaseId, tableName));
        return connector == null ? nativeTable(task.getEngine(), databaseId, tableName, schema) : connector;
    }

    /** 秘密授权同时匹配任务、工作组和引擎，不能借用另一个任务已挂载的引用。 */
    private cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Tables nativeResolver(IntegrationTaskDO task, NativePreparation prepared) {
        var resolver = cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.withManagedFunctions(
                (databaseId, tableName, schema) -> resolveNative(task, prepared, databaseId, tableName, schema), properties.getManagedFunctions());
        return cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.withManagedSecretRefs(resolver, managedSecretReferences(task));
    }

    private Set<String> managedSecretReferences(IntegrationTaskDO task) {
        var references = new LinkedHashSet<String>();
        properties.getManagedSecretBindings().forEach((reference, binding) -> {
            if (reference != null && reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") && binding != null
                    && binding.getAllowedTaskIds() != null && binding.getAllowedTaskIds().contains(task.getId())
                    && binding.getWorkerGroups() != null && binding.getWorkerGroups().contains(task.getWorkerGroup())
                    && binding.getEngines() != null && binding.getEngines().contains(task.getEngine())) references.add(reference);
        });
        return Collections.unmodifiableSet(references);
    }

    /** 复用发布版本解析与授权；后台编译使用持久创建人，不借用任务负责人身份。 */
    private EtlSubflowExpander.Result expandSubflows(IntegrationTaskDO task, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        EtlSubflowExpander.Result result;
        if (nodes.stream().noneMatch(node -> EtlSubflowExpander.CALL.equals(node.getToolCode()))) {
            result = new EtlSubflowExpander.Result(nodes, edges, List.of(), Map.of());
        } else {
            if ("DATAX".equals(task.getEngine())) throw invalid("DataX 不支持包含子流程的行转换图，请选择原生 SQL 引擎");
            Long actor = SecurityFrameworkUtils.getLoginUserId();
            if (actor == null) actor = ExecutionAccessService.parseExecutionUserId(task.getCreator());
            if (actor == null) throw invalid("子流程编译缺少有效执行身份");
            result = subflows.expand(nodes, edges, actor, Map.of());
        }
        // 发布子版本也不能成为集成流程写入凭据或独立脚本的旁路。
        result.nodes().forEach(node -> rejectNodeSecrets(node.getToolCode(), JSONUtil.parseObj(node.getNodeJson())));
        return result;
    }

    private static boolean hasScripts(List<EtlNodeDef> nodes) {
        return nodes.stream().anyMatch(node -> Set.of("java-script", "custom-token").contains(node.getToolCode()));
    }

    private String managedInitialization(IntegrationTaskDO task) {
        return Objects.toString("FLINK".equals(task.getEngine()) ? properties.getFlinkInitializationSql() : properties.getSparkInitializationSql(), "");
    }

    private void validateScriptProgram(IntegrationTaskDO task, cn.iocoder.yudao.framework.etl.core.script.EtlNativeScriptProgram program, EngineConfig config) {
        for (var stage : program.stages()) nativeStep(task, "native_validation", task.getName(), stage.inputSql(), config, List.of());
        for (String insert : program.inserts()) nativeStep(task, "native_validation", task.getName(), insert, config, List.of());
    }

    private Step scriptStep(IntegrationTaskDO task, cn.iocoder.yudao.framework.etl.core.script.EtlNativeScriptProgram program, EngineConfig config) {
        validateScriptProgram(task, program, config);
        String queue = config.getYarnQueue() == null || config.getYarnQueue().isBlank() ? "default" : config.getYarnQueue();
        String name = "dh_integration_" + task.getId() + "_native_script";
        var resource = scripts.launcher(task.getEngine()); NativeTaskParameters.Payload payload;
        if ("FLINK".equals(task.getEngine())) {
            payload = NativeTaskParameters.flinkManagedProgram(new NativeTaskParameters.FlinkOptions(
                    NativeTaskParameters.ProgramType.JAVA,
                    "REALTIME".equals(task.getMode()) ? NativeTaskParameters.FlinkMode.STREAMING : NativeTaskParameters.FlinkMode.BATCH,
                    null, resource, "cn.iocoder.yudao.framework.etl.script.flink.EtlNativeScriptMain", program.argumentChunks(), List.of(),
                    config.getSlots(), config.getTaskManagerCount(), config.getParallelism(), config.getJobManagerMemory(),
                    config.getTaskManagerMemory(), queue, name, "REALTIME".equals(task.getMode()) ? 60000 : 0),
                    NativeTaskParameters.FlinkDeployment.valueOf(properties.getFlinkDeployMode()), properties.getFlinkJobManager());
            if ("REALTIME".equals(task.getMode())) payload = NativeTaskParameters.withFlinkRestartPolicy(payload,
                    properties.getFlinkRestartAttempts(), properties.getFlinkRestartDelayMs());
        } else {
            payload = NativeTaskParameters.spark(new NativeTaskParameters.SparkOptions(NativeTaskParameters.ProgramType.JAVA, null,
                    resource, "cn.iocoder.yudao.framework.etl.script.spark.EtlNativeScriptMain", program.argumentChunks(), List.of(),
                    config.getDriverCores(), config.getDriverMemory(), config.getExecutorCount(), config.getExecutorCores(),
                    config.getExecutorMemory(), queue, name), properties.getSparkMaster());
        }
        return new Step("native_script", task.getName(), payload.taskType(), payload.taskParams(), List.of());
    }

    private Step nativeStep(IntegrationTaskDO task, String id, String label, String sql, EngineConfig config, List<String> upstream) {
        NativeTaskParameters.Payload payload;
        String queue = config.getYarnQueue() == null || config.getYarnQueue().isBlank() ? "default" : config.getYarnQueue();
        String name = "dh_integration_" + task.getId() + "_" + id;
        if ("FLINK".equals(task.getEngine())) {
            payload = NativeTaskParameters.flink(new NativeTaskParameters.FlinkOptions(
                NativeTaskParameters.ProgramType.SQL,
                "REALTIME".equals(task.getMode()) ? NativeTaskParameters.FlinkMode.STREAMING : NativeTaskParameters.FlinkMode.BATCH,
                sql, null, null, List.of(), List.of(), config.getSlots(), config.getTaskManagerCount(),
                config.getParallelism(), config.getJobManagerMemory(), config.getTaskManagerMemory(), queue, name,
                "REALTIME".equals(task.getMode()) ? 60000 : 0), NativeTaskParameters.FlinkDeployment.valueOf(properties.getFlinkDeployMode()), properties.getFlinkJobManager());
            if ("REALTIME".equals(task.getMode())) payload = NativeTaskParameters.withFlinkRestartPolicy(payload,
                    properties.getFlinkRestartAttempts(), properties.getFlinkRestartDelayMs());
        } else {
            payload = NativeTaskParameters.spark(new NativeTaskParameters.SparkOptions(
                NativeTaskParameters.ProgramType.SQL, sql, null, null, List.of(), List.of(),
                config.getDriverCores(), config.getDriverMemory(), config.getExecutorCount(),
                config.getExecutorCores(), config.getExecutorMemory(), queue, name), properties.getSparkMaster());
        }
        payload = NativeTaskParameters.withSqlInitialization(payload, "FLINK".equals(task.getEngine())
                ? properties.getFlinkInitializationSql() : properties.getSparkInitializationSql());
        return new Step(id, label, payload.taskType(), payload.taskParams(), upstream);
    }

    private cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Table nativeTable(String engine, Long databaseId, String tableName, String schema) {
        access.requireDatabase(databaseId);
        var binding = properties.getNativeTableBindings().get(databaseId);
        if (binding == null) throw invalid("数据源 " + databaseId + " 尚未绑定 " + engine + " 受管 Catalog，请先配置引擎中的外部表");
        var namespace = "SPARK".equals(engine) ? binding.getSparkNamespace() : binding.getFlinkNamespace();
        if (namespace == null || namespace.isEmpty() || namespace.size() > 2) throw invalid("受管 Catalog 命名空间无效");
        var connection = connections.resolve(databaseId); var metadata = databases.getTableMeta(databaseId, tableName);
        if (schema != null && !schema.isBlank() && !Objects.equals(schema, metadata.getSchemaName())) throw invalid("节点 Schema 与当前元数据不一致");
        var path = new ArrayList<>(namespace); path.add(binding.getTableAliases().getOrDefault(tableName, tableName));
        var columns = new LinkedHashMap<String, String>();
        metadata.getColumns().forEach(column -> columns.put(column.getFieldName(), column.getFieldTypeName()));
        var table = new cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Table(path, columns,
                connection.getProductType(), metadata.getSchemaName(), metadata.getTableName(),
                "FLINK".equals(engine) && binding.isFlinkContinuousSource()
                        && binding.getFlinkContinuousTables() != null && binding.getFlinkContinuousTables().contains(tableName));
        // 普通表组件与 Hive 专用组件复用同一存储检查，不能通过切换调色板组件绕过事务表保护。
        return connection.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.HIVE
                ? hiveStorage.requireOrdinaryText(connection, table) : table;
    }

    /** 控制面只接受真实非空主键；执行端 LIKE 继承后再次检查 JDBC 服务、物理表、目录 PK 和 NULL 策略。 */
    private cn.iocoder.yudao.framework.etl.core.compile.EtlNativeConnectorCompiler.TablePlan nativeUpsertTable(Long databaseId, JSONObject config) {
        var binding = properties.getNativeTableBindings().get(databaseId);
        if (binding == null || !binding.isFlinkVerifiedJdbcSinkInstalled()) throw invalid("目标尚未安装受管 Flink JDBC 增量输出校验器");
        var table = nativeTable("FLINK", databaseId, config.getStr("tableName"), config.getStr("schemaName"));
        if (table.product() != cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL) throw invalid("实时增量输出当前只支持已验证的 PostgreSQL JDBC 目标");
        var metadata = databases.getTableMeta(databaseId, config.getStr("tableName"));
        var actual = metadata.getColumns().stream().filter(column -> Boolean.TRUE.equals(column.getPk())).toList();
        var declared = binding.getFlinkUpsertPrimaryKeys().get(metadata.getTableName());
        if (actual.isEmpty() || actual.stream().anyMatch(column -> !Boolean.FALSE.equals(column.getNullable())) || declared == null || declared.isEmpty()
                || declared.stream().distinct().count() != declared.size()
                || !new HashSet<>(declared).equals(actual.stream().map(cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO::getFieldName).collect(java.util.stream.Collectors.toSet())))
            throw invalid("真实数据库非空主键与管理员受管 Catalog 主键声明不一致");
        int batch;
        try { batch = new java.math.BigDecimal(String.valueOf(config.getOrDefault("batchSize", 1000))).intValueExact(); }
        catch (RuntimeException failure) { throw invalid("实时增量写入批次必须为整数"); }
        var connection = connections.resolve(databaseId);
        String upsertAlias = binding.getFlinkUpsertTableAliases().get(metadata.getTableName());
        if (upsertAlias != null) {
            var path = new ArrayList<>(binding.getFlinkNamespace()); path.add(upsertAlias);
            table = new cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Table(path, table.columns(), table.product(), table.schema(), table.physicalTable(), false);
        }
        return cn.iocoder.yudao.framework.etl.core.compile.EtlNativeFlinkJdbcSinkSql.wrap(table, connection.getJdbcUrl(), declared, batch);
    }

    private List<Step> datax(IntegrationTaskDO task, List<EtlNodeDef> nodes, Map<String, List<String>> upstream, EngineConfig config) {
        Map<String, EtlNodeDef> byId = new HashMap<>(); nodes.forEach(node -> byId.put(node.getNo(), node));
        var result = new ArrayList<Step>(); var consumed = new HashSet<String>();
        for (var output : nodes) {
            if (!"insert-db".equals(output.getToolCode())) continue;
            var parents = upstream.get(output.getNo());
            if (parents.size() != 1) throw invalid("DataX表输出需要唯一的表输入或去重上游");
            var input = byId.get(parents.getFirst()); JSONObject dedup = null;
            if (input != null && "data-clean".equals(input.getToolCode())) {
                var before = upstream.get(input.getNo());
                if (before.size() != 1 || !consumed.add(input.getNo())) throw invalid("DataX去重必须连接唯一表输入且只连接一个输出");
                dedup = JSONUtil.parseObj(input.getNodeJson()); input = byId.get(before.getFirst());
            }
            if (input == null || !"read-db".equals(input.getToolCode())) throw invalid("DataX去重与输出需要连接表输入");
            if (!upstream.get(input.getNo()).isEmpty() || !consumed.add(input.getNo())) {
                throw invalid("DataX 输入必须独立且只能连接一个输出");
            }
            consumed.add(output.getNo());
            var source = JSONUtil.parseObj(input.getNodeJson()); var target = JSONUtil.parseObj(output.getNodeJson());
            if (!target.getStr("whereCondition", "").isBlank()) throw invalid("表输出不支持 WHERE 条件，请将条件配置在输入或过滤组件");
            Long sourceId = source.getLong("databaseId"), targetId = target.getLong("databaseId");
            access.requireDatabase(sourceId); access.requireDatabase(targetId);
            var sourceBinding = binding(sourceId); var targetBinding = binding(targetId);
            var connection = connections.resolve(sourceId);
            EtlTableReadConfig.requireSupportedExecution(source);
            String where = source.getStr("whereCondition", "");
            var mappings = target.getJSONArray("fieldMappings");
            var selected = source.getJSONArray("selectedColumns");
            String sql; var projectedTypes = new LinkedHashMap<String, String>(); var aliases = new LinkedHashMap<String, String>();
            if (source.getInt("ifSql", 0) == 1) {
                if (dedup != null) throw invalid("DataX去重需要可核验字段合同的表输入，请使用表模式");
                if (!where.isBlank() || mappings != null || selected != null) throw invalid("自定义 SQL 请在查询中完成字段选择、别名映射和条件过滤");
                sql = source.getStr("sql");
            } else {
                var sourceMetadata = databases.getTableMeta(sourceId, source.getStr("tableName")).getColumns();
                var sourceColumns = sourceMetadata.stream()
                        .map(cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO::getFieldName).collect(java.util.stream.Collectors.toSet());
                var sourceTypes = new LinkedHashMap<String, String>(); sourceMetadata.forEach(column -> sourceTypes.put(column.getFieldName(), column.getFieldTypeName()));
                var targetColumns = databases.getTableMeta(targetId, target.getStr("tableName")).getColumns().stream()
                        .map(cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableColumnRespVO::getFieldName).collect(java.util.stream.Collectors.toSet());
                var projections = new ArrayList<String>(); var usedTargets = new HashSet<String>();
                if (mappings != null) {
                    for (Object entry : mappings) {
                        var mapping = JSONUtil.parseObj(entry); if (!mapping.getBool("enabled", true)) continue;
                        String from = mapping.getStr("source"), to = mapping.getStr("target");
                        if (!sourceColumns.contains(from) || !targetColumns.contains(to) || !usedTargets.add(to)
                                || (selected != null && !selected.contains(from))) throw invalid("字段映射包含不存在、未选中或重复的字段");
                        projections.add(connection.getProductType().quoteName(from) + " AS " + connection.getProductType().quoteName(to));
                        projectedTypes.put(to, sourceTypes.get(from)); aliases.putIfAbsent(from, to);
                    }
                    if (projections.isEmpty()) throw invalid("请至少启用一个有效字段映射");
                } else if (selected != null) {
                    for (String field : selected.toList(String.class)) {
                        if (!sourceColumns.contains(field)) throw invalid("选择了不存在的输入字段");
                        projections.add(connection.getProductType().quoteName(field)); projectedTypes.put(field, sourceTypes.get(field)); aliases.put(field, field);
                    }
                    if (projections.isEmpty()) throw invalid("请至少选择一个输入字段");
                }
                if (projections.isEmpty()) { projectedTypes.putAll(sourceTypes); sourceTypes.keySet().forEach(name -> aliases.put(name, name)); }
                sql = "SELECT " + (projections.isEmpty() ? "*" : String.join(", ", projections)) + " FROM "
                        + EtlTableReference.resolve(connection, source.getStr("tableName"), source.getStr("schemaName")).quoted()
                        + (where.isBlank() ? "" : " WHERE " + where);
            }
            sql = RestrictedSelectGuard.parseBound(connection.getProductType(), sql, connection.getSchema()).getNormalizedSql();
            if (dedup != null) sql = dataxDedup(connection, sql, projectedTypes, aliases, dedup);
            var targetConnection = connections.resolve(targetId);
            var targetReference = EtlTableReference.resolve(targetConnection, target.getStr("tableName"), target.getStr("schemaName"));
            int syncType = target.getInt("dataSyncType", 1);
            boolean upsert = syncType == 2;
            if (target.getBool("targetTruncate", false) || !Set.of(1, 2).contains(syncType)
                    || !Set.of("INSERT", "UPSERT").contains(target.getStr("writeMode", "INSERT").toUpperCase(Locale.ROOT))
                    || !upsert && "UPSERT".equalsIgnoreCase(target.getStr("writeMode"))) {
                throw invalid("DataX 仅支持向已有表插入或按唯一键幂等写入，不支持清空、建表或仅更新");
            }
            var options = new DataxTaskParameters.Options(sourceBinding.getDsDatasourceId(),
                    sourceBinding.getDsType(), targetBinding.getDsDatasourceId(), targetBinding.getDsType(), sql, targetReference.quoted(),
                    config.getJvmInitialMemory(), config.getJvmMaximumMemory(), config.getChannel(), config.getByteSpeed(),
                    config.getRecordSpeed(), config.getErrorLimit(), target.getInt("batchSize", 1000));
            Map<String, Object> params;
            if (upsert) {
                if (!properties.getDataxUpsertWorkerGroups().contains(task.getWorkerGroup()))
                    throw invalid("当前工作组尚未安装受管 DATAX_UPSERT 插件");
                if (!"POSTGRESQL".equals(targetBinding.getDsType())) throw invalid("幂等 DataX 当前仅支持 PostgreSQL 目标");
                var keys = target.getJSONArray("upsertKeys");
                if (keys == null) throw invalid("幂等写入必须选择目标表的非空唯一键");
                var pre = cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresUpsertHooks.parse(target.getStr("preSql", ""), targetReference.schema());
                var post = cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresUpsertHooks.parse(target.getStr("postSql", ""), targetReference.schema());
                params = DataxTaskParameters.upsertWithHooks(options, targetReference.schema(), targetReference.table(), keys.toList(String.class), pre, post);
            } else params = DataxTaskParameters.build(options);
            result.add(new Step(output.getNo(), input.getName() + " → " + output.getName(), upsert ? "DATAX_UPSERT" : "DATAX", params, List.of()));
        }
        if (consumed.size() != nodes.size() || result.isEmpty()) throw invalid("DataX仅支持表输入 → 可选去重 → 表输出的完整链路");
        return result;
    }

    /** 去重只做领域配置和字段别名适配，结构化SQL由DB Switch生成。 */
    private static String dataxDedup(EtlConnectionInfo connection, String sql, Map<String, String> types,
                                    Map<String, String> aliases, JSONObject config) {
        var allowed = Set.of("enableDeduplication", "deduplicationStrategy", "keyFields", "ignoreCase", "ignoreCaseFields", "orderBy", "uniqueFields", "executionMode");
        for (String key : config.keySet()) if (!allowed.contains(key)) throw invalid("DataX去重不支持其它清洗参数：" + key);
        if (!config.getBool("enableDeduplication", false) || !"LOCAL".equalsIgnoreCase(config.getStr("executionMode", "LOCAL")))
            throw invalid("DataX仅支持本地去重组件");
        String strategy = config.getStr("deduplicationStrategy", "ALL_FIELDS");
        if (!Set.of("ALL_FIELDS", "KEY_FIELDS").contains(strategy)) throw invalid("DataX去重策略无效");
        var keys = config.getJSONArray("keyFields");
        var namedKeys = keys == null ? List.<String>of() : keys.toList(String.class);
        if ("ALL_FIELDS".equals(strategy) && !namedKeys.isEmpty()) throw invalid("整行去重不能同时指定去重键");
        java.util.function.Function<String, String> mapped = name -> {
            String alias = aliases.get(name); if (alias == null) throw invalid("去重或排序字段未包含在当前输出投影：" + name); return alias;
        };
        var insensitive = config.getJSONArray("ignoreCaseFields");
        Set<String> folded = insensitive != null ? new LinkedHashSet<>(insensitive.toList(String.class).stream().map(mapped).toList())
                : config.getBool("ignoreCase", false) ? types.entrySet().stream().filter(entry -> entry.getValue() != null
                    && ("ALL_FIELDS".equals(strategy) || namedKeys.stream().map(mapped).toList().contains(entry.getKey()))
                    && entry.getValue().toUpperCase(Locale.ROOT).matches("(?:TEXT|STRING|VARCHAR|CHAR|CHARACTER VARYING)(?:\\([0-9]+\\))?"))
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()) : Set.of();
        if (insensitive != null && folded.size() != insensitive.size()) throw invalid("忽略大小写字段不能重复");
        var orders = new ArrayList<cn.iocoder.yudao.framework.dbswitch.core.service.DeduplicatingSelectSql.Order>();
        var configuredOrder = config.getJSONArray("orderBy");
        if (configuredOrder != null) for (Object item : configuredOrder) {
            var rule = JSONUtil.parseObj(item); String direction = rule.getStr("order", "ASC");
            if (!Set.of("ASC", "DESC").contains(direction)) throw invalid("去重排序方向无效");
            orders.add(new cn.iocoder.yudao.framework.dbswitch.core.service.DeduplicatingSelectSql.Order(mapped.apply(rule.getStr("field")), "DESC".equals(direction)));
        }
        var unique = config.getJSONArray("uniqueFields");
        var options = new cn.iocoder.yudao.framework.dbswitch.core.service.DeduplicatingSelectSql.Options("ALL_FIELDS".equals(strategy),
                namedKeys.stream().map(mapped).toList(), folded, orders, unique == null ? List.of() : unique.toList(String.class).stream().map(mapped).toList());
        return cn.iocoder.yudao.framework.dbswitch.core.service.DeduplicatingSelectSql.render(connection.getProductType(), sql, connection.getSchema(), types, options);
    }

    private IntegrationRuntimeProperties.DatabaseBinding binding(Long id) {
        var binding = properties.getDatabaseBindings().get(id);
        if (binding == null || binding.getDsDatasourceId() == null || binding.getDsType() == null) {
            throw invalid("数据源 " + id + " 尚未配置受管 DS 数据源绑定");
        }
        if (binding.getDsDatasourceId() <= 0) throw invalid("DS 数据源编号必须为正整数");
        return binding;
    }

    /** 只识别实际节点 properties.toolCode + nodeJson.code；其它同名字段仍按普通配置扫描。 */
    private static void rejectGraphSecrets(JSONObject graph) {
        var checked = JSONUtil.parseObj(graph.toString());
        var nodes = checked.getJSONArray("nodes");
        for (int index = 0; index < nodes.size(); index++) {
            var node = nodes.getJSONObject(index); var properties = node.getJSONObject("properties");
            if (properties == null || !scriptCode(properties.getStr("toolCode")) || properties.get("nodeJson") == null) continue;
            var config = JSONUtil.parseObj(properties.get("nodeJson"));
            rejectNodeSecrets(properties.getStr("toolCode"), config);
            config.remove("code"); properties.set("nodeJson", config); node.set("properties", properties); nodes.set(index, node);
        }
        rejectSecrets(checked);
    }

    private static boolean scriptCode(String code) { return "java-script".equals(code) || "custom-token".equals(code); }

    private static void rejectNodeSecrets(String toolCode, JSONObject config) {
        if (!scriptCode(toolCode)) { rejectSecrets(config); return; }
        var checked = JSONUtil.parseObj(config.toString()); Object value = checked.remove("code");
        if (value != null) {
            if (!(value instanceof String source) || source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
                throw invalid("脚本源码必须是有界字符串");
            // 派生或接口返回的 token 变量是脚本契约；仅明确的凭据字符串字面量仍不落画布。
            if (source.matches("(?is).*\\b(password|passwd|secret|secretKey|accessKey|accessToken|refreshToken|token)\\b\\s*=\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*;.*"))
                throw invalid("脚本不得保存凭据字符串字面量，请使用受管秘密引用或派生认证上下文");
        }
        rejectSecrets(checked);
    }

    private static void rejectSecrets(Object value) {
        if (value instanceof Map<?, ?> map) map.forEach((key, child) -> {
            if (String.valueOf(key).matches("(?i)(password|passwd|secret|secretKey|accessKey|accessToken|refreshToken|token|jdbcUrl)")) throw invalid("流程不得保存连接凭据");
            rejectSecrets(child);
        });
        else if (value instanceof Iterable<?> array) array.forEach(IntegrationFlowCompiler::rejectSecrets);
        else if (value instanceof String text && (text.matches("(?is).*['\"]?(password|passwd|secret|token)['\"]?\\s*[:=].*")
                )) throw invalid("流程包含凭据或未经绑定的模板参数");
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
