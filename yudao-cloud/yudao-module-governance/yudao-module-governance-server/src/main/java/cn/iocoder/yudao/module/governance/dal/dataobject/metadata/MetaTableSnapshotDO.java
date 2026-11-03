package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-元数据-表结构快照 DO
 *
 * 仅首次基线与结构变更时写快照（无变化不写）；快照为全结构 jsonb（表属性+全字段+主键/索引/分区），
 * 字段级定版随表快照获得；「回滚到时点」通过快照 + 变更日志重建，不做数据源级全量拷贝
 *
 * @author Li
 */
@TableName(value = "dh_governance_meta_table_snapshot", autoResultMap = true)
@KeySequence("dh_governance_meta_table_snapshot_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaTableSnapshotDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 表元数据编号
     */
    private Long tableId;

    /**
     * 库元数据编号
     */
    private Long dbId;

    /**
     * 结构语义版本（与表当前版本对应，unique(table_id, struct_version)）
     */
    private String structVersion;

    /**
     * 产生快照的采集实例编号（手工/导入/回滚为虚拟实例）
     */
    private Long instanceId;

    /**
     * 快照来源：1-采集 2-手工 3-导入 4-回滚，见 {@link MetaChangeDO} SOURCE_* 常量
     */
    private Integer changeSource;

    /**
     * 全结构快照 JSON：表属性 + 全字段 + 主键/索引/分区
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String snapshot;

    /**
     * 结构哈希（幂等与对比加速）
     */
    private String snapshotHash;

    /**
     * 变更摘要 JSON：{colAdd,colUpdate,colDelete,breaking,notes}
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String changeSummary;

    /**
     * 命名基线引用标记（P2）：0-否 1-是
     */
    private Integer baselineFlag;

    /**
     * 备注
     */
    private String remark;

}
