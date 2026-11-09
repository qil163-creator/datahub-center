package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发布配置摘要与数据合同摘要。合同覆盖资源、查询语义、出入参；配置额外覆盖运行限制。
 */
public final class DataApiContract {

    /**
     * 限制数据 API契约工具的实例化，统一通过静态能力使用。
     */
    private DataApiContract() {
    }

    /**
     * 计算契约内容摘要。
     *
     * @param api 本次使用的 API 定义
     * @return 计算后的摘要或签名字符串
     */
    public static String contractHash(DataApiDO api) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("configType", StrUtil.nullToEmpty(api.getConfigType()));
        material.put("apiType", StrUtil.nullToEmpty(api.getApiType()));
        material.put("databaseId", api.getDatabaseId());
        material.put("tableName", StrUtil.nullToEmpty(api.getTableName()));
        material.put("sqlText", StrUtil.nullToEmpty(api.getSqlText()));
        material.put("requestParams", StrUtil.nullToEmpty(api.getRequestParamsJson()));
        material.put("responseFields", StrUtil.nullToEmpty(api.getResponseFieldsJson()));
        material.put("resourceRef", StrUtil.nullToEmpty(api.getResourceRef()));
        if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType())) {
            material.put("etlBatchId", api.getEtlBatchId());
            material.put("etlBatchHash", api.getEtlBatchHash());
            material.put("etlSchemaName", api.getEtlSchemaName());
        }
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(material));
    }

    /**
     * 计算配置摘要，供版本及内容一致性校验使用。
     *
     * @param api 本次使用的 API 定义
     * @return 计算后的摘要或签名字符串
     */
    public static String configHash(DataApiDO api) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("contract", contractHash(api));
        material.put("method", StrUtil.nullToEmpty(api.getMethod()));
        material.put("path", StrUtil.nullToEmpty(api.getPath()));
        material.put("version", StrUtil.nullToEmpty(api.getVersion()));
        material.put("authType", StrUtil.nullToEmpty(api.getAuthType()));
        material.put("resultFormat", StrUtil.nullToEmpty(api.getResultFormat()));
        material.put("limitEnabled", api.getLimitEnabled());
        material.put("limitSeconds", api.getLimitSeconds());
        material.put("limitCount", api.getLimitCount());
        material.put("cacheEnabled", api.getCacheEnabled());
        material.put("cacheSeconds", api.getCacheSeconds());
        material.put("forwardUrl", StrUtil.nullToEmpty(api.getForwardUrl()));
        material.put("forwardMethod", StrUtil.nullToEmpty(api.getForwardMethod()));
        // 新增的运行保护参数只在设置时计入，未使用的接口摘要保持不变（已有试调凭证继续有效）
        if (api.getMaxConcurrency() != null) {
            material.put("maxConcurrency", api.getMaxConcurrency());
        }
        if (api.getTimeoutSeconds() != null) {
            material.put("timeoutSeconds", api.getTimeoutSeconds());
        }
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(material));
    }

    /**
     * 读取数据 API契约快照。
     *
     * @param api 本次使用的 API 定义
     * @return 处理后的键值映射
     */
    public static Map<String, Object> snapshot(DataApiDO api) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", api.getName());
        snap.put("version", api.getVersion());
        snap.put("path", api.getPath());
        snap.put("method", api.getMethod());
        snap.put("resultFormat", api.getResultFormat());
        snap.put("apiType", api.getApiType());
        snap.put("authType", api.getAuthType());
        snap.put("configType", api.getConfigType());
        snap.put("databaseId", api.getDatabaseId());
        snap.put("tableName", api.getTableName());
        if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType())) {
            snap.put("etlBatchId", api.getEtlBatchId());
            snap.put("etlBatchHash", api.getEtlBatchHash());
            snap.put("etlSchemaName", api.getEtlSchemaName());
        }
        snap.put("sqlText", api.getSqlText());
        snap.put("forwardUrl", api.getForwardUrl());
        snap.put("forwardMethod", api.getForwardMethod());
        snap.put("requestParamsJson", api.getRequestParamsJson());
        snap.put("responseFieldsJson", api.getResponseFieldsJson());
        snap.put("ipWhitelist", api.getIpWhitelist());
        snap.put("ipRuleType", api.getIpRuleType());
        snap.put("limitEnabled", api.getLimitEnabled());
        snap.put("limitSeconds", api.getLimitSeconds());
        snap.put("limitCount", api.getLimitCount());
        snap.put("cacheEnabled", api.getCacheEnabled());
        snap.put("cacheSeconds", api.getCacheSeconds());
        snap.put("breakerEnabled", api.getBreakerEnabled());
        snap.put("breakerErrorCount", api.getBreakerErrorCount());
        snap.put("breakerWindowSeconds", api.getBreakerWindowSeconds());
        snap.put("breakerOpenSeconds", api.getBreakerOpenSeconds());
        snap.put("maxConcurrency", api.getMaxConcurrency());
        snap.put("timeoutSeconds", api.getTimeoutSeconds());
        snap.put("resourceRef", api.getResourceRef());
        return snap;
    }

    /**
     * 应用快照。
     *
     * @param target 目标数据
     * @param snap 快照
     */
    public static void applySnapshot(DataApiDO target, Map<String, Object> snap) {
        if (snap == null) {
            return;
        }
        target.setName(str(snap.get("name")));
        target.setVersion(str(snap.get("version")));
        target.setPath(str(snap.get("path")));
        target.setMethod(str(snap.get("method")));
        target.setResultFormat(str(snap.get("resultFormat")));
        target.setApiType(str(snap.get("apiType")));
        target.setAuthType(str(snap.get("authType")));
        target.setConfigType(str(snap.get("configType")));
        target.setDatabaseId(lng(snap.get("databaseId")));
        target.setTableName(str(snap.get("tableName")));
        target.setEtlBatchId(lng(snap.get("etlBatchId")));
        target.setEtlBatchHash(str(snap.get("etlBatchHash")));
        target.setEtlSchemaName(str(snap.get("etlSchemaName")));
        target.setSqlText(str(snap.get("sqlText")));
        target.setForwardUrl(str(snap.get("forwardUrl")));
        target.setForwardMethod(str(snap.get("forwardMethod")));
        target.setRequestParamsJson(str(snap.get("requestParamsJson")));
        target.setResponseFieldsJson(str(snap.get("responseFieldsJson")));
        target.setIpWhitelist(str(snap.get("ipWhitelist")));
        target.setIpRuleType(str(snap.get("ipRuleType")));
        target.setLimitEnabled(intg(snap.get("limitEnabled")));
        target.setLimitSeconds(intg(snap.get("limitSeconds")));
        target.setLimitCount(intg(snap.get("limitCount")));
        target.setCacheEnabled(intg(snap.get("cacheEnabled")));
        target.setCacheSeconds(intg(snap.get("cacheSeconds")));
        target.setBreakerEnabled(intg(snap.get("breakerEnabled")));
        target.setBreakerErrorCount(intg(snap.get("breakerErrorCount")));
        target.setBreakerWindowSeconds(intg(snap.get("breakerWindowSeconds")));
        target.setBreakerOpenSeconds(intg(snap.get("breakerOpenSeconds")));
        target.setMaxConcurrency(intg(snap.get("maxConcurrency")));
        target.setTimeoutSeconds(intg(snap.get("timeoutSeconds")));
        target.setResourceRef(str(snap.get("resourceRef")));
    }

    /**
     * 获取数据 API契约的字符串。
     *
     * @param value 待处理的值
     * @return 数据 API契约的字符串文本
     */
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 获取数据 API契约的长整数。
     *
     * @param value 待处理的值
     * @return 长整数数值
     */
    private static Long lng(Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return null;
        }
        return Long.valueOf(String.valueOf(value));
    }

    /**
     * 获取数据 API契约的整数。
     *
     * @param value 待处理的值
     * @return 整数数值
     */
    private static Integer intg(Object value) {
        if (value == null || StrUtil.isBlank(String.valueOf(value))) {
            return null;
        }
        return Integer.valueOf(String.valueOf(value));
    }
}
