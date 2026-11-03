package cn.iocoder.yudao.module.supply.dal.mysql.dataapi;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiOperationDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DataApiOperationMapper extends BaseMapperX<DataApiOperationDO> {

    /**
     * 新增数据 API操作的认领记录。
     *
     * @param app 调用应用信息
     * @param api 本次使用的 API 定义
     * @param release 发布
     * @param key 标识键
     * @param hash 摘要
     * @param trace 链路
     * @return 认领数值
     */
    @Insert("""
            INSERT INTO dh_supply_api_operation(app_id, api_id, release_id, operation_key,
              request_hash, state, lease_until, trace_id)
            VALUES(#{app}, #{api}, #{release}, #{key}, #{hash}, 'RUNNING',
              CURRENT_TIMESTAMP + INTERVAL '5 minutes', #{trace})
            ON CONFLICT (app_id, api_id, operation_key) DO NOTHING
            """)
    int claim(@Param("app") long app, @Param("api") long api,
            @Param("release") long release, @Param("key") String key, @Param("hash") String hash,
            @Param("trace") String trace);

    /**
     * 查询数据 API操作的。
     *
     * @param app 调用应用信息
     * @param api 本次使用的 API 定义
     * @param key 标识键
     * @return 数据 API操作
     */
    @Select("""
            SELECT * FROM dh_supply_api_operation WHERE app_id=#{app}
              AND api_id=#{api} AND operation_key=#{key} AND deleted=0
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DataApiOperationDO find(@Param("app") long app,
            @Param("api") long api, @Param("key") String key);

    /**
     * 更新数据 API操作的结束。
     *
     * @param id 记录编号
     * @param state 状态
     * @param result 处理结果
     * @return 结束数值
     */
    @Update("""
            UPDATE dh_supply_api_operation SET state=#{state}, result_json=#{result}, update_time=CURRENT_TIMESTAMP
            WHERE id=#{id} AND state IN ('RUNNING','UNKNOWN') AND resolved_by IS NULL
            """)
    int finish(@Param("id") long id,
            @Param("state") String state, @Param("result") String result);

    /**
     * 更新数据 API操作的过期。
     *
     * @return 符合条件或受影响的记录数量
     */
    @Update("""
            UPDATE dh_supply_api_operation SET state='UNKNOWN', update_time=CURRENT_TIMESTAMP
            WHERE id IN (SELECT id FROM dh_supply_api_operation
              WHERE state='RUNNING' AND lease_until<CURRENT_TIMESTAMP
              ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED)
            """)
    int expire();

    /**
     * 查询数据 API操作的历史。
     *
     * @param api 本次使用的 API 定义
     * @param state 状态
     * @param after 变更后状态
     * @param key 标识键
     * @return 数据 API操作的历史结果集合
     */
    @Select("""
            <script>SELECT * FROM dh_supply_api_operation WHERE deleted=0
              <if test="api != null">AND api_id=#{api}</if>
              <if test="state != null and state != ''">AND state=#{state}</if>
              <if test="key != null and key != ''">AND operation_key=#{key}</if>
              <if test="after != null">AND id &lt; #{after}</if>
              ORDER BY id DESC LIMIT 51</script>
            """)
    List<DataApiOperationDO> history(@Param("api") Long api,
            @Param("state") String state, @Param("after") Long after, @Param("key") String key);

    /**
     * 查询数据 API操作的。
     *
     * @param id 记录编号
     * @return 数据 API操作
     */
    @Select("SELECT * FROM dh_supply_api_operation WHERE id=#{id} AND deleted=0")
    DataApiOperationDO get(@Param("id") long id);

    /**
     * 更新数据 API操作的解析。
     *
     * @param id 记录编号
     * @param state 状态
     * @param actor 操作人编号
     * @param reason 操作原因
     * @param evidence 处置依据
     * @return 解析数值
     */
    @Update("""
            UPDATE dh_supply_api_operation SET state=#{state}, resolved_by=#{actor}, resolution=#{reason},
              evidence=#{evidence}, resolved_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP, updater=#{actor}
            WHERE id=#{id} AND state='UNKNOWN' AND resolved_by IS NULL
            """)
    int resolve(@Param("id") long id, @Param("state") String state,
            @Param("actor") long actor, @Param("reason") String reason, @Param("evidence") String evidence);
}
