package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-元数据-库（当前态）DO
 *
 * 自然键：datasource_id + db_name + schema_name；schema_name 保存 DB Switch 的精确关系命名空间；
 * 发布/定版属于表级语义，库级不落发布状态字段
 *
 * @author Li
 */
@TableName(value = "dh_governance_meta_db", autoResultMap = true)
@KeySequence("dh_governance_meta_db_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaDbDO extends BaseDO {

    /** 可信离线导入的展示快照，不接受浏览器维护，不参与授权或连接执行。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler.class,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String displaySnapshot;


    /** 来源：采集 */
    public static final int ORIGIN_COLLECT = 1;

    /** 来源：手工 */
    public static final int ORIGIN_MANUAL = 2;

    /** 来源：导入 */
    public static final int ORIGIN_IMPORT = 3;

    /** 对象状态：正常 */
    public static final int OBJECT_STATUS_ACTIVE = 1;

    /** 对象状态：待确认删除 */
    public static final int OBJECT_STATUS_PENDING_DELETE = 2;

    /** 对象状态：已删除 */
    public static final int OBJECT_STATUS_DELETED = 3;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /** 标签绑定修订号，与标签集合在同一事务中更新。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer tagRevision;

    /**
     * 资源 URN（不可变）：urn:datahub:{env}:database:{datasourceId}:{dbId}
     */
    private String resourceUrn;

    /**
     * 来源：1-采集 2-手工 3-导入
     */
    private Integer origin;

    /**
     * dataforge 数据源编号
     */
    private Long datasourceId;

    /**
     * 来源系统编号
     */
    private Long sourceSystemId;

    /**
     * 来源系统名称（展示快照）
     */
    private String sourceSystemName;

    /**
     * 数据库类型
     */
    private String dbType;

    /**
     * 库名（自然键）
     */
    private String dbName;

    /**
     * DB Switch 表查询命名空间（自然键）：schema 型产品为 schema，catalog 型产品为 catalog。
     * 采集枚举与读表必须使用同一精确值，不猜测连接默认 schema。
     */
    private String schemaName;

    /**
     * 源库注释（采集覆盖）
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
     * 数仓分层：ODS/DWD/DWS/ADS 等
     */
    private String layer;

    /**
     * 表数量（运行指标）
     */
    private Integer tableCount;

    /**
     * 存储大小（字节，估算）
     */
    private Long storageSize;

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
     * 扩展属性 JSON
     */
    @TableField(value = "\"extend\"", typeHandler = JsonbTypeHandler.class)
    private String extend;

    /**
     * 备注
     */
    private String remark;

}
