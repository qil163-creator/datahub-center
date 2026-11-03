package cn.iocoder.yudao.module.dataforge.dal.mysql.database;

import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseMonitorVO.*;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 观测领域 SQL 显式绑定业务对象。联合查询和租约 SQL 不依赖租户 SQL 重写，
 * 各分支沿用业务对象标识、状态与租约条件控制读写。
 */
@Mapper

public interface DatabaseMonitorMapper {

    /**
     * 锁定并读取数据源监控记录。
     *
     * @param databaseId 数据源编号
     * @return 锁数据源数值
     */
    @Select("SELECT id FROM dh_dataforge_database WHERE id=#{databaseId} AND deleted=0 FOR UPDATE")
    Long lockDatabase(@Param("databaseId") Long databaseId);

    /**
     * 按数据源与采样时刻创建或接管过期采样租约，返回本次认领记录编号。
     *
     * @param databaseId 数据源编号
     * @param slot 占位
     * @param now 当前时间
     * @param token 当前执行或租约令牌
     * @return 认领数值
     */
    @Select(value = """
            INSERT INTO dh_dataforge_database_sample
              (database_id, slot_time, state, lease_token, lease_until)
            VALUES (#{databaseId}, #{slot}, 'COLLECTING', #{token}, #{now}::timestamp + interval '2 minutes')
            ON CONFLICT (database_id, slot_time) DO UPDATE
              SET lease_token=EXCLUDED.lease_token, lease_until=EXCLUDED.lease_until, update_time=now()
              WHERE dh_dataforge_database_sample.state='COLLECTING'
                AND dh_dataforge_database_sample.lease_until < #{now}
            RETURNING id
            """, affectData = true)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    // INSERT RETURNING 通过查询通道返回编号，权限查询解析器不支持 INSERT；仅此租约写入跳过重写。
    // 入口仍由 collectSample 校验数据源可见性和启用状态，SQL 保留对象、分钟槽与租约约束。
    @InterceptorIgnore(dataPermission = "true")
    Long claim(@Param("databaseId") Long databaseId,
            @Param("slot") LocalDateTime slot, @Param("now") LocalDateTime now, @Param("token") String token);

    /**
     * 校验数据源、采样状态及租约令牌后保存采样结果。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     * @param token 当前执行或租约令牌
     * @param collected 已收集
     * @param status 目标状态
     * @param response 响应对象
     * @param capacity 容量
     * @param collectionStatus 集合状态
     * @param collectionMessage 集合消息
     * @return 完整数值
     */
    @Update("""
            UPDATE dh_dataforge_database_sample SET state='COMPLETE', collected_time=#{collected},
                connection_status=#{status}, response_time_ms=#{response}, capacity_used=#{capacity},
                collection_status=#{collectionStatus}, collection_message=#{collectionMessage}, update_time=now()
            WHERE id=#{id} AND database_id=#{databaseId}
                AND state='COLLECTING' AND lease_token=#{token}
            """)
    int complete(@Param("databaseId") Long databaseId, @Param("id") Long id,
            @Param("token") String token, @Param("collected") LocalDateTime collected,
            @Param("status") int status, @Param("response") Long response, @Param("capacity") String capacity,
            @Param("collectionStatus") String collectionStatus, @Param("collectionMessage") String collectionMessage);

    /**
     * 有界查询尚未完成当前时段采样的已启用数据源编号。
     *
     * @param slot 占位
     * @return 有界查询尚未完成当前时段采样的已启用数据源编号结果集合
     */
    @Select("""
            SELECT d.id FROM dh_dataforge_database d
            LEFT JOIN (SELECT database_id, max(slot_time) AS last_time FROM dh_dataforge_database_sample
              WHERE state='COMPLETE' GROUP BY database_id) s ON s.database_id=d.id
            WHERE d.deleted=0 AND d.status=0
              AND (s.last_time IS NULL OR s.last_time < #{slot})
            ORDER BY s.last_time ASC NULLS FIRST, d.id LIMIT 100
            """)
    List<Long> candidates(@Param("slot") LocalDateTime slot);

    /**
     * 分批清理指定时间之前的监控采样记录。
     *
     * @param before 变更前状态
     * @return 符合条件或受影响的记录数量
     */
    @Delete("""
            DELETE FROM dh_dataforge_database_sample WHERE id IN
              (SELECT id FROM dh_dataforge_database_sample WHERE slot_time < #{before} ORDER BY id LIMIT 1000)
            """)
    int cleanSamples(@Param("before") LocalDateTime before);

    /**
     * 分页查询指定数据源的读写日志。
     *
     * @param databaseId 数据源编号
     * @param limit 读取或处理上限
     * @param offset 读取偏移量
     * @return 分页查询指定数据源的读写日志结果集合
     */
    List<ReadWriteLog> logs(@Param("databaseId") Long databaseId,
            @Param("limit") int limit, @Param("offset") long offset);

    /**
     * 统计指定数据源的读写日志数量。
     *
     * @param databaseId 数据源编号
     * @return 日志数量数值
     */
    long logCount(@Param("databaseId") Long databaseId);

    /**
     * 汇总指定数据源的连接、容量和当日读写指标。
     *
     * @param databaseId 数据源编号
     * @param today 今日
     * @return 概览
     */
    Overview summary(@Param("databaseId") Long databaseId,
            @Param("today") LocalDateTime today);

    /**
     * 查询指定起始时间以来的数据源读写日志趋势。
     *
     * @param databaseId 数据源编号
     * @param since 起始时间
     * @return 指定起始时间以来的数据源读写日志趋势结果集合
     */
    List<TrendPoint> logTrend(@Param("databaseId") Long databaseId,
            @Param("since") LocalDateTime since);

    /**
     * 查询指定起始时间以来的数据源监控采样趋势。
     *
     * @param databaseId 数据源编号
     * @param since 起始时间
     * @return 指定起始时间以来的数据源监控采样趋势结果集合
     */
    List<TrendPoint> sampleTrend(@Param("databaseId") Long databaseId,
            @Param("since") LocalDateTime since);
}
