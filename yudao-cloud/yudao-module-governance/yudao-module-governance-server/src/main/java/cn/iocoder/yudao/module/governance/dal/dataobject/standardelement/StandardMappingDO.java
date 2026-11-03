package cn.iocoder.yudao.module.governance.dal.dataobject.standardelement;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 数据标准--落标映射事实 DO
 *
 * <p>标准数据元与元数据字段的落标关系事实表。当前目标类型仅支持 METADATA_COLUMN，
 * 与 {@code dh_governance_meta_column.standard_element_id} 通过同库事务保持一致。</p>
 *
 * @author Li
 */
@TableName("dh_governance_standard_mapping")
@KeySequence("dh_governance_standard_mapping_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardMappingDO extends BaseDO {

    /** 目标类型：元数据字段 */
    public static final String TARGET_TYPE_METADATA_COLUMN = "METADATA_COLUMN";

    /** 目标类型：模型字段（设计态落标，按 fieldUid 识别） */
    public static final String TARGET_TYPE_MODEL_FIELD = "MODEL_FIELD";

    /** 映射状态：已确认 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    /** 映射状态：待确认 */
    public static final String STATUS_PROPOSED = "PROPOSED";

    /** 映射状态：已失效（目标不再引用该标准或目标已删除） */
    public static final String STATUS_STALE = "STALE";

    /** 映射状态：已撤销 */
    public static final String STATUS_REVOKED = "REVOKED";

    /** 来源类型：存量引用自动迁移 */
    public static final String SOURCE_LEGACY_REFERENCE = "LEGACY_REFERENCE";

    /** 来源类型：人工操作 */
    public static final String SOURCE_MANUAL = "MANUAL";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 数据元编号
     */
    private Integer elementId;

    /**
     * 目标类型：METADATA_COLUMN
     */
    private String targetType;

    /**
     * 元数据字段编号
     */
    private Long columnId;

    /**
     * 目标稳定标识（复用 dh_governance_meta_column.resource_urn）
     */
    private String targetUrn;

    /**
     * 目标名称快照，如 t_person.person_name
     */
    private String targetNameSnapshot;

    /**
     * 映射状态：CONFIRMED/PROPOSED/STALE/REVOKED
     */
    private String mappingStatus;

    /**
     * 来源类型：LEGACY_REFERENCE/MANUAL
     */
    private String sourceType;

    /**
     * 确认人
     */
    private String confirmedBy;

    /**
     * 确认时间
     */
    private LocalDateTime confirmedTime;

    /**
     * 偏离/备注说明
     */
    private String deviationReason;

}
