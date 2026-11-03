package cn.iocoder.yudao.module.dataforge.dal.dataobject.systemsource;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/**
 * 来源系统 DO
 *
 * @author Li
 */
@TableName("dh_foundation_system_source")
@KeySequence("dh_system_source_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemSourceDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 系统名称
     */
    private String name;

    /**
     * 系统图标
     */
    private String icon;

    /**
     * 系统地址
     */
    private String url;

    /**
     * 系统类型
     *
     * 枚举 {@link TODO system_source_type 对应的类}
     */
    private String type;

    /**
     * 负责人ID
     */
    private Long ownerUserId;

    /**
     * 说明
     */
    private String description;

    /**
     * 排序
     */
    @TableField(value = "sort_order", updateStrategy = FieldStrategy.ALWAYS)
    private Integer sortOrder;

    /**
     * 所属部门id
     */
    private Short deptId;

    /**
     * 状态：0停用 1启用
     *
     * 枚举 {@link TODO common_status 对应的类}
     */
    private Short status;

    /**
     * 备注
     */
    private String remark;


}
