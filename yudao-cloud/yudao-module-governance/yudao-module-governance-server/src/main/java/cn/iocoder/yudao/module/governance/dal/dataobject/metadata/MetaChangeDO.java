package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-元数据-变更明细 DO
 *
 * 字段级属性前后值留痕；变更来源区分采集/手工/导入/回滚（回滚不单设变更类型，明细仍按实际动作记增/改/删）；
 * 本表同时支撑变更查询页、MetadataCollected.v1 事件载荷、变更订阅（P3）与概览变更趋势聚合
 *
 * @author Li
 */
@TableName("dh_governance_meta_change")
@KeySequence("dh_governance_meta_change_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaChangeDO extends BaseDO {

    /** 变更来源：采集 */
    public static final int SOURCE_COLLECT = 1;

    /** 变更来源：手工 */
    public static final int SOURCE_MANUAL = 2;

    /** 变更来源：导入 */
    public static final int SOURCE_IMPORT = 3;

    /** 变更来源：回滚 */
    public static final int SOURCE_ROLLBACK = 4;

    /** 对象类型：库 */
    public static final int OBJECT_TYPE_DB = 1;

    /** 对象类型：表 */
    public static final int OBJECT_TYPE_TABLE = 2;

    /** 对象类型：字段 */
    public static final int OBJECT_TYPE_COLUMN = 3;

    /** 变更类型：新增 */
    public static final int TYPE_ADD = 1;

    /** 变更类型：修改 */
    public static final int TYPE_MODIFY = 2;

    /** 变更类型：删除 */
    public static final int TYPE_DELETE = 3;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 采集实例编号（手工修改为空）
     */
    private Long instanceId;

    /**
     * 变更来源：1-采集 2-手工 3-导入 4-回滚
     */
    private Integer changeSource;

    /**
     * 对象类型：1-库 2-表 3-字段
     */
    private Integer objectType;

    /**
     * 对象编号
     */
    private Long objectId;

    /**
     * 归属表编号（冗余，字段变更按表聚合；库级变更为空）
     */
    private Long tableId;

    /**
     * 归属库编号（冗余）
     */
    private Long dbId;

    /**
     * 变更类型：1-新增 2-修改 3-删除
     */
    private Integer changeType;

    /**
     * 变更属性：COMMENT/DATA_TYPE/LENGTH/NULLABLE/PK 等（新增/删除对象时为空）
     */
    private String property;

    /**
     * 修改前值
     */
    private String beforeValue;

    /**
     * 修改后值
     */
    private String afterValue;

    /**
     * 变更前语义版本
     */
    private String versionFrom;

    /**
     * 变更后语义版本
     */
    private String versionTo;

    /**
     * 操作人（手工变更记录，采集为空）
     */
    private String operator;

    /**
     * 备注
     */
    private String remark;

}
