package cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
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
 * 数据标准--标准码值 DO
 *
 * 通过稳定的 {@link #codeSetId} 关联码表，同时保留 {@link #dictType} 兼容旧接口。
 *
 * @author Li
 */
@TableName("dh_governance_standard_code_item")
@KeySequence("dh_governance_standard_code_item_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardCodeItemDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Integer id;

    /**
     * 码值标签
     */
    private String label;

    /**
     * 码值键值
     */
    private String value;

    /**
     * 码值类型，对应标准码表的字典类型
     */
    private String dictType;

    /** 标准码表编号 */
    private Integer codeSetId;

    /** 父码值编号 */
    private Integer parentId;

    /** 树层级，根节点为 0，由服务端维护 */
    private Integer levelNo;

    /** 生效时间（含） */
    private LocalDateTime validFrom;

    /** 失效时间（不含），为空表示长期有效 */
    private LocalDateTime validTo;

    /**
     * 码值排序
     */
    private Integer sort;

    /**
     * 状态
     *
     * 枚举 {@link CommonStatusEnum}
     */
    private Short status;

    /**
     * 备注
     */
    private String remark;

}
