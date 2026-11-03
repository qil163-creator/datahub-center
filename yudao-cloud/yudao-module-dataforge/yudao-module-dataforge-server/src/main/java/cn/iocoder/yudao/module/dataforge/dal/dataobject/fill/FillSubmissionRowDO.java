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
 * 在线填报-提交快照行 DO（不可变；data 含服务端公式重算的权威值；长期保留）
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_fill_submission_row", autoResultMap = true)
@KeySequence("dh_dataforge_fill_submission_row_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillSubmissionRowDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 提交快照编号
     */
    private Long submissionId;

    /**
     * 稳定行标识（与草稿行一致，来源追踪使用）
     */
    private String rowId;

    /**
     * 显示排序
     */
    private BigDecimal rowOrder;

    /**
     * 行数据 JSON（含服务端计算列权威值）
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String data;

    /**
     * 行创建人编号（多人共同填报的来源审计）
     */
    private Long createUserId;

    /**
     * 行最后修改人编号（多人共同填报的来源审计）
     */
    private Long lastModifyUserId;

}
