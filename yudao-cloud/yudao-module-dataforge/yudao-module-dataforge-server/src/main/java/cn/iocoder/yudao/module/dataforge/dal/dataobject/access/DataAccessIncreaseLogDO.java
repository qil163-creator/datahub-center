package cn.iocoder.yudao.module.dataforge.dal.dataobject.access;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据同步任务-增量同步日志 DO
 *
 * 记录每张表增量字段的同步区间，作为下一次增量同步的起点
 *
 * @author Li
 */
@TableName("dh_dataforge_access_increase_log")
@KeySequence("dh_dataforge_access_increase_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataAccessIncreaseLogDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 数据同步任务id
     */
    private Long dataAccessId;

    /**
     * 源端数据源id
     */
    private Long sourceDatabaseId;

    /**
     * 目的端数据源id
     */
    private Long targetDatabaseId;

    /**
     * 源端 schema
     */
    private String schemaName;

    /**
     * 表名（SQL 接入时为 SQL 语句）
     */
    private String tableName;

    /**
     * 增量字段
     */
    private String increaseColumn;

    /**
     * 本次增量开始值
     */
    private String startVal;

    /**
     * 本次增量结束值
     */
    private String endVal;

    /**
     * 接入方式：1-入中台库 2-自定义接入
     */
    private Integer accessMode;

}
