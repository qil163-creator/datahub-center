package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-合并批次 DO（UNIQUE(submissionId) 为唯一幂等定义；失败重试不重新审批）
 *
 * 按业务记录执行后台任务，不依赖租户上下文。
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_merge_batch")
@KeySequence("dh_dataforge_fill_merge_batch_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillMergeBatchDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 提交快照编号（唯一幂等键）
     */
    private Long submissionId;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 周期任务编号
     */
    private Long taskId;

    /**
     * 合并策略：APPEND/UPSERT/REPLACE_SCOPE
     */
    private String mergeStrategy;

    /**
     * 目标数据源编号
     */
    private Long targetDatabaseId;

    /**
     * 目标物理表名
     */
    private String targetTableName;

    /**
     * 状态：0-待执行 1-执行中 2-成功 3-失败，见 FillMergeBatchStatusEnum
     */
    private Integer status;

    /**
     * 应写入总条数
     */
    private Integer totalCount;

    /**
     * 成功写入条数
     */
    private Integer successCount;

    /**
     * 失败原因
     */
    private String errorMessage;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 下次重试时间
     */
    private LocalDateTime nextRetryTime;

    /**
     * 本次执行开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 本次执行结束时间
     */
    private LocalDateTime finishedAt;

}
