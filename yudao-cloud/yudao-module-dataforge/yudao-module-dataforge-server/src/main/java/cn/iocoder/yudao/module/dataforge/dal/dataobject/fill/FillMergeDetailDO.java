package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 在线填报-合并明细 DO（源行→目标记录血缘；目标表无法加系统列时以本表为旁路血缘）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_merge_detail")
@KeySequence("dh_dataforge_fill_merge_detail_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillMergeDetailDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 合并批次编号
     */
    private Long mergeBatchId;

    /**
     * 提交快照编号
     */
    private Long submissionId;

    /**
     * 来源行标识
     */
    private String sourceRowId;

    /**
     * 目标业务键值串（UPSERT 业务键 / REPLACE_SCOPE 范围键）
     */
    private String targetKey;

    /**
     * 写入动作：INSERT/UPDATE/REPLACE
     */
    private String action;

}
