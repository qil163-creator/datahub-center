package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-汇总来源 DO：中间单位最近一次汇总纳入的下级提交
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_rollup_source")
@KeySequence("dh_dataforge_fill_rollup_source_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillRollupSourceDO extends BaseDO {

    /** 编号 */
    @TableId
    private Long id;

    /** 汇总单位的组织实例编号 */
    private Long workItemId;

    /** 下级组织实例编号 */
    private Long childWorkItemId;

    /** 汇总时纳入的下级提交编号 */
    private Long childSubmissionId;

    /** 汇总时间 */
    private LocalDateTime rolledAt;

}
