package cn.iocoder.yudao.module.dataforge.dal.dataobject.dev;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据开发-补数据批次明细 DO：批次内一个业务日期及其对应的运行实例。
 */
@TableName("dh_dataforge_dev_backfill_item")
@KeySequence("dh_dataforge_dev_backfill_item_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevBackfillItemDO extends BaseDO {

    public static final String STATUS_PENDING = "PENDING";

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_SUCCESS = "SUCCESS";

    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId
    private Long id;

    /** 批次编号 */
    private Long backfillId;

    /** 任务编号（冗余） */
    private Long taskId;

    /** 执行顺序，从 1 开始 */
    private Integer seq;

    /** 业务日期 */
    private LocalDate businessDate;

    /** 状态：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED */
    private String status;

    /** 运行实例编号 */
    private Long recordId;

    /** 失败原因 */
    private String lastError;

    /** 提交时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

}
