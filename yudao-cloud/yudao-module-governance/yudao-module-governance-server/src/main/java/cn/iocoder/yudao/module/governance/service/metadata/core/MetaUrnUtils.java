package cn.iocoder.yudao.module.governance.service.metadata.core;

import java.util.UUID;

/**
 * 元数据资源 URN 工具
 *
 * 规范：urn:datahub:{env}:{resourceType}:{sourceNamespaceId}:{stableId}
 * - sourceNamespaceId 使用 dataforge 数据源的平台内部编号（不可变）
 * - stableId 使用平台对象编号（雪花/序列），自然键改名不影响 URN
 *
 * 由于对象编号在插入后才产生，插入时先写入 pending 占位 URN，再回填正式 URN
 *
 * @author Li
 */
public final class MetaUrnUtils {

    /** 库对象类型 */
    public static final String TYPE_DATABASE = "database";

    /** 表对象类型 */
    public static final String TYPE_TABLE = "table";

    /** 字段对象类型 */
    public static final String TYPE_COLUMN = "column";

    /** 占位 URN 前缀（插入后回填正式 URN） */
    public static final String PENDING_PREFIX = "pending:";

    /**
     * 限制元数据 URN工具类的直接实例化。
     */
    private MetaUrnUtils() {
    }

    /**
     * 构造元数据 URN的数据库URN。
     */
    public static String buildDbUrn(String env, Long datasourceId, Long dbId) {
        return build(env, TYPE_DATABASE, datasourceId, dbId);
    }

    /**
     * 构造元数据 URN的表URN。
     */
    public static String buildTableUrn(String env, Long datasourceId, Long tableId) {
        return build(env, TYPE_TABLE, datasourceId, tableId);
    }

    /**
     * 构造元数据 URN的字段URN。
     */
    public static String buildColumnUrn(String env, Long datasourceId, Long columnId) {
        return build(env, TYPE_COLUMN, datasourceId, columnId);
    }

    /**
     * 按资源路径片段构造规范化元数据 URN。
     */
    public static String build(String env, String resourceType, Long datasourceId, Long stableId) {
        return String.format("urn:datahub:%s:%s:%s:%s", env, resourceType, datasourceId, stableId);
    }

    /**
     * 生成插入占位 URN（满足唯一约束，插入取得编号后立即回填）
     */
    public static String pending() {
        return PENDING_PREFIX + UUID.randomUUID();
    }

}
