package cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据建模-业务实体版本快照。每次上线在同一事务内生成，写入后只读，只用于查看与比对。
 */
@TableName(value = "dh_modeling_business_entity_version", autoResultMap = true)
@KeySequence("dh_modeling_business_entity_version_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessEntityVersionDO extends BaseDO {

    @TableId
    private Long id;

    /** 业务实体编号 */
    private Long entityId;

    /** 实体内递增的版本号，从 1 开始 */
    private Integer versionNo;

    /** 上线完成时实体的修订号 */
    private Long entityRevision;

    /** 上线时的实体信息与关系快照 JSON */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String snapshot;

    /** 相对上一版本的变化摘要 JSON */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String changeSummary;

    /** 上线操作人用户编号 */
    private Long publishedBy;

    /** 上线时间 */
    private LocalDateTime publishedTime;
}
