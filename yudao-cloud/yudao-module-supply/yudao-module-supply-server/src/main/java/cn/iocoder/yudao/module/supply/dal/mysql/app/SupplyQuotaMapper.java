package cn.iocoder.yudao.module.supply.dal.mysql.app;

import lombok.Data;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** 复合业务键台账；所有语句按业务对象和执行人限定，锁仅覆盖本地记账事务。 */
@Mapper
public interface SupplyQuotaMapper {

    @Data
    class Balance {

        private String metric;

        private long quotaLimit;

        private long usedUnits;

        private long reservedUnits;
    }

    @Data
    class Row {

        private String consumerKey;

        private String requestKey;

        private long actorId;

        private String requestHash;

        private String state;

        private String token;

        private int rowLimit;

        private Integer actualRows;

        private String allocations;

        private boolean expired;
    }

    /**
     * 新增供应额度的确保记录。
     *
     * @param system 系统
     * @param key 标识键
     * @param metric 指标
     * @param limit 读取或处理上限
     * @return 确保数值
     */
    @Insert("INSERT INTO dh_supply_quota_balance(source_system,source_key,metric,quota_limit) VALUES(#{s},#{k},#{m},#{l}) ON CONFLICT DO NOTHING")
    int ensure(@Param("s") String system, @Param("k") String key, @Param("m") String metric, @Param("l") long limit);

    /**
     * 锁定并读取供应额度记录。
     *
     * @param system 系统
     * @param key 标识键
     * @return 余额
     */
    @Select("SELECT metric,quota_limit,used_units,reserved_units FROM dh_supply_quota_balance WHERE source_system=#{s} AND source_key=#{k} FOR UPDATE")
    Balance lockBalance(@Param("s") String system, @Param("k") String key);

    /**
     * 更新供应额度的变更。
     *
     * @param system 系统
     * @param key 标识键
     * @param used 已使用
     * @param reserved 已预留
     * @return 变更数值
     */
    @Update("UPDATE dh_supply_quota_balance SET used_units=used_units+#{used},reserved_units=reserved_units+#{reserved},update_time=clock_timestamp() WHERE source_system=#{s} AND source_key=#{k}")
    int change(@Param("s") String system, @Param("k") String key, @Param("used") long used,
            @Param("reserved") long reserved);

    /**
     * 新增供应额度的认领记录。
     *
     * @param consumer 消费结果的回调
     * @param key 标识键
     * @param actor 操作人编号
     * @param hash 摘要
     * @param token 当前执行或租约令牌
     * @param limit 读取或处理上限
     * @return 认领数值
     */
    @Insert("INSERT INTO dh_supply_quota_receipt(consumer_key,request_key,actor_id,request_hash,state,token,row_limit,allocations,expires_at) VALUES(#{c},#{k},#{actor},#{hash},'RESERVED',#{token},#{limit},'[]'::jsonb,clock_timestamp()+interval '5 minutes') ON CONFLICT DO NOTHING")
    int claim(@Param("c") String consumer, @Param("k") String key, @Param("actor") long actor,
            @Param("hash") String hash, @Param("token") String token, @Param("limit") int limit);

    /**
     * 更新供应额度的分配。
     *
     * @param consumer 消费结果的回调
     * @param key 标识键
     * @param limit 读取或处理上限
     * @param allocations 分配
     * @return 分配数值
     */
    @Update("UPDATE dh_supply_quota_receipt SET allocations=CAST(#{a} AS jsonb),row_limit=#{limit} WHERE consumer_key=#{c} AND request_key=#{k}")
    int allocate(@Param("c") String consumer, @Param("k") String key, @Param("limit") int limit,
            @Param("a") String allocations);

    /**
     * 锁定并读取供应额度记录。
     *
     * @param consumer 消费结果的回调
     * @param key 标识键
     * @return 行
     */
    @Select("SELECT *,expires_at<=clock_timestamp() AS expired FROM dh_supply_quota_receipt WHERE consumer_key=#{c} AND request_key=#{k} FOR UPDATE")
    Row lockReceipt(@Param("c") String consumer, @Param("k") String key);

    /**
     * 查询供应额度的回执。
     *
     * @param consumer 消费结果的回调
     * @param key 标识键
     * @param actor 操作人编号
     * @return 行
     */
    @Select("SELECT *,expires_at<=clock_timestamp() AS expired FROM dh_supply_quota_receipt WHERE consumer_key=#{c} AND request_key=#{k} AND actor_id=#{actor}")
    Row receipt(@Param("c") String consumer, @Param("k") String key, @Param("actor") long actor);

    /**
     * 更新供应额度的结束。
     *
     * @param consumer 消费结果的回调
     * @param key 标识键
     * @param state 状态
     * @param rows 待处理的行数据
     * @return 结束数值
     */
    @Update("UPDATE dh_supply_quota_receipt SET state=#{state},actual_rows=#{rows},update_time=clock_timestamp() WHERE consumer_key=#{c} AND request_key=#{k} AND state='RESERVED' AND (#{state} != 'COMMITTED' OR expires_at > clock_timestamp())")
    int finish(@Param("c") String consumer, @Param("k") String key, @Param("state") String state,
            @Param("rows") Integer rows);

    /**
     * 查询供应额度的过期。
     *
     * @return 供应额度的过期结果集合
     */
    @Select("SELECT *,true AS expired FROM dh_supply_quota_receipt WHERE state='RESERVED' AND expires_at<=clock_timestamp() ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED")
    List<Row> expired();

    /**
     * 查询供应额度的历史。
     *
     * @param actor 操作人编号
     * @return 供应额度的历史结果集合
     */
    @Select("SELECT *,expires_at<=clock_timestamp() AS expired FROM dh_supply_quota_receipt WHERE actor_id=#{actor} ORDER BY create_time DESC,consumer_key,request_key LIMIT 100")
    List<Row> history(@Param("actor") long actor);

    /**
     * 查询供应额度的余额。
     *
     * @param actor 操作人编号
     * @return 供应额度的余额结果集合
     */
    @Select("SELECT b.source_system,b.source_key,b.metric,b.quota_limit,b.used_units,b.reserved_units,b.quota_limit-b.used_units-b.reserved_units AS remaining_units FROM dh_supply_quota_balance b WHERE EXISTS(SELECT 1 FROM dh_supply_quota_receipt r CROSS JOIN LATERAL jsonb_array_elements(r.allocations) a WHERE r.actor_id=#{actor} AND a->>'sourceSystem'=b.source_system AND a->>'sourceKey'=b.source_key) ORDER BY b.source_system,b.source_key LIMIT 100")
    List<cn.iocoder.yudao.module.supply.api.app.dto.SupplyQuotaDTO.Balance> balances(@Param("actor") long actor);
}
