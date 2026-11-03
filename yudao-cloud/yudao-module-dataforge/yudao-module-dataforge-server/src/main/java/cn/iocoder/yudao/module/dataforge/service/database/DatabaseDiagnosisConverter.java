package cn.iocoder.yudao.module.dataforge.service.database;

import cn.hutool.core.util.IdUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseDiagnosisRespVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按本项目的产品诊断方案归并组件原子检查；展示目录不代表产品能力认证。 */
final class DatabaseDiagnosisConverter {

    private static final Set<String> STATUSES = Set.of("PASS", "WARN", "FAIL", "SKIPPED");

    private static final Set<String> CONNECTION_CHECKS = Set.of("CONFIG", "DNS", "TCP", "AUTH", "VALIDATION", "SCHEMA");

    private static final Map<String, String> SOURCE_GROUPS = Map.ofEntries(
            Map.entry("CONFIG", "BASIC"), Map.entry("DNS", "BASIC"), Map.entry("TCP", "BASIC"),
            Map.entry("AUTH", "BASIC"),
            Map.entry("VALIDATION", "TARGET"), Map.entry("SCHEMA", "TARGET"), Map.entry("PRIVILEGES", "TARGET"),
            Map.entry("TABLES", "METADATA"), Map.entry("COLUMNS", "METADATA"), Map.entry("PRIMARY_KEYS", "METADATA"),
            Map.entry("EXTENDED_PROPERTIES", "METADATA"), Map.entry("ZERO_ROW_READ", "READ"),
            Map.entry("TABLE_REFERENCE", "READ"),
            Map.entry("TEMP_CREATE", "WRITE"), Map.entry("INSERT", "WRITE"), Map.entry("BATCH_INSERT", "WRITE"),
            Map.entry("UPDATE", "WRITE"), Map.entry("UPSERT", "WRITE"), Map.entry("MERGE", "WRITE"),
            Map.entry("DELETE", "WRITE"), Map.entry("TRUNCATE", "WRITE"),
            Map.entry("TRANSACTION", "ADVANCED"), Map.entry("ALTER_TEMP", "ADVANCED"),
            Map.entry("RENAME_TEMP", "ADVANCED"), Map.entry("DROP_TEMP", "ADVANCED"),
            Map.entry("CLEANUP", "ADVANCED"), Map.entry("WRITE_CLEANUP", "ADVANCED"), Map.entry("CDC", "ADVANCED"));

    private static final Map<String, String> DISPLAY_CONTENTS = Map.ofEntries(
            Map.entry("CONFIG", "检查连接配置"),
            Map.entry("DNS", "检查数据源 IP 或域名是否可解析"),
            Map.entry("TCP", "检查目标服务端口是否可以建立连接"),
            Map.entry("AUTH", "检查账号、密码、Token 或 AccessKey 等认证信息"),
            Map.entry("SCHEMA", "检查目标数据库、schema 或资源是否存在且可访问"),
            Map.entry("PRIVILEGES", "检查当前账号角色及权限信息"),
            Map.entry("TABLES", "检查库表等基础元数据是否可以读取"),
            Map.entry("COLUMNS", "检查字段结构、字段类型和主键信息是否可以读取"),
            Map.entry("EXTENDED_PROPERTIES", "检查注释等扩展属性是否可以读取"),
            Map.entry("ZERO_ROW_READ", "检查库表数据读取权限"),
            Map.entry("INSERT", "检查 INSERT 写入能力"),
            Map.entry("BATCH_INSERT", "检查批量写入能力"),
            Map.entry("UPDATE", "检查 UPDATE、UPSERT 和 MERGE 能力"),
            Map.entry("DELETE", "检查 TRUNCATE 和 DELETE 能力"),
            Map.entry("TEMP_CREATE", "检查 CREATE 建表能力"),
            Map.entry("ALTER_TEMP", "检查 ALTER、RENAME 和 DROP 能力"),
            Map.entry("CDC", "检查 CDC 相关日志和复制权限"));

    private static final List<DisplayCheck> DISPLAY_CHECKS = List.of(
            definition("BASIC", "CONFIG", "连接配置", "检查数据库产品、驱动配置与 JDBC 连接参数是否满足诊断要求",
                    "CONFIG"),
            definition("BASIC", "DNS", "网络地址解析", "解析能够确定的单一 JDBC 目标主机；路由不明确时不推断网络目标",
                    "DNS"),
            definition("BASIC", "TCP", "端口连通性", "尝试连接 JDBC 目标的一条解析地址及服务端口", "TCP"),
            definition("BASIC", "AUTH", "账号认证",
                    "本次只验证 JDBC 账号、密码和产品固定只读验证；不检测 Token 或 AccessKey", "AUTH", "VALIDATION"),
            definition("TARGET", "SCHEMA", "目标资源访问", "验证目标数据库或 schema 存在且当前会话拥有使用权限",
                    "SCHEMA"),
            definition("TARGET", "PRIVILEGES", "账号角色与权限",
                    "读取当前角色及连接、命名空间权限；读取成功不表示拥有全部权限", "PRIVILEGES"),
            definition("METADATA", "TABLES", "元数据读取", "读取目标命名空间内有界的基本表目录", "TABLES"),
            definition("METADATA", "COLUMNS", "字段结构读取",
                    "读取采样基本表的字段名称、类型及主键结构；未声明主键也如实说明", "COLUMNS", "PRIMARY_KEYS"),
            definition("METADATA", "EXTENDED_PROPERTIES", "扩展属性读取",
                    "读取表与字段注释等已实现的扩展属性；无注释不等于读取失败", "EXTENDED_PROPERTIES"),
            definition("READ", "ZERO_ROW_READ", "库表读取权限", "对采样基本表执行零行查询并关闭只读会话；不返回业务数据",
                    "ZERO_ROW_READ", "CLEANUP", "TABLE_REFERENCE"),
            definition("WRITE", "INSERT", "INSERT写入", "仅在诊断临时表中验证单行插入与事务回滚，不写业务表", "INSERT",
                    "TRANSACTION"),
            definition("WRITE", "BATCH_INSERT", "批量写入", "仅向诊断临时表批量插入测试行并核对结果", "BATCH_INSERT"),
            definition("ADVANCED", "UPDATE", "UPDATE/UPSERT/MERGE",
                    "分别验证诊断临时表的更新、冲突写入和合并；未支持的子操作单独说明", "UPDATE", "UPSERT", "MERGE"),
            definition("ADVANCED", "DELETE", "TRUNCATE/DELETE",
                    "分别验证诊断临时表的清空与删除行，不清空或删除业务表数据", "TRUNCATE", "DELETE"),
            definition("ADVANCED", "TEMP_CREATE", "CREATE建表",
                    "只验证独立会话随机临时表的创建，不代表拥有普通业务表建表权限", "TEMP_CREATE"),
            definition("ADVANCED", "ALTER_TEMP", "ALTER/RENAME/DROP",
                    "只验证诊断临时表的结构变更、重命名、删除与会话清理", "ALTER_TEMP", "RENAME_TEMP", "DROP_TEMP",
                    "WRITE_CLEANUP"),
            definition("ADVANCED", "CDC", "CDC日志与复制权限",
                    "仅检查已实现的日志配置及复制角色前置条件，不创建复制槽、不执行实际 CDC", "CDC"));

    /**
     * 限制数据源诊断工具的实例化，统一通过静态能力使用。
     */
    private DatabaseDiagnosisConverter() {
    }

    /** 原生协议与仅测连产品按实际探针展示，不要求伪造 JDBC 命名空间或元数据检查。 */
    static DatabaseDiagnosisRespVO convertConnectionOnly(ConnectionDiagnosticResult result, LocalDateTime checkedAt) {
        return convertConnectionOnly(result, checkedAt, false);
    }

    /** ES 的基础连接还需要逻辑命名空间明确通过；物理索引权限不决定连接在线状态。 */
    static DatabaseDiagnosisRespVO convertConnectionOnly(ConnectionDiagnosticResult result, LocalDateTime checkedAt,
            boolean requireNamespace) {
        List<DatabaseDiagnosisRespVO.Item> items = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        boolean valid = result != null && result.checks() != null && !result.checks().isEmpty();
        if (valid) {
            for (var check : result.checks()) {
                if (check == null || check.key() == null || check.key().isBlank()
                        || !keys.add(check.key()) || check.label() == null || check.label().isBlank()
                        || check.message() == null || check.status() == null || !STATUSES.contains(check.status())) {
                    valid = false;
                    break;
                }
                var item = new DatabaseDiagnosisRespVO.Item();
                item.setCheckCode(check.key());
                item.setCheckName(check.label());
                item.setCheckContent("所选协议的真实连接与目标资源探测");
                item.setStatus(check.status());
                item.setMessage(check.message());
                item.setImpact(impact("BASIC", check.status()));
                item.setReasonCode(check.reasonCode());
                item.setDurationMs(Math.max(0, check.durationMillis()));
                items.add(item);
            }
        }
        var group = new DatabaseDiagnosisRespVO.Group();
        group.setGroupCode("CONNECTION");
        group.setGroupName("连接与目标资源");
        group.setDescription("仅依据组件实际协议探测，不推断元数据、读写或同步能力");
        group.setItems(items);
        if (valid) {
            valid = result.status() != null && result.status()
                    .equals(status(count(group, "PASS"), count(group, "WARN"), count(group, "FAIL")));
        }
        if (!valid) {
            var invalid = new DatabaseDiagnosisRespVO.Item();
            invalid.setCheckCode("PROBE_RESULT");
            invalid.setCheckName("探测结果校验");
            invalid.setCheckContent("校验组件结果完整性");
            invalid.setStatus("SKIPPED");
            invalid.setReasonCode("UNVERIFIED");
            invalid.setMessage("组件未返回完整可验证的连接结果，当前状态未确认");
            invalid.setImpact("连接状态尚未验证");
            group.setItems(new ArrayList<>(List.of(invalid)));
        }
        var unsupported = new DatabaseDiagnosisRespVO.Item();
        unsupported.setCheckCode("CONNECT_ONLY");
        unsupported.setCheckName("其他操作能力");
        unsupported.setCheckContent("本次未单独返回检查证据的其他操作能力");
        unsupported.setStatus("SKIPPED");
        unsupported.setReasonCode("UNVERIFIED");
        unsupported.setMessage("仅认可本次返回的产品探针证据；未单独验证的元数据、读写、DDL 与同步能力保持未确认");
        unsupported.setImpact("未验证的操作能力不能认定为受支持");
        group.getItems().add(unsupported);
        group.setNormalCount(count(group, "PASS"));
        group.setWarningCount(count(group, "WARN"));
        group.setErrorCount(count(group, "FAIL"));
        group.setSkippedCount(count(group, "SKIPPED"));
        group.setTotalCount(group.getItems().size());
        group.setStatus(status(group.getNormalCount(), group.getWarningCount(), group.getErrorCount()));
        var response = new DatabaseDiagnosisRespVO();
        response.setDiagnosisId(IdUtil.fastSimpleUUID());
        response.setCheckedAt(checkedAt);
        response.setDurationMs(result == null ? 0 : Math.max(0, result.durationMillis()));
        boolean protocolPassed = group.getItems().stream().anyMatch(item -> "PASS".equals(item.getStatus())
                && Set.of("connection", "CONNECT", "CONNECTION", "AUTH").contains(item.getCheckCode()));
        boolean requiredProbeFailed = group.getItems().stream().anyMatch(item -> "FAIL".equals(item.getStatus())
                && Set.of("CONFIG", "connection", "CONNECT", "CONNECTION", "AUTH", "VALIDATION", "SCHEMA",
                        "TARGET", "RESOURCE").contains(item.getCheckCode()));
        boolean namespacePassed = !requireNamespace || group.getItems().stream().anyMatch(item ->
                "SCHEMA".equals(item.getCheckCode()) && "PASS".equals(item.getStatus()));
        response.setConnectionSuccessful(valid && result.connectionSuccessful() && protocolPassed && namespacePassed
                && !requiredProbeFailed);
        response.setOverallStatus(group.getStatus());
        response.setGroups(List.of(group));
        response.setNormalCount(group.getNormalCount());
        response.setWarningCount(group.getWarningCount());
        response.setErrorCount(group.getErrorCount());
        response.setSkippedCount(group.getSkippedCount());
        response.setTotalCount(group.getTotalCount());
        return response;
    }

    /**
     * 转换数据源诊断。
     *
     * @param result 处理结果
     * @param checkedAt 已校验时间
     * @return 数据源诊断响应数据
     */
    static DatabaseDiagnosisRespVO convert(ConnectionDiagnosticResult result, LocalDateTime checkedAt) {
        return convertWithIntegrity(result, checkedAt).response();
    }

    /**
     * 转换包含完整性。
     *
     * @param result 处理结果
     * @param checkedAt 已校验时间
     * @return 转换
     */
    static Conversion convertWithIntegrity(ConnectionDiagnosticResult result, LocalDateTime checkedAt) {
        return convertWithIntegrity(result, checkedAt, ProductTypeEnum.POSTGRESQL);
    }

    /**
     * 转换包含完整性。
     *
     * @param result 处理结果
     * @param checkedAt 已校验时间
     * @param product 产品
     * @return 转换
     */
    static Conversion convertWithIntegrity(ConnectionDiagnosticResult result, LocalDateTime checkedAt,
            ProductTypeEnum product) {
        Source source = source(result);
        List<DisplayCheck> definitions = displayChecks(product);
        Map<String, DatabaseDiagnosisRespVO.Group> groups = new LinkedHashMap<>();
        addGroup(groups, "BASIC", "基础连接", "连接参数、网络、端口与认证验证");
        addGroup(groups, "TARGET", "目标资源访问", "目标资源及当前账号角色、权限");
        addGroup(groups, "METADATA", "元数据能力", "表目录、字段类型、主键与扩展属性");
        addGroup(groups, "READ", "读取能力", "采样基本表的零行读取与会话清理");
        addGroup(groups, "WRITE", "写入能力", "明确授权后，在独立临时表中检查写入能力");
        addGroup(groups, "ADVANCED", "高级操作能力", "临时表高级操作和 CDC 前置条件");
        if (product == ProductTypeEnum.MONGODB) {
            groups.get("TARGET").setDescription("目标数据库访问验证");
            groups.get("METADATA").setDescription("集合目录与字段结构；专用探针未实现时明确标记未验证");
        } else if (product == ProductTypeEnum.TDENGINE) {
            groups.get("TARGET").setDescription("目标数据库访问及当前账号权限；各项仅依据实际探针结果");
        }
        for (DisplayCheck definition : definitions) {
            groups.get(definition.group()).getItems().add(displayItem(definition, source));
        }
        preserveUndisplayedProblems(groups.get("BASIC").getItems().getFirst(), definitions, source);
        groups.values().removeIf(group -> group.getItems().isEmpty());
        var response = new DatabaseDiagnosisRespVO();
        response.setDiagnosisId(IdUtil.fastSimpleUUID());
        response.setCheckedAt(checkedAt);
        response.setDurationMs(result == null ? 0 : Math.max(0, result.durationMillis()));
        response.setConnectionSuccessful(source.valid() && result.connectionSuccessful()
                && passed(source, "AUTH") && passed(source, "VALIDATION") && passed(source, "SCHEMA"));
        for (var group : groups.values()) {
            group.setNormalCount(count(group, "PASS"));
            group.setWarningCount(count(group, "WARN"));
            group.setErrorCount(count(group, "FAIL"));
            group.setSkippedCount(count(group, "SKIPPED"));
            group.setTotalCount(group.getItems().size());
            group.setStatus(status(group.getNormalCount(), group.getWarningCount(), group.getErrorCount()));
            response.setNormalCount(response.getNormalCount() + group.getNormalCount());
            response.setWarningCount(response.getWarningCount() + group.getWarningCount());
            response.setErrorCount(response.getErrorCount() + group.getErrorCount());
            response.setSkippedCount(response.getSkippedCount() + group.getSkippedCount());
            response.setTotalCount(response.getTotalCount() + group.getTotalCount());
        }
        response.setGroups(List.copyOf(groups.values()));
        response.setOverallStatus(
                status(response.getNormalCount(), response.getWarningCount(), response.getErrorCount()));
        boolean connectionFailed = source.valid() && CONNECTION_CHECKS.stream()
                .map(source.checks()::get).anyMatch(check -> check != null && "FAIL".equals(check.status()));
        return new Conversion(response, source.valid(), connectionFailed);
    }

    /** 仅选择展示方案，不从通用读写能力枚举推导诊断支持，也不补造组件未返回的结果。 */
    private static List<DisplayCheck> displayChecks(ProductTypeEnum product) {
        if (product == ProductTypeEnum.DORIS || product == ProductTypeEnum.TDENGINE) {
            return DISPLAY_CHECKS.stream().map(DatabaseDiagnosisConverter::databaseNamespaceLabel).toList();
        }
        if (product == ProductTypeEnum.MONGODB) {
            List<DisplayCheck> checks = new ArrayList<>(DISPLAY_CHECKS.subList(0, 4));
            checks.add(new DisplayCheck("TARGET", "SCHEMA", "数据库访问",
                    "检查目标数据库是否存在且可访问",
                    "验证当前会话访问目标数据库的能力；未实现的命名空间探针不会据目录可见性判定通过",
                    List.of("SCHEMA")));
            checks.add(new DisplayCheck("METADATA", "TABLES", "集合元数据读取", "检查集合等基础元数据是否可以读取",
                    "读取组件返回的集合目录；未实现专用读取时标记未验证", List.of("TABLES")));
            checks.add(new DisplayCheck("METADATA", "COLUMNS", "字段结构读取",
                    "检查集合字段结构和字段类型是否可以读取",
                    "仅根据组件字段探针判断，不以 SQL 主键信息代替集合字段验证", List.of("COLUMNS")));
            return List.copyOf(checks);
        }
        if (product == null || product == ProductTypeEnum.UNKNOWN) {
            List<DisplayCheck> checks = new ArrayList<>(DISPLAY_CHECKS);
            checks.set(0, definition("BASIC", "CONFIG", "连接配置",
                    "产品类型未识别，采用通用诊断展示方案；各项是否通过仅依据实际探针结果", "CONFIG"));
            return List.copyOf(checks);
        }
        // Hive 也使用标准 JDBC 展示方案；参考页面某次只显示八项不构成固定产品契约。
        if (product == ProductTypeEnum.HIVE) {
            return DISPLAY_CHECKS.stream().map(DatabaseDiagnosisConverter::databaseNamespaceLabel).toList();
        }
        return DISPLAY_CHECKS;
    }

    /**
     * 获取数据源诊断的数据源命名空间标签。
     *
     * @param check 检查
     * @return 展示检查
     */
    private static DisplayCheck databaseNamespaceLabel(DisplayCheck check) {
        return "SCHEMA".equals(check.key())
                ? new DisplayCheck(check.group(), check.key(), "Database访问", "检查目标数据库是否存在且可访问",
                "验证目标数据库命名空间及当前会话的访问能力", check.sources()) : check;
    }

    /** 缩减展示目录不能吞掉已执行检查中的失败，尤其不能隐藏独立会话清理问题。 */
    private static void preserveUndisplayedProblems(DatabaseDiagnosisRespVO.Item configuration,
            List<DisplayCheck> definitions, Source source) {
        Set<String> displayed = new HashSet<>();
        definitions.forEach(definition -> displayed.addAll(definition.sources()));
        for (var check : source.checks().values()) {
            if (displayed.contains(check.key()) || !("WARN".equals(check.status()) || "FAIL".equals(check.status()))) {
                continue;
            }
            if (!"FAIL".equals(configuration.getStatus())) {
                configuration.setStatus(check.status());
            }
            configuration.setDurationMs(configuration.getDurationMs() + Math.max(0, check.durationMillis()));
            configuration.setMessage(configuration.getMessage() + "；其他诊断检查[" + check.key() + "]："
                    + (check.message() == null ? "未提供说明" : check.message()));
            configuration.setImpact("存在未单列的诊断失败或警告，请查看说明；基础连接状态仍以连接探针为准");
        }
    }

    /** 先验证原子结果自己的摘要；展示聚合产生的 WARN 不能反过来污染该校验。 */
    private static Source source(ConnectionDiagnosticResult result) {
        Map<String, ConnectionDiagnosticResult.Check> indexed = new LinkedHashMap<>();
        if (result == null || result.status() == null || !STATUSES.contains(result.status())
                || result.checks() == null || result.checks().isEmpty()) {
            return new Source(indexed, false);
        }
        boolean valid = true;
        int pass = 0, warning = 0, failure = 0;
        for (var check : result.checks()) {
            if (check == null || check.key() == null || !SOURCE_GROUPS.containsKey(check.key())
                    || !SOURCE_GROUPS.get(check.key()).equals(check.group())
                    || check.status() == null || !STATUSES.contains(check.status())) {
                valid = false;
                continue;
            }
            if (indexed.putIfAbsent(check.key(), check) != null) {
                valid = false;
            }
            if ("PASS".equals(check.status())) {
                pass++;
            }
            if ("WARN".equals(check.status())) {
                warning++;
            }
            if ("FAIL".equals(check.status())) {
                failure++;
            }
        }
        return new Source(indexed, valid && result.status().equals(status(pass, warning, failure)));
    }

    /**
     * 获取数据源诊断的展示项。
     *
     * @param definition 定义
     * @param source 来源数据
     * @return 项
     */
    private static DatabaseDiagnosisRespVO.Item displayItem(DisplayCheck definition, Source source) {
        var item = new DatabaseDiagnosisRespVO.Item();
        item.setCheckCode(definition.key());
        item.setCheckName(definition.name());
        item.setCheckContent(definition.checkContent());
        int pass = 0, warning = 0, failure = 0, skipped = 0;
        List<String> messages = new ArrayList<>();
        Set<String> reasons = new HashSet<>();
        for (String key : definition.sources()) {
            var check = source.checks().get(key);
            // 表引用是新增的可选增强证据；旧组件未返回时不降低既有读取检查评级。
            if (check == null && "TABLE_REFERENCE".equals(key)) {
                continue;
            }
            if (check == null) {
                skipped++;
                reasons.add("UNVERIFIED");
                messages.add(key + "：未返回此项检查证据，未验证");
                continue;
            }
            switch (check.status()) {
                case "PASS" -> pass++;
                case "WARN" -> warning++;
                case "FAIL" -> failure++;
                default -> {
                    skipped++;
                    reasons.add(check.reasonCode() == null ? "UNVERIFIED" : check.reasonCode());
                }
            }
            item.setDurationMs(item.getDurationMs() + Math.max(0, check.durationMillis()));
            String label = check.label() == null ? key : check.label();
            messages.add(label + "：" + (check.message() == null ? "未提供说明" : check.message()));
        }
        // 部分通过而部分未执行只能表示部分验证，不能把复合能力标为全部通过。
        item.setStatus(failure > 0 ? "FAIL" : warning > 0 || pass > 0 && skipped > 0 ? "WARN"
                : pass > 0 ? "PASS" : "SKIPPED");
        if (skipped > 0) {
            item.setReasonCode(reasons.size() == 1 ? reasons.iterator().next() : "UNVERIFIED");
        }
        item.setMessage(definition.content() + "；" + String.join("；", messages));
        if ("CONFIG".equals(definition.key()) && !source.valid()) {
            item.setStatus("FAIL");
            item.setMessage("诊断结果缺失、重复或与摘要矛盾，无法确认连接状态；请重新诊断");
        }
        item.setImpact(impact(definition.group(), item.getStatus()));
        return item;
    }

    /**
     * 获取数据源诊断的定义。
     *
     * @param group 分组
     * @param key 标识键
     * @param name 名称
     * @param content 内容
     * @param sources 来源
     * @return 展示检查
     */
    private static DisplayCheck definition(String group, String key, String name, String content, String... sources) {
        return new DisplayCheck(group, key, name, DISPLAY_CONTENTS.get(key), content, List.of(sources));
    }

    /**
     * 添加分组。
     *
     * @param groups 分组
     * @param code 编码
     * @param name 名称
     * @param description 描述
     */
    private static void addGroup(Map<String, DatabaseDiagnosisRespVO.Group> groups,
            String code, String name, String description) {
        var group = new DatabaseDiagnosisRespVO.Group();
        group.setGroupCode(code);
        group.setGroupName(name);
        group.setDescription(description);
        group.setItems(new ArrayList<>());
        groups.put(code, group);
    }

    /**
     * 判断通过条件是否成立。
     *
     * @param source 来源数据
     * @param key 标识键
     * @return 处理是否成功
     */
    private static boolean passed(Source source, String key) {
        var check = source.checks().get(key);
        return check != null && "PASS".equals(check.status());
    }

    /**
     * 统计数据源诊断。
     *
     * @param group 分组
     * @param status 目标状态
     * @return 符合条件或受影响的记录数量
     */
    private static int count(DatabaseDiagnosisRespVO.Group group, String status) {
        return (int) group.getItems().stream().filter(item -> status.equals(item.getStatus())).count();
    }

    /**
     * 读取数据源诊断状态。
     *
     * @param normal 普通
     * @param warning 警告
     * @param error 失败原因
     * @return 数据源诊断状态文本
     */
    private static String status(int normal, int warning, int error) {
        return error > 0 ? "FAIL" : warning > 0 ? "WARN" : normal > 0 ? "PASS" : "SKIPPED";
    }

    /**
     * 获取数据源诊断的影响。
     *
     * @param group 分组
     * @param status 目标状态
     * @return 数据源诊断的影响文本
     */
    private static String impact(String group, String status) {
        if ("PASS".equals(status)) {
            return "仅表示本项说明中的实际检查范围通过";
        }
        if ("SKIPPED".equals(status)) {
            return "本项未验证，不能据此认定支持或拥有权限";
        }
        if ("WARN".equals(status)) {
            return "部分检查尚未通过或未验证，请结合各子项说明确认";
        }
        return switch (group) {
            case "BASIC", "TARGET" -> "可能影响连接或目标数据库访问";
            case "METADATA" -> "可能影响元数据采集";
            case "READ" -> "可能影响数据读取或会话清理";
            case "WRITE" -> "临时表写入或回滚检查失败，不代表基础连接离线";
            default -> "对应高级操作或资源清理未通过验证";
        };
    }

    record Conversion(DatabaseDiagnosisRespVO response, boolean validSource, boolean connectionFailed) {

    }

    private record Source(Map<String, ConnectionDiagnosticResult.Check> checks, boolean valid) {

    }

    private record DisplayCheck(String group, String key, String name, String checkContent, String content,
                                List<String> sources) {

    }
}
