package cn.iocoder.yudao.module.dataforge.dal.dataobject.push;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据推送任务 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_push_task")
@KeySequence("dh_dataforge_push_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataPushTaskDO extends BaseDO {

    public static final int STATUS_UNRELEASED = 0;

    public static final int STATUS_RELEASED = 1;

    @TableId
    private Long id;

    private Long pushConfigId;

    private String name;

    private Long categoryId;

    private Long sourceDatabaseId;

    /**
     * 1-数据表 2-自定义 SQL
     */
    private Integer pushType;

    private String tableName;

    private String sqlText;

    private String objectName;

    /**
     * 1-全量 2-增量
     */
    private Integer pushMode;

    private String incrementColumn;

    /**
     * 首次增量起始水位（不含）；空则从最小扫到当前 MAX
     */
    private String initStartVal;

    private Integer batchSize;

    /**
     * 最近一次重置水位的操作人
     */
    private Long watermarkResetUserId;

    private LocalDateTime watermarkResetTime;

    private String watermarkResetOldEnd;

    /**
     * 1-一次性 2-周期性
     */
    private Integer taskType;

    private String cron;

    /**
     * 0-未发布 1-已发布
     */
    private Integer status;

    private LocalDateTime nextRunTime;

    /** 发布与取消发布的单调代际，拒绝旧扫描。 */
    private Long scheduleGeneration;

    private LocalDateTime releaseTime;

    private Long releaseUserId;

    private String description;

    private Long orgId;
}
