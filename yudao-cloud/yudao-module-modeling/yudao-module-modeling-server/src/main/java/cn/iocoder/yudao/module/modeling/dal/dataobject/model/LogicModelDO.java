package cn.iocoder.yudao.module.modeling.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName(value = "dh_modeling_logic_model", autoResultMap = true)
@KeySequence("dh_modeling_logic_model_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogicModelDO extends BaseDO {

    public static final String MODE_FORWARD = "FORWARD";

    public static final String MODE_REVERSE = "REVERSE";

    public static final String STATUS_DRAFT = "DRAFT";

    public static final String STATUS_PUBLISHED = "PUBLISHED";

    public static final String STATUS_DISCARDED = "DISCARDED";

    @TableId
    private Long id;

    /** 业务定义修订，字段/关系/口径写入统一递增。 */
    private Long revision;

    /** 画布布局修订，独立于业务定义。 */
    private Long layoutRevision;

    /** 一行数据代表什么。 */
    private String grain;

    /**
     * 模型名称
     */
    private String name;

    /**
     * 模型编码
     */
    private String code;

    /**
     * 创建方式：FORWARD-正向建模 REVERSE-逆向建模
     */
    private String createMode;


    /**
     * 状态：DRAFT/PUBLISHED/DISCARDED
     */
    private String status;

    /** 当前设计的表类型配置，与当前字段同事务保存。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler.class)
    private String typeConfig;

    /** 当前设计的实际发布事实，编辑后清空。 */
    private Long publishedBy;

    private java.time.LocalDateTime publishedTime;

    /**
     * 描述
     */
    private String description;

    /**
     * 建模风格：RELATIONAL/DIMENSIONAL/WIDE/TIME_SERIES/SPATIAL/EVENT_STREAM
     */
    private String modelStyle;

    /**
     * 数仓分层编码：NONE/ODS/DWD/DIM/DWS/ADS
     *
     * 派生字段，保存时按 {@link #layerId} 对应的分层编码回写，仅供展示与既有引用使用。
     */
    private String warehouseLayer;

    /**
     * 所属数仓分层编号（dh_modeling_dw_layer，仅 LAYER 节点）
     */
    private Long layerId;

    /**
     * 所属主题域编号（dh_modeling_subject_domain，选填）
     */
    private Long subjectDomainId;

    /**
     * 表类型：SOURCE/DETAIL/DIMENSION/SUMMARY/APPLICATION，空则取所属分层的默认表类型
     *
     * 枚举 {@link cn.iocoder.yudao.module.modeling.enums.ModelTableTypeEnum}
     */
    private String tableType;

    /**
     * 分类确认状态：UNCLASSIFIED-待分类 CONFIRMED-已确认
     *
     * 由分层、表类型及数据域的有效性派生，发布时重新核验。
     * 枚举 {@link cn.iocoder.yudao.module.modeling.enums.ModelClassificationStatusEnum}
     */
    private String classificationStatus;

    /**
     * 模型直接归属的数据域
     */
    private Long dataDomainId;

    /**
     * 业务分类编号
     */
    private Long bizCategoryId;

    /** 完整表名 */
    private String tableNameNotation;

    /** 来源系统编号（贴源逻辑表） */
    private Long sourceSystemId;

    /** 来源数据连接编号（贴源逻辑表） */
    private Long datasourceId;

    /** 来源数据库类型（贴源逻辑表） */
    private String dbType;

    /** 来源数据库名（贴源逻辑表） */
    private String dbName;

    /** 来源模式名（贴源逻辑表） */
    private String schemaName;

    /** 关联业务实体编号 */
    private Long entityId;

    /** 维度类型，字典 modeling_logic_obj_type */
    private String objType;

    /** 业务类型，字典 modeling_logic_business_type */
    private String businessType;

    /** 统计时效，字典 modeling_logic_data_timeliness */
    private String dataTimeliness;

    /** 统计粒度（汇总逻辑表，逗号分隔） */
    private String statisticsStr;

    /** 统计粒度实体编号，复用框架无损编号列表处理器。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.framework.mybatis.core.type.LongListTypeHandler.class)
    private java.util.List<Long> grainEntityIds;

    /** 责任人姓名 */
    private String ownerName;

    /** 责任人电话 */
    private String ownerPhone;

    /** 完整表名大小写：1 大写，2 小写 */
    private Integer tableCase;

    /** 标准类型 */
    private String documentType;

    /** 标准登记编号 */
    private Long documentId;

    /** 责任人用户编号 */
    private Long contactUserId;

    /** 责任人联系电话 */
    private String contactNumber;

    /** 是否启用：0 禁用，1 启用。与设计状态 status 分开 */
    private Integer enabled;

    /** 备注 */
    private String remark;

    /**
     * 主题 ER 画布横坐标（空表示未布局）
     */
    private Integer posX;

    /**
     * 主题 ER 画布纵坐标（空表示未布局）
     */
    private Integer posY;

}
