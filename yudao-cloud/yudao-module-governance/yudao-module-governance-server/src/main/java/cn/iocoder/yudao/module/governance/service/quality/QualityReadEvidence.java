package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 质量执行读取凭证；只记录业务绑定与摘要，不记录 URL、账号、密码或业务行。 */
@Data
public class QualityReadEvidence {

    static final int MAX_ORG_GROUPS = 1024;

    /** v3/v4 均绑定 DB Switch 验证的实际关系；只认识 v1/v2 的旧节点不能降级解释。 */
    static final int RELATION_SCHEMA_VERSION = 3;

    static final int COMPOSITE_SCHEMA_VERSION = 4;

    private Integer schemaVersion;

    private Long databaseId;

    private String configurationRevision;

    private String productType;

    private String databaseSchema;

    private Integer usageType;

    private String readMode;

    private String coverage;

    private String sqlHash;

    /** DB Switch 在实际读取连接上解析的关系指纹；不保存 URL、凭据或服务器明文地址。 */
    private String relationFingerprint;

    /** 部署管理的目标/代际摘要；不保存 pin 的 schema、instanceId、generation 或连接地址。 */
    private String managedSourceTarget;

    private String managedSourceGeneration;

    // 持久化凭证使用精确字符串，避免全局毫秒时间戳截断后早于 PostgreSQL 审计时点。
    private String startedAt;

    private String finishedAt;

    /** 可选、独立版本的机构统计；历史凭证缺失时不能由全表结果推断。 */
    private OrgScope orgScope;

    /** 与基础计数同一条查询的完整键集合；缺省/不完整不能证明逐行覆盖。 */
    private KeyScope keyScope;

    /** 副本读取前后的有界同步事实；仅诊断，不作为到数或验收凭证。 */
    private QualitySyncEvidenceCollector.Scope syncScope;

    /** 同一规则统计/机构/定位读取的只读快照摘要；不是副本到数或跨连接提交凭证。 */
    private ReadSnapshot.Evidence snapshotScope;

    /** 指定运行/批次在该快照的事务可见性诊断；不代替完整到数证明。 */
    private QualitySyncEvidenceCollector.ReadCommits commitScope;

    /** 完整写入批次范围诊断；不改变 UNPROVEN_REPLICA 或工单 PASS 准入。 */
    private QualitySyncEvidenceCollector.ReadRanges commitRangeScope;

    @Data
    public static class KeyScope {

        private Integer schemaVersion;

        private String column;

        /** 复合键的有序字段；与单列 column 互斥，旧凭证缺省仍按原单列协议。 */
        private List<String> columns;

        private String algorithm;

        private String sqlHash;

        private Integer jdbcType;

        private String keyHash;

        private Long keyCount;

        private Long total;

        private Long fail;

        private Boolean complete;

        private String reasonCode;

        /** 非空时绑定某一分组，不能冒充全表或另一个机构的覆盖凭证。 */
        private String groupKeyHash;
    }

    /**
     * 构造质量读取的行键范围证据。
     */
    static KeyScope keyScope(String column, AuditKeySnapshot snapshot) {
        KeyScope scope = new KeyScope();
        scope.setSchemaVersion(1);
        scope.setColumn(column);
        scope.setAlgorithm(snapshot.algorithm());
        scope.setSqlHash(DigestUtil.sha256Hex(snapshot.sql()));
        scope.setJdbcType(snapshot.jdbcType());
        scope.setKeyHash(snapshot.keyHash());
        scope.setKeyCount(snapshot.keyCount());
        scope.setTotal(snapshot.total());
        scope.setFail(snapshot.fail());
        scope.setComplete(snapshot.complete());
        scope.setReasonCode(snapshot.reasonCode());
        return scope;
    }

    /**
     * 构造质量读取的行键范围证据。
     */
    static KeyScope keyScope(QualityKeyDefinition definition, AuditKeySnapshot snapshot) {
        KeyScope scope = keyScope(definition.column(), snapshot);
        scope.setColumns(definition.columns());
        return scope;
    }

    @Data
    public static class OrgScope {

        private Integer schemaVersion;

        /** v2 机构凭证绑定原生分组类型及编码；v1 历史/回退统计不能代替它。 */
        private String groupEncoding;

        private Integer groupJdbcType;

        private String field;

        private String sqlHash;

        private String startedAt;

        private String finishedAt;

        private Boolean complete;

        private String reasonCode;

        /** 主键采集失败时仍可保留普通机构统计，但不能把回退统计当作主键证明。 */
        private String keyCaptureReason;

        private List<OrgCount> groups;
    }

    /** 不保存机构原值，避免将其他机构标识复制进审计凭证。 */
    public record OrgCount(String keyHash, Long total, Long fail, String resultType, KeyScope keyScope) {

        /**
         * 使用给定键摘要、总数、失败初始化质量读取证据。
         */
        public OrgCount(String keyHash, Long total, Long fail, String resultType) {
            this(keyHash, total, fail, resultType, null);
        }
    }

    /**
     * 读取质量查询证据的开始时间。
     */
    LocalDateTime startedTime() {
        return startedAt == null ? null : LocalDateTime.parse(startedAt);
    }

    /**
     * 读取质量查询证据的结束时间。
     */
    LocalDateTime finishedTime() {
        return finishedAt == null ? null : LocalDateTime.parse(finishedAt);
    }

    /**
     * 捕获质量读取证据读取快照及时间范围证据。
     */
    static QualityReadEvidence capture(MasterDatabaseResolver.ConnectionInfo connection,
            QualityCheckObjectDO object, String sql, boolean generated,
            LocalDateTime startedAt, LocalDateTime finishedAt) {
        QualityReadEvidence evidence = new QualityReadEvidence();
        evidence.setSchemaVersion(1);
        evidence.setDatabaseId(connection.getDatabaseId());
        evidence.setConfigurationRevision(connection.getConfigurationRevision());
        evidence.setProductType(connection.getProductType().name());
        var pin = connection.getSourceGenerationPin();
        if (pin != null) {
            if (connection.getProductType() != ProductTypeEnum.MARIADB) {
                throw new IllegalArgumentException("Managed source generation is only verified for MariaDB");
            }
            evidence.setManagedSourceTarget(ConfiguredSourceGenerations.targetFingerprint(
                    connection.getProductType(), connection.getJdbcUrl(), connection.getUsername()));
            evidence.setManagedSourceGeneration(pin.fingerprint());
        }
        evidence.setDatabaseSchema(connection.getSchema());
        evidence.setUsageType(connection.getUsageType());
        // 检查对象声明和数据源所属服务返回的用途必须共同成立，未知值不当作直查源端。
        evidence.setReadMode("THIRD_PARTY".equals(object.getDatasourceType())
                && Integer.valueOf(1).equals(connection.getUsageType()) ? "SOURCE_DIRECT" : "UNPROVEN_REPLICA");
        evidence.setCoverage(generated ? "FULL_PREDICATE" : "CUSTOM_UNPROVEN");
        evidence.setSqlHash(DigestUtil.sha256Hex(sql));
        evidence.setStartedAt(startedAt.toString());
        evidence.setFinishedAt(finishedAt.toString());
        return evidence;
    }
}
