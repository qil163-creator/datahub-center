package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据治理-元数据-表（当前态）DO
 *
 * 自然键：db_id + table_name；结构语义版本 vX.Y.Z（major=破坏性 minor=兼容新增 patch=描述类）；
 * 发布状态与定版独立：published_version/published_snapshot_id 为定版指针，采集流程不感知定版；
 * 行数/存储量等运行指标只更新不升版本；平台维护属性（custom_comment/business_name/认责等）采集永不覆盖
 *
 * @author Li
 */
@TableName(value = "dh_governance_meta_table", autoResultMap = true)
@KeySequence("dh_governance_meta_table_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaTableDO extends BaseDO {

    /** 可信离线导入的展示快照，不接受浏览器维护，不参与授权或连接执行。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler.class,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String displaySnapshot;


    /** 类型：物理表 */
    public static final String TYPE_TABLE = "TABLE";

    /** 类型：视图 */
    public static final String TYPE_VIEW = "VIEW";

    /** 发布状态：未发布 */
    public static final int PUBLISH_STATUS_NO = 0;

    /** 发布状态：已发布 */
    public static final int PUBLISH_STATUS_YES = 1;

    /** 首个基线版本 */
    public static final String FIRST_VERSION = "v1.0.0";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /** 标签绑定修订号，与标签集合在同一事务中更新。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer tagRevision;

    /** 平台业务属性修订号，采集与运行指标更新不覆盖。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer bizRevision;

    /** 发布/定版状态修订号，禁止采集实体回写覆盖。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer stateRevision;

    /** 最后确认定版时间。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime finalizedTime;

    /** 确认定版操作人。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String finalizedBy;

    /** 选定历史结构与确认时业务信息的独立只读镜像，不修改结构快照。 */
    @TableField(typeHandler = JsonbTypeHandler.class, updateStrategy = FieldStrategy.NEVER)
    private String finalizedSnapshot;

    /**
     * 资源 URN（不可变）：urn:datahub:{env}:table:{datasourceId}:{tableId}
     */
    private String resourceUrn;

    /**
     * 来源：1-采集 2-手工 3-导入
     */
    private Integer origin;

    /**
     * 所属库元数据编号
     */
    private Long dbId;

    /**
     * dataforge 数据源编号（冗余）
     */
    private Long datasourceId;

    /**
     * 表名（自然键）
     */
    private String tableName;

    /**
     * 类型：TABLE/VIEW
     */
    private String tableType;

    /**
     * 源库表注释（采集覆盖）
     */
    private String sourceComment;

    /**
     * 平台注释（平台维护，采集不覆盖）
     */
    private String customComment;

    /**
     * 业务名称（平台维护）
     */
    private String businessName;

    /**
     * 所属分层（可空，默认继承所属库）
     */
    private String layer;

    /**
     * 字段数量
     */
    private Integer columnCount;

    /**
     * 主键字段列表 JSON：["id"]
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String primaryKeys;

    /**
     * 索引信息 JSON：[{name,type,unique,columns[]}]（P1a 预留，方言采集接入后填充）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String indexes;

    /**
     * 分区信息 JSON
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String partitionInfo;

    /**
     * 存储引擎
     */
    private String engine;

    /**
     * 结构语义版本 vX.Y.Z
     */
    private String structVersion;

    /**
     * 行数（估算，运行指标不升版本）
     */
    private Long rowCount;

    /**
     * 存储大小（字节，估算）
     */
    private Long storageSize;

    /**
     * 数据最近更新时间（运行元数据）
     */
    private LocalDateTime dataUpdateTime;

    /**
     * 源表创建时间
     */
    private LocalDateTime tbCreateTime;

    /**
     * 结构变更累计次数（运行元数据）
     */
    private Integer structChangeCount;

    /**
     * 最近结构变更时间
     */
    private LocalDateTime lastStructChangeTime;

    /**
     * 是否主表：0-否 1-是（业务元数据）
     */
    private Integer masterFlag;

    /** 是否临时表：0-否 1-是；平台业务属性，采集不覆盖。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer temporaryFlag;

    /** 平台维护的存储类型，独立于源端采集的存储引擎。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String storageType;

    /**
     * 是否敏感表：0-否 1-是（业务元数据）
     */
    private Integer sensitiveFlag;

    /**
     * 业务定义（平台维护）
     */
    private String businessDefinition;

    /**
     * 主题域编号（modeling 主题域引用）
     */
    private Long subjectDomainId;

    /**
     * 主题域名称（展示快照）
     */
    private String subjectDomainName;

    /**
     * 目录挂接：0-未挂接 1-已挂接（asset 事件回写，本模块只读）
     */
    private Integer catalogLinked;

    /**
     * 目录项 URN（asset 事件回写）
     */
    private String catalogItemUrn;

    /**
     * 技术负责人用户编号
     */
    private Long techLeaderUserId;

    /**
     * 技术负责人电话
     */
    private String techLeaderPhone;

    /**
     * 业务负责人用户编号
     */
    private Long businessLeaderUserId;

    /**
     * 业务负责人电话
     */
    private String businessLeaderPhone;

    /**
     * 责任部门编号
     */
    private Long ownerDeptId;

    /**
     * 对象状态：1-正常 2-待确认删除 3-已删除
     */
    private Integer objectStatus;

    /**
     * 发布状态：0-未发布 1-已发布，与定版指针独立
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer publishStatus;

    /**
     * 定版版本号（独立于发布开关）
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String publishedVersion;

    /**
     * 定版快照编号（保留资产引用契约）
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long publishedSnapshotId;

    /**
     * 发布时间
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime publishTime;

    /**
     * 发布人
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String publisher;

    /**
     * 健康度评分（P2）
     */
    private BigDecimal healthScore;

    /**
     * 健康度维度明细 JSON（P2）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String healthDetail;

    /**
     * 首次发现实例编号
     */
    private Long firstInstanceId;

    /**
     * 最近确认实例编号
     */
    private Long lastInstanceId;

    /**
     * 首次发现时间
     */
    private LocalDateTime firstSeenTime;

    /**
     * 最近确认时间
     */
    private LocalDateTime lastSeenTime;

    /**
     * 扩展属性 JSON（含 renamed_from/redirect_to_id 等）
     */
    @TableField(value = "\"extend\"", typeHandler = JsonbTypeHandler.class)
    private String extend;

    /**
     * 备注
     */
    private String remark;

}
