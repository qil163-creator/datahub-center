package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import cn.iocoder.yudao.framework.etl.core.executor.builtin.InsertDbExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.builtin.ReadDbExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.builtin.SqlScriptExecutor;
import cn.iocoder.yudao.module.dataforge.enums.access.AccessEngineTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * DataForge 运行快照的平台数据源引用。资源身份在受理时冻结，领取旧记录时可按同一快照补齐；
 * 这里只识别域内的连接引用，不解析 SQL 或读取连接凭据。
 */
public final class RunResourceScope {

    /**
     * 限制运行资源范围工具的实例化，统一通过静态能力使用。
     */
    private RunResourceScope() {
    }

    /**
     * 从数据同步冻结快照提取源端与目标端数据源编号，校验引擎所需资源后输出去重 JSON。
     *
     * @param snapshotJson 快照JSON
     * @return 从数据同步冻结快照提取源端与目标端数据源编号，校验引擎所需资源后输出去重 JSON文本
     */
    public static String access(String snapshotJson) {
        JSONObject snapshot = object(snapshotJson);
        TreeSet<Long> ids = new TreeSet<>();
        Long source = snapshot.getLong("sourceDatabaseId");
        Long target = snapshot.getLong("targetDatabaseId");
        Integer engine = snapshot.getInt("engineType");
        if ((AccessEngineTypeEnum.DBSWITCH.getType().equals(engine) && source == null)
                || ((AccessEngineTypeEnum.DBSWITCH.getType().equals(engine)
                || AccessEngineTypeEnum.API_ACCESS.getType().equals(engine)) && target == null)) {
            throw new IllegalArgumentException("Managed data source identity is required by this access engine");
        }
        add(ids, source);
        add(ids, target);
        return JSONUtil.toJsonStr(ids);
    }

    /**
     * 从 ETL 快照及参数默认值解析节点的数据源编号，校验后输出去重的资源范围 JSON。
     *
     * @param snapshotJson 快照JSON
     * @return 去重且有序的数据源编号 JSON 数组
     */
    public static String etl(String snapshotJson) {
        JSONObject snapshot = object(snapshotJson);
        TreeSet<Long> ids = new TreeSet<>();
        Map<String, String> defaults = new HashMap<>();
        JSONArray parameters = snapshot.getJSONArray("parameters");
        if (parameters != null) {
            for (Object item : parameters) {
                if (!(item instanceof JSONObject parameter)) {
                    throw new IllegalArgumentException("Invalid ETL parameter");
                }
                String name = parameter.getStr("name");
                if (name != null && !name.isBlank()) {
                    defaults.put(name, parameter.getStr("defaultValue", ""));
                }
            }
        }
        EtlVariableContext variables = new EtlVariableContext(defaults);
        JSONArray nodes = snapshot.getJSONArray("nodes");
        if (nodes != null) {
            List<Map<String, Object>> graphNodes = new ArrayList<>(nodes.size());
            for (Object item : nodes) {
                if (!(item instanceof JSONObject node)) {
                    throw new IllegalArgumentException("Invalid ETL node");
                }
                graphNodes.add(node);
            }
            for (var node : EtlGraphConverter.toNodeDefs(graphNodes)) {
                Object rawId = JSONUtil.parseObj(node.getNodeJson()).get("databaseId");
                if (rawId instanceof String raw && raw.contains("${sys.")) {
                    throw new IllegalArgumentException("Dynamic system time cannot select a data source");
                }
                JSONObject config = JSONUtil.parseObj(variables.resolve(node.getNodeJson()));
                Long id = config.getLong("databaseId");
                if (id == null && (ReadDbExecutor.TOOL_CODE.equals(node.getToolCode())
                        || InsertDbExecutor.TOOL_CODE.equals(node.getToolCode())
                        || SqlScriptExecutor.TOOL_CODE.equals(node.getToolCode()))) {
                    throw new IllegalArgumentException("Database ETL node has no managed data source identity");
                }
                add(ids, id);
            }
        }
        return JSONUtil.toJsonStr(ids);
    }

    /** 不能将损坏的冻结范围当作空集合；有资源的运行必须继续占用配额。 */
    public static List<Long> decode(String resourceIdsJson) {
        if (resourceIdsJson == null || !JSONUtil.isTypeJSONArray(resourceIdsJson)) {
            throw new IllegalArgumentException("Missing run resource scope");
        }
        JSONArray array = JSONUtil.parseArray(resourceIdsJson);
        TreeSet<Long> ids = new TreeSet<>();
        for (Object item : array) {
            if (!(item instanceof Number number)) {
                throw new IllegalArgumentException("Invalid run resource identity");
            }
            long id;
            try {
                id = new BigDecimal(number.toString()).longValueExact();
            } catch (NumberFormatException | ArithmeticException invalid) {
                throw new IllegalArgumentException("Invalid run resource identity", invalid);
            }
            if (id <= 0 || !ids.add(id)) {
                throw new IllegalArgumentException("Invalid run resource identity");
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 校验并解析运行配置 JSON 对象，拒绝缺失或损坏的冻结快照。
     *
     * @param json JSON 内容
     * @return JSON对象
     */
    private static JSONObject object(String json) {
        if (json == null || !JSONUtil.isTypeJSONObject(json)) {
            throw new IllegalArgumentException("Missing run snapshot");
        }
        return JSONUtil.parseObj(json);
    }

    /**
     * 将非空的合法数据源编号加入去重资源集合。
     *
     * @param ids 记录编号集合
     * @param id 记录编号
     */
    private static void add(TreeSet<Long> ids, Long id) {
        if (id == null) {
            return;
        }
        if (id <= 0) {
            throw new IllegalArgumentException("Invalid platform data source identity");
        }
        ids.add(id);
    }
}
