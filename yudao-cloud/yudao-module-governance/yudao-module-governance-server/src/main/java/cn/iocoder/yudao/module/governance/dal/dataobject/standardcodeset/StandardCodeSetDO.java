package cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset;

import lombok.*;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.*;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;

/**
 * 数据标准--标准码 DO
 *
 * @author Li
 */
@TableName("dh_governance_standard_code_set")
@KeySequence("dh_governance_standard_code_set_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增。如果是 MySQL 等数据库，可不写。
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardCodeSetDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Integer id;

    /**
     * 字典名称
     */
    private String name;

    /**
     * 字典类型
     */
    private String type;

    /**
     * 状态
     *
     * 枚举 {@link TODO common_status 对应的类}
     */
    private Short status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除时间
     */
    private LocalDateTime deletedTime;


    private Long categoryId;

    private String columnType;

    private Integer dataLength;

    private Integer dataScale;

    private Long documentId;

    private Long personCharge;

    private String description;

    private String standardType;

    private String contactNumber;

    private Long revision;
}
