package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 主数据模型字段 DO
 *
 * @author Li
 */
@TableName("dh_governance_master_column")
@KeySequence("dh_governance_master_column_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterColumnDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 主数据模型编号
     */
    private Long masterModelId;

    /**
     * 物理字段名
     */
    private String name;

    /**
     * 字段注释
     */
    private String commentStr;

    /**
     * 数据类型（dbswitch 逻辑类型）：1-近似浮点数 2-字符串 3-日期 4-布尔 5-整数 6-精确小数 9-日期时间 10-时间
     *
     * 见 {@link cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData} 的 TYPE_* 常量
     */
    private Integer fieldType;

    /**
     * 字段长度
     */
    private Integer fieldLength;

    /**
     * 小数位数
     */
    private Integer fieldScale;

    /**
     * 是否可为空：0-否 1-是
     */
    private Integer nullable;

    /**
     * 是否主键：0-否 1-是
     */
    private Integer pk;

    /**
     * 字段顺序（建表与页面展示顺序）
     */
    private Integer sort;

    /**
     * 绑定的标准数据元编号
     */
    private Long standardElementId;

    /**
     * 转换为 dbswitch 的列描述，用于生成建表 DDL
     */
    public ColumnDescription toColumnDescription() {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName(name);
        description.setLabelName(name);
        description.setValType(fieldType);
        description.setLength(fieldLength);
        description.setPrecision(fieldScale);
        description.setNullable(nullable == null || nullable == 1);
        description.setRemarks(commentStr);
        description.setPk(pk != null && pk == 1);
        description.setAutoIncrement(false);
        return description;
    }

}
