package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo.DataApiSaveReqVO;
import tools.jackson.core.type.TypeReference;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.DATA_API_CONFIG_INVALID;

/**
 * 指标接口配置（INDICATOR 模式）：
 * <pre>
 * {"indicatorIds":[1,2], "dimensions":["uid_region"], "timeGrain":"DAY", "limit":200,
 *  "startParam":"startDate", "endParam":"endDate", "filters":[{"param":"region","fieldUid":"uid_region"}]}
 * </pre>
 * 指标、维度与粒度在发布时固定，调用方只能通过声明的请求参数传入时间范围和维度过滤值，不能改动口径。
 *
 * @param indicatorIds 指标编号（1-10）
 * @param dimensions   维度字段 UID（≤5）
 * @param timeGrain    时间粒度，可空
 * @param limit        行数上限（1-1000，默认 200）
 * @param startParam   开始日期参数名，可空
 * @param endParam     结束日期参数名，可空
 * @param filters      请求参数 → 维度过滤
 */
public record DataApiIndicatorConfig(List<Long> indicatorIds, List<String> dimensions, String timeGrain,
                                     Integer limit, String startParam, String endParam, List<FilterParam> filters) {

    private static final Pattern PARAM = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> GRAINS = Set.of("DAY", "WEEK", "MONTH", "QUARTER", "YEAR");

    /**
     * 请求参数到维度过滤的映射。
     */
    public record FilterParam(String param, String fieldUid) {
    }

    /**
     * 解析并校验；不合法时抛出配置错误。
     */
    public static DataApiIndicatorConfig parse(String json) {
        if (StrUtil.isBlank(json)) {
            throw exception(DATA_API_CONFIG_INVALID, "请选择要发布的指标");
        }
        DataApiIndicatorConfig config;
        try {
            config = JsonUtils.parseObject(json, new TypeReference<DataApiIndicatorConfig>() {
            });
        } catch (RuntimeException invalid) {
            throw exception(DATA_API_CONFIG_INVALID, "指标配置格式不正确");
        }
        if (config == null || config.indicatorIds() == null || config.indicatorIds().isEmpty()
                || config.indicatorIds().size() > 10 || config.indicatorIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw exception(DATA_API_CONFIG_INVALID, "请选择 1-10 个指标");
        }
        if (config.dimensions() != null && (config.dimensions().size() > 5
                || config.dimensions().stream().anyMatch(uid -> StrUtil.isBlank(uid) || uid.length() > 64))) {
            throw exception(DATA_API_CONFIG_INVALID, "维度最多 5 个");
        }
        if (config.timeGrain() != null && !GRAINS.contains(config.timeGrain())) {
            throw exception(DATA_API_CONFIG_INVALID, "时间粒度不合法");
        }
        if (config.limit() != null && (config.limit() < 1 || config.limit() > 1000)) {
            throw exception(DATA_API_CONFIG_INVALID, "行数上限应为 1-1000");
        }
        if ((config.startParam() == null) != (config.endParam() == null)) {
            throw exception(DATA_API_CONFIG_INVALID, "开始与结束日期参数需同时配置");
        }
        Set<String> names = new HashSet<>();
        for (String name : config.paramNames()) {
            if (!PARAM.matcher(name).matches() || !names.add(name)) {
                throw exception(DATA_API_CONFIG_INVALID, "请求参数名不合法或重复：" + name);
            }
        }
        if (config.filters() != null) {
            if (config.filters().size() > 10) {
                throw exception(DATA_API_CONFIG_INVALID, "维度过滤最多 10 个");
            }
            for (FilterParam filter : config.filters()) {
                if (filter == null || StrUtil.isBlank(filter.fieldUid()) || filter.fieldUid().length() > 64) {
                    throw exception(DATA_API_CONFIG_INVALID, "维度过滤必须指定维度字段");
                }
            }
        }
        return config;
    }

    /**
     * 配置引用的全部请求参数名。
     */
    public List<String> paramNames() {
        List<String> names = new java.util.ArrayList<>();
        if (startParam != null) {
            names.add(startParam);
            names.add(endParam);
        }
        if (filters != null) {
            filters.stream().filter(java.util.Objects::nonNull).forEach(filter -> names.add(filter.param()));
        }
        return names;
    }

    /**
     * 引用的参数必须在接口的请求参数中声明，网关才会接收。
     */
    public void requireParamsDeclared(String requestParamsJson) {
        Set<String> declared = new HashSet<>();
        if (StrUtil.isNotBlank(requestParamsJson)) {
            List<DataApiSaveReqVO.ParamItem> items = JsonUtils.parseObject(requestParamsJson, new TypeReference<>() {
            });
            if (items != null) {
                items.forEach(item -> declared.add(item.getName()));
            }
        }
        for (String name : paramNames()) {
            if (!declared.contains(name)) {
                throw exception(DATA_API_CONFIG_INVALID, "指标配置引用的请求参数未声明：" + name);
            }
        }
    }

    /**
     * 取字符串参数；空值视为未传。
     */
    static String text(Map<String, Object> params, String name) {
        if (name == null || params == null) {
            return null;
        }
        Object value = params.get(name);
        return value == null || StrUtil.isBlankIfStr(value) ? null : String.valueOf(value);
    }
}
