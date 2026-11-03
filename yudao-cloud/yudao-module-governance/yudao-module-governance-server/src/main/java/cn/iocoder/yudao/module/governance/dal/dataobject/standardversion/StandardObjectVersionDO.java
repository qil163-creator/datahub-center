package cn.iocoder.yudao.module.governance.dal.dataobject.standardversion;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 数据标准--标准文件版本快照 DO
 *
 * <p>手动"生成新版本"时保存的不可变快照，版本号按对象递增；
 * 码表快照包含码表本体与全部码值。</p>
 *
 * @author Li
 */
@TableName(value = "dh_governance_standard_object_version", autoResultMap = true)
@KeySequence("dh_governance_standard_object_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardObjectVersionDO extends BaseDO {

    /** 对象类型：标准文件 */
    public static final String TYPE_DOC = "DOC";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 对象类型：DOC-标准文件
     */
    private String objectType;

    /**
     * 对象编号（标准文件 id / 码表 id）
     */
    private Long objectId;

    /**
     * 版本号，从 1 开始按对象递增
     */
    private Integer version;

    /**
     * 版本快照（JSON）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String snapshot;

    /**
     * 变更说明
     */
    private String changeSummary;

}
