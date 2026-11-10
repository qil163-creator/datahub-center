package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO.ApiConfig;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeitem.StandardCodeItemMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** 主数据 REST 推送的范围、映射、标准转换与凭据边界，不承担通用数据库或 HTTP 执行。 */
@Component
public class MasterDistributeApiPolicy {

    @Resource
    private MasterColumnMapper columns;

    @Resource
    private StandardElementMapper elements;

    @Resource
    private StandardCodeSetMapper codeSets;

    @Resource
    private StandardCodeItemMapper codeItems;

    /**
     * 补齐 REST 分发方法、认证、超时、推送和重试配置的默认值。
     */
    static ApiConfig defaults(ApiConfig value) {
        if (value == null) {
            throw new IllegalArgumentException("未配置 REST 接口");
        }
        if (value.getMethod() == null) {
            value.setMethod("POST");
        }
        if (value.getAuthType() == null) {
            value.setAuthType("NONE");
        }
        if (value.getTimeoutSeconds() == null) {
            value.setTimeoutSeconds(30);
        }
        if (value.getPushScope() == null) {
            value.setPushScope("ALL");
        }
        if (value.getPushMode() == null) {
            value.setPushMode("FULL");
        }
        if (value.getRetryEnabled() == null) {
            value.setRetryEnabled(true);
        }
        if (value.getRetryCount() == null) {
            value.setRetryCount(3);
        }
        if (value.getRetryIntervalMinutes() == null) {
            value.setRetryIntervalMinutes(5);
        }
        if (value.getExhaustedAction() == null) {
            value.setExhaustedAction("MANUAL");
        }
        if (value.getEnabledValue() == null) {
            value.setEnabledValue("1");
        }
        return value;
    }

    /**
     * 校验主数据分发API的选项，不满足约束时拒绝当前操作。
     */
    static void validateOptions(ApiConfig api) {
        defaults(api);
        if (!Set.of("POST", "PUT").contains(api.getMethod())) {
            throw new IllegalArgumentException("主数据推送仅支持 POST / PUT，GET 不承载推送请求体");
        }
        if (!Set.of("TOKEN", "NONE").contains(api.getAuthType())) {
            throw new IllegalArgumentException("认证方式无效");
        }
        if ("TOKEN".equals(api.getAuthType()) && (api.getToken() == null || api.getToken().isBlank())) {
            throw new IllegalArgumentException("Token 不能为空");
        }
        if (api.getTimeoutSeconds() < 1 || api.getTimeoutSeconds() > 300) {
            throw new IllegalArgumentException("超时范围为 1-300 秒");
        }
        if (!Set.of("ENABLED", "ALL").contains(api.getPushScope()) || !Set.of("INCREMENTAL", "FULL")
                .contains(api.getPushMode())) {
            throw new IllegalArgumentException("推送范围或模式无效");
        }
        if (api.getRetryCount() < 0 || api.getRetryCount() > 10 || api.getRetryIntervalMinutes() < 1
                || api.getRetryIntervalMinutes() > 1440 || !Set.of("STOP", "MANUAL")
                .contains(api.getExhaustedAction())) {
            throw new IllegalArgumentException("请检查失败重试配置");
        }
        if ("ENABLED".equals(api.getPushScope()) && (api.getStatusField() == null || api.getStatusField().isBlank()
                || api.getEnabledValue().isBlank())) {
            throw new IllegalArgumentException("启用数据范围必须指定状态字段和启用值");
        }
        if (api.getMappings() != null && api.getMappings().size() > 1000) {
            throw new IllegalArgumentException("映射数量不能超过 1000");
        }
    }

    /** 密文由 MyBatis 组件处理，本方法只拆分凭据字段并保留未替换的现有值。 */
    static ApiConfig restoreSecrets(MasterDistributeDO task, ApiConfig api) {
        if (task != null && task.getRequestSecrets() != null) {
            ApiConfig secrets = JsonUtils.parseObject(task.getRequestSecrets(), ApiConfig.class);
            if (api.getToken() == null || api.getToken().isBlank()) {
                api.setToken(secrets.getToken());
            }
            api.setHeaders(mergePairs(api.getHeaders(), secrets.getHeaders()));
            api.setParams(mergePairs(api.getParams(), secrets.getParams()));
        }
        return api;
    }

    /**
     * 按不区分大小写的键名合并键值对，空白新值沿用已有值。
     */
    private static List<MasterDistributeConfigVO.KeyValue> mergePairs(List<MasterDistributeConfigVO.KeyValue> current,
            List<MasterDistributeConfigVO.KeyValue> previous) {
        if (current == null) {
            return previous;
        }
        if (previous == null) {
            return current;
        }
        for (var pair : current) {
            if (pair == null || pair.getKey() == null || pair.getValue() != null && !pair.getValue().isBlank()) {
                continue;
            }
            previous.stream().filter(p -> p.getKey().equalsIgnoreCase(pair.getKey())).findFirst()
                    .ifPresent(p -> pair.setValue(p.getValue()));
        }
        return current;
    }

    /**
     * 将认证令牌和请求密钥单独保存，并清除普通配置中的明文。
     */
    static void separateSecrets(MasterDistributeDO task, ApiConfig api) {
        ApiConfig secrets = new ApiConfig();
        secrets.setToken("TOKEN".equals(api.getAuthType()) ? api.getToken() : null);
        secrets.setHeaders(api.getHeaders());
        secrets.setParams(api.getParams());
        task.setRequestSecrets(JsonUtils.toJsonString(secrets));
        api.setToken(null);
        api.setTokenConfigured(null);
        api.setHeaders(redactPairs(api.getHeaders()));
        api.setParams(redactPairs(api.getParams()));
    }

    /**
     * 隐藏 REST 分发配置中的敏感值，仅保留令牌是否配置等展示信息。
     */
    static void redact(MasterDistributeDO task, ApiConfig api) {
        if (api == null) {
            return;
        }
        defaults(api);
        ApiConfig secrets = task.getRequestSecrets() == null ? null : JsonUtils.parseObject(task.getRequestSecrets(),
                ApiConfig.class);
        api.setTokenConfigured(secrets != null && secrets.getToken() != null && !secrets.getToken().isBlank());
        api.setToken(null);
        api.setHeaders(redactPairs(api.getHeaders()));
        api.setParams(redactPairs(api.getParams()));
    }

    /** 配置说明、凭据和重试调整不能使已确认数据重新发送。映射变化由 payload hash 自然检测。 */
    static String checkpointIdentity(MasterDistributeDO task) {
        if (!Integer.valueOf(2).equals(task.getDistributeType())) {
            return "non-api";
        }
        var config = JsonUtils.parseObject(task.getDistributeJson(), MasterDistributeConfigVO.class);
        if (config == null || config.getApi() == null) {
            return "unconfigured";
        }
        var api = defaults(restoreSecrets(task, config.getApi()));
        return hash(Arrays.asList(task.getMasterModelId(), api.getUrl(), api.getMethod(), api.getParams(),
                api.getPushScope(), api.getStatusField(), api.getEnabledValue()));
    }

    /**
     * 脱敏隐藏键值对。
     */
    private static List<MasterDistributeConfigVO.KeyValue> redactPairs(List<MasterDistributeConfigVO.KeyValue> values) {
        if (values == null) {
            return null;
        }
        return values.stream().map(value -> {
            var result = new MasterDistributeConfigVO.KeyValue();
            result.setKey(value.getKey());
            return result;
        }).toList();
    }

    record Plan(ApiConfig config, List<String> primaryKeys, Map<String, Map<String, String>> dictionaries) {

        /**
         * 判断字段是否属于当前推送计划的允许范围。
         */
        boolean includes(Map<String, Object> row) {
            return !"ENABLED".equals(config.getPushScope())
                    || Objects.equals(config.getEnabledValue(),
                    Objects.toString(row.get(config.getStatusField()), null));
        }

        /**
         * 按推送映射与字典规则转换单行主数据。
         */
        Map<String, Object> map(Map<String, Object> row) {
            if (config.getMappings() == null || config.getMappings().isEmpty()) {
                return new LinkedHashMap<>(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (var mapping : config.getMappings()) {
                if (Boolean.FALSE.equals(mapping.getEnabled())) {
                    continue;
                }
                Object value = row.get(mapping.getSourceField());
                if (value == null) {
                    value = mapping.getDefaultValue();
                }
                if (value != null && "STANDARD_DICT".equals(mapping.getTransform())) {
                    value = dictionaries.get(mapping.getSourceField()).get(value.toString());
                    if (value == null) {
                        throw new IllegalArgumentException("存在未匹配的标准码值");
                    }
                }
                result.put(mapping.getTargetField(), value);
            }
            return result;
        }

        /**
         * 生成主数据分发API规范化标识键。
         */
        String key(Map<String, Object> row) {
            List<Object> key = new ArrayList<>();
            for (String name : primaryKeys) {
                Object value = row.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("推送主键不能为 NULL");
                }
                key.add(value);
            }
            return hash(key);
        }
    }

    /**
     * 核验模型字段、主键与下游映射，构建 REST 数据推送计划。
     */
    Plan plan(Long modelId, ApiConfig api) {
        validateOptions(api);
        List<MasterColumnDO> modelColumns = columns.selectListByModelId(modelId);
        Map<String, MasterColumnDO> byName = new HashMap<>();
        modelColumns.forEach(column -> byName.put(column.getName(), column));
        List<String> keys = modelColumns.stream().filter(column -> Integer.valueOf(1).equals(column.getPk()))
                .map(MasterColumnDO::getName).toList();
        if ("INCREMENTAL".equals(api.getPushMode()) && keys.isEmpty()) {
            throw new IllegalArgumentException("增量推送要求模型具有主键");
        }
        if ("ENABLED".equals(api.getPushScope()) && !byName.containsKey(api.getStatusField())) {
            throw new IllegalArgumentException("状态字段不属于当前模型");
        }
        Map<String, Map<String, String>> dictionaries = new HashMap<>();
        Set<String> targets = new HashSet<>();
        if (api.getMappings() != null) {
            for (var mapping : api.getMappings()) {
                if (mapping == null || !byName.containsKey(mapping.getSourceField()) || mapping.getTargetField() == null
                        || mapping.getTargetField().isBlank() || mapping.getTargetField().length() > 128) {
                    throw new IllegalArgumentException("请检查模型字段和下游字段映射");
                }
                if (mapping.getTransform() == null) {
                    mapping.setTransform("ORIGINAL");
                }
                if (!Set.of("ORIGINAL", "STANDARD_DICT").contains(mapping.getTransform())) {
                    throw new IllegalArgumentException("转换规则无效");
                }
                if (Boolean.FALSE.equals(mapping.getEnabled())) {
                    continue;
                }
                if (!targets.add(mapping.getTargetField())) {
                    throw new IllegalArgumentException("推送的下游字段不能重复");
                }
                if ("STANDARD_DICT".equals(mapping.getTransform())) {
                    var elementId = byName.get(mapping.getSourceField()).getStandardElementId();
                    var element = elementId == null ? null : elements.selectById(elementId);
                    var codeSet = element == null || element.getCodeSetId() == null ? null : codeSets.selectById(
                            element.getCodeSetId());
                    if (element == null || !"PUBLISHED".equals(element.getLifecycleStatus()) || codeSet == null
                            || !Short.valueOf((short) 0).equals(codeSet.getStatus())) {
                        throw new IllegalArgumentException("标准字典转换要求字段关联已发布数据元及启用码表");
                    }
                    Map<String, String> dictionary = new HashMap<>();
                    codeItems.selectEnabledByCodeSetId(codeSet.getId())
                            .forEach(item -> dictionary.put(item.getValue(), item.getLabel()));
                    dictionaries.put(mapping.getSourceField(), Map.copyOf(dictionary));
                }
            }
        }
        if (api.getMappings() != null && !api.getMappings().isEmpty() && targets.isEmpty()) {
            throw new IllegalArgumentException("至少启用一个推送字段");
        }
        return new Plan(api, keys, Map.copyOf(dictionaries));
    }

    /**
     * 计算主数据分发API内容摘要。
     */
    static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtils.toJsonString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }
}
