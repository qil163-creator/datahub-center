package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

/**
 * 在线填报-草稿行 DO（多人共享；行删除走逻辑删除保留审计；rowId 永不复用）
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_fill_draft_row", autoResultMap = true)
@KeySequence("dh_dataforge_fill_draft_row_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillDraftRowDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 稳定行标识（服务端签发；排序/插删不改变行业务身份，来源追踪使用它）
     */
    private String rowId;

    /**
     * 显示排序（小数间隙法，插行不重排既有行）
     */
    private BigDecimal rowOrder;

    /**
     * 行数据 JSON：fieldCode -> 值；字段白名单由模板版本控制
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String data;

    /**
     * 行级乐观版本（同一单元格并发的最终防线）
     */
    private Long rowVersion;

    /**
     * 行创建人编号
     */
    private Long createUserId;

    /**
     * 行最后修改人编号
     */
    private Long lastModifyUserId;

}
