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
 * 数据治理-元数据-字段（当前态）DO
 *
 * 连续存续期间按 table_id + column_name 匹配并保持 ID；已确认删除后同名再现生成新 ID，治理属性不跨身份继承；
 * 字段无独立发布状态：表级发布级联字段，字段可见性由敏感标注 + 安全分级控制
 *
 * @author Li
 */
@TableName(value = "dh_governance_meta_column", autoResultMap = true)
@KeySequence("dh_governance_meta_column_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaColumnDO extends BaseDO {

    /** 可信离线导入的展示快照，不接受浏览器维护，不参与授权或连接执行。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler.class,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String displaySnapshot;


    /**
     * 主键
     */
    @TableId
    private Long id;

    /** 标签绑定修订号，与标签集合在同一事务中更新。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer tagRevision;

    /**
     * 资源 URN（不可变）：urn:datahub:{env}:column:{datasourceId}:{columnId}
     */
    private String resourceUrn;

    /**
     * 来源：1-采集 2-手工 3-导入
     */
    private Integer origin;

    /**
     * 所属表元数据编号
     */
    private Long tableId;

    /**
     * 所属库元数据编号（冗余）
     */
    private Long dbId;

    /**
     * dataforge 数据源编号（冗余）
     */
    private Long datasourceId;

    /**
     * 字段名（自然键）
     */
    private String columnName;

    /**
     * 字段顺序
     */
    private Integer ordinalPosition;

    /**
     * 源库字段注释（采集覆盖）
     */
    private String sourceComment;

    /**
     * 平台注释（平台维护，采集不覆盖）
     */
    private String customComment;

    /**
     * 业务名称/中文名称（平台维护，可由标准数据元带入）
     */
    private String businessName;

    /**
     * 数据类型
     */
    private String dataType;

    /**
     * 数据长度
     */
    private Long dataLength;

    /**
     * 数据精度
     */
    private Integer dataPrecision;

    /**
     * 小数位数
     */
    private Integer dataScale;

    /**
     * 是否可空：0-否 1-是
     */
    private Integer nullableFlag;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 是否主键：0-否 1-是
     */
    private Integer pkFlag;

    /**
     * 是否外键：0-否 1-是
     */
    private Integer fkFlag;

    /**
     * 是否唯一：0-否 1-是
     */
    private Integer uniqueFlag;

    /**
     * 是否自增：0-否 1-是
     */
    private Integer autoIncrementFlag;

    /**
     * 业务定义（平台维护）
     */
    private String businessDefinition;

    /**
     * 度量单位（平台维护）
     */
    private String measuringUnit;

    /**
     * 标准数据元编号（standard 包引用）
     */
    private Long standardElementId;

    /**
     * 安全分级编号（safety 包引用）
     */
    private Long safetyLevelId;

    /**
     * 敏感/加密标注：0-否 1-是
     */
    private Integer sensitiveFlag;

    /**
     * 内部修订号（自增，不对外展示）
     */
    private Integer revision;

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
     * 扩展属性 JSON（字符集、排序规则、外键目标等）
     */
    @TableField(value = "\"extend\"", typeHandler = JsonbTypeHandler.class)
    private String extend;

    /**
     * 备注
     */
    private String remark;

}
