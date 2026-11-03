package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.etl.core.engine.EtlSqlScriptBinding;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionResolver;
import cn.iocoder.yudao.framework.etl.core.connection.EtlTableReference;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** ETL 操作权限与连接绑定；SQL 解析和标识符复用 DB Switch。 */
@Service
public class EtlNodeAccessService {

    @Resource
    private ExecutionAccessService executionAccessService;

    @Resource
    private DatabaseQuerySupport queries;

    @Resource
    private EtlConnectionResolverAdapter connectionResolverAdapter;

    @Resource
    private DatabaseApi databaseApi;

    /**
     * 设计期字段查询只允许整表或固定物理关系的受限 SELECT；不执行业务写节点。
     * SQL 形状与标识符复用 DB Switch，参数由调用方在环境解析前绑定。
     */
    public record SchemaGraph(List<EtlNodeDef> nodes, EtlConnectionResolver connections) {

    }

    /**
     * 准备结构。
     *
     * @param nodes 节点
     * @param actor 操作人编号
     * @return 结构图
     */
    public SchemaGraph prepareSchema(List<EtlNodeDef> nodes, Long actor) {
        if (actor == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        List<EtlNodeDef> prepared = new ArrayList<>();
        Map<Long, EtlConnectionInfo> authorizedConnections = new LinkedHashMap<>();
        for (var original : nodes) {
            var node = BeanUtils.toBean(original, EtlNodeDef.class);
            prepared.add(node);
            if (!"read-db".equals(node.getToolCode())) {
                continue;
            }
            var config = JSONUtil.parseObj(StrUtil.blankToDefault(node.getNodeJson(), "{}"));
            Long databaseId = config.getLong("databaseId");
            // 未配置的数据源仍是草稿，不发起连接或元数据查询。
            if (databaseId == null) {
                continue;
            }
            executionAccessService.requireDatabase(databaseId);
            final EtlConnectionInfo connection;
            try {
                connection = authorizedConnections.computeIfAbsent(databaseId, id -> {
                    var resolved = connectionResolverAdapter.resolve(id);
                    return resolved == null ? null : BeanUtils.toBean(resolved, EtlConnectionInfo.class);
                });
                if (connection == null) {
                    throw new IllegalStateException("Missing connection");
                }
            } catch (RuntimeException unavailable) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID, "设计期字段推导的数据源身份不可用，请核对连接配置");
            }
            String sql = config.getStr("sql");
            if (config.getInt("ifSql", 0) != 1) {
                String table = config.getStr("tableName");
                if (StrUtil.isBlank(table)) {
                    continue;
                }
                var reference = EtlTableReference.resolve(connection, table, config.getStr("schemaName"));
                sql = "SELECT * FROM " + reference.quoted();
            }
            if (StrUtil.isBlank(sql)) {
                continue;
            }
            final cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectPlan requested;
            try {
                requested = RestrictedSelectGuard.parseBound(connection.getProductType(), sql, connection.getSchema());
            } catch (RuntimeException unsupported) {
                throw exception(ETL_PIPELINE_GRAPH_INVALID,
                        "设计期 SQL 字段推导仅支持已验证数据库的单表列投影与受限条件，不支持多语句、CTE、JOIN、函数或写语句");
            }
            config.set("ifSql", 1);
            config.set("sql", requested.getNormalizedSql());
            node.setNodeJson(config.toString());
        }
        // 解析器闭包捕获请求本地凭据，不提供描述性 toString。
        // 元数据必须使用已解析物理关系的同一连接标识，
        // 不能在管理员修改主机或模式后重新解析源端。
        return new SchemaGraph(List.copyOf(prepared), authorizedConnections::get);
    }

    /**
     * 创建读取端口。
     *
     * @param actor 操作人编号
     * @return ETL读取策略端口
     */
    public cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort createReadPort(Long actor) {
        if (actor == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        return new cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort() {
            /**
             * 获取ETL节点访问的重写。
             *
             * @param nodeNo 节点序号
             * @param databaseId 数据源编号
             * @param sql SQL
             * @return ETL节点访问的重写文本
             */
            public String rewrite(String nodeNo, Long databaseId, String sql) {
                executionAccessService.requireDatabase(databaseId);
                return queries.normalizeSelect(databaseId, sql);
            }

            /**
             * 按条件筛选ETL节点访问。
             *
             * @param nodeNo 节点序号
             * @param databaseId 数据源编号
             * @param sql SQL
             * @param rows 待处理的行数据
             * @return 按条件筛选ETL节点访问结果集合
             */
            public List<Map<String, Object>> filter(String nodeNo, Long databaseId, String sql,
                    List<Map<String, Object>> rows) {
                return rows;
            }

            /**
             * 按条件筛选ETL节点访问。
             *
             * @param nodeNo 节点序号
             * @param databaseId 数据源编号
             * @param sql SQL
             * @param rows 待处理的行数据
             * @param originalSql 原始SQL
             * @return 按条件筛选ETL节点访问结果集合
             */
            public List<Map<String, Object>> filter(String nodeNo, Long databaseId, String sql,
                    List<Map<String, Object>> rows, String originalSql) {
                return rows;
            }
        };
    }

    /**
     * 校验ETL节点访问所需的访问权限。
     *
     * @param nodes 节点
     * @param actor 操作人编号
     * @param parameters 参数定义或取值
     * @return 处理后的键值映射
     */
    public Map<String, EtlSqlScriptBinding> authorize(List<EtlNodeDef> nodes, Long actor,
            Map<String, String> parameters) {
        if (actor == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        var variables = new EtlVariableContext(parameters == null ? Map.of() : parameters);
        Map<String, EtlSqlScriptBinding> scripts = new LinkedHashMap<>();
        for (var node : nodes) {
            var config = JSONUtil.parseObj(variables.resolveJson(StrUtil.blankToDefault(node.getNodeJson(), "{}")));
            if ("rest-api".equals(node.getToolCode())) {
                var api = config.getJSONObject("apiConfig");
                if (api == null || !api.toBean(
                                cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO.class)
                        .isRequestConfigurationValid()) {
                    throw exception(ETL_PIPELINE_GRAPH_INVALID, "API 接口配置不完整");
                }
            }
            Long databaseId = config.getLong("databaseId");
            if (databaseId == null) {
                continue;
            }
            executionAccessService.requireDatabase(databaseId);
            String code = node.getToolCode();
            boolean script = "sql-script".equals(code);
            boolean sqlMode = script || ("read-db".equals(code) && config.getInt("ifSql", 0) == 1);
            if (sqlMode) {
                String sql = config.getStr("sql");
                if (StrUtil.isBlank(sql)) {
                    throw exception(ETL_PIPELINE_GRAPH_INVALID, "SQL 节点未配置 SQL");
                }
                var connection = connectionResolverAdapter.resolve(databaseId);
                var plan = SqlScriptPlan.parse(connection.getProductType(), sql);
                if (!script && plan.steps().size() != 1) {
                    throw exception(ETL_PIPELINE_GRAPH_INVALID, "非脚本节点只允许一条 SQL");
                }
                if (!script) {
                    queries.normalizeSelect(databaseId, sql);
                }
                if (script) {
                    scripts.put(node.getNo(), new EtlSqlScriptBinding(databaseId, plan));
                }
            } else if (StrUtil.isNotBlank(config.getStr("tableName"))) {
                String reference;
                if ("managed-db-output".equals(code)) {
                    String target = config.getStr("tableName");
                    if (StrUtil.isNotBlank(config.getStr("schemaName"))) {
                        target = EtlTableReference.resolve(
                                        connectionResolverAdapter.resolve(databaseId), target, config.getStr("schemaName"))
                                .quoted();
                    }
                    var table = databaseApi.resolveQueryTable(databaseId, target).getCheckedData();
                    if (table == null || StrUtil.isBlank(table.getSchemaName()) || StrUtil.isBlank(
                            table.getTableReference())) {
                        throw exception(ETL_PIPELINE_GRAPH_INVALID, "目标表物理身份不可用");
                    }
                    reference = table.getTableReference();
                } else {
                    var connection = connectionResolverAdapter.resolve(databaseId);
                    reference = "read-db".equals(code)
                            ? EtlTableReference.resolve(connection, config.getStr("tableName"),
                            config.getStr("schemaName")).quoted()
                            : connection.getProductType()
                            .quoteSchemaTableName(StrUtil.blankToDefault(connection.getSchema(), ""),
                                    config.getStr("tableName"));
                }

            }
        }
        return Map.copyOf(scripts);
    }
}
