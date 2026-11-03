package cn.iocoder.yudao.module.supply.dal.mysql.appapi;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantSourceDO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface SupplyGrantSourceMapper extends BaseMapperX<SupplyGrantSourceDO> {

    /**
     * 新增供应授权来源的来源记录。
     *
     * @param source 来源数据
     * @return 新增来源数值
     */
    @Insert("""
            INSERT INTO dh_supply_grant_source
              (source_system,source_key,app_id,api_id,source_revision,source_status,
               scope_json,payload_hash,compatibility_key)
            VALUES (#{sourceSystem},#{sourceKey},#{appId},#{apiId},#{sourceRevision},
              #{sourceStatus},CAST(#{scopeJson,jdbcType=VARCHAR} AS jsonb),#{payloadHash},#{compatibilityKey})
            """)
    int insertSource(SupplyGrantSourceDO source);

    /**
     * 更新供应授权来源的来源。
     *
     * @param source 来源数据
     * @return 符合条件或受影响的记录数量
     */
    @Update("""
            UPDATE dh_supply_grant_source SET source_revision=#{sourceRevision},source_status=#{sourceStatus},
              scope_json=CAST(#{scopeJson,jdbcType=VARCHAR} AS jsonb),
              payload_hash=#{payloadHash},compatibility_key=#{compatibilityKey},
              update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE source_system=#{sourceSystem} AND source_key=#{sourceKey}
              AND app_id=#{appId} AND api_id=#{apiId} AND source_revision < #{sourceRevision}
            """)
    int updateSource(SupplyGrantSourceDO source);

    /**
     * 锁定并读取供应授权来源记录。
     *
     * @param sourceSystem 来源系统
     * @param sourceKey 来源键
     * @return 供应授权来源
     */
    @Select("""
            SELECT * FROM dh_supply_grant_source
            WHERE source_system=#{sourceSystem} AND source_key=#{sourceKey}
            FOR UPDATE
            """)
    SupplyGrantSourceDO selectForUpdate(@Param("sourceSystem") String sourceSystem,
            @Param("sourceKey") String sourceKey);

    /**
     * 查询供应授权来源的聚合来源。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @return 供应授权来源的聚合来源结果集合
     */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT * FROM dh_supply_grant_source
            WHERE app_id=#{appId} AND api_id=#{apiId}
            ORDER BY source_system, source_key
            """)
    List<SupplyGrantSourceDO> selectAggregateSources(@Param("appId") long appId, @Param("apiId") long apiId);
}
