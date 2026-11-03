package cn.iocoder.yudao.module.supply.dal.mysql.appapi;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.List;

/**
 * 数据服务-应用 API 授权 Mapper
 *
 * @author Li
 */
@Mapper
public interface AppApiMapper extends BaseMapperX<AppApiDO> {

    /** 应用父行已锁定；读取本次整体状态变更影响的 ETL 授予，用于同事务追加审计。 */
    @Select("""
            SELECT * FROM dh_supply_app_api WHERE app_id=#{appId} AND deleted=0
              AND protocol_version=0 AND source_type='ADMIN_ETL_BATCH' ORDER BY id FOR UPDATE
            """)
    List<AppApiDO> selectEtlGrantsForApp(@Param("appId") long appId);

    /**
     * 查询应用 API 授权的授权控制按编号。
     *
     * @param id 记录编号
     * @return 应用 API 授权
     */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM dh_supply_app_api WHERE id=#{id} AND deleted=0")
    AppApiDO selectGrantControlById(@Param("id") long id);

    /**
     * 更新应用 API 授权的变更聚合禁用状态。
     *
     * @param id 记录编号
     * @param disabled 禁用状态
     * @param revision 修订号
     * @return 变更聚合禁用状态数值
     */
    @org.apache.ibatis.annotations.Update("""
            UPDATE dh_supply_app_api SET aggregate_disabled=#{disabled}, aggregate_revision=aggregate_revision+1,
              grant_revision=CASE WHEN protocol_version=0 AND source_type='ADMIN_ETL_BATCH' THEN grant_revision+1 ELSE grant_revision END,
              update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{id} AND deleted=0 AND aggregate_revision=#{revision}
              AND aggregate_disabled IS DISTINCT FROM #{disabled}
            """)
    int changeAggregateDisabled(@Param("id") long id,
            @Param("disabled") boolean disabled, @Param("revision") long revision);

    /** 永久关闭独立 ADMIN 授权：终态 grant_status=3 并推进聚合版本；只命中 protocol=0 的 ADMIN 行。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE dh_supply_app_api SET grant_status=3, aggregate_revision=aggregate_revision+1,
              grant_revision=CASE WHEN source_type='ADMIN_ETL_BATCH' THEN grant_revision+1 ELSE grant_revision END,
              update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{id} AND deleted=0 AND aggregate_revision=#{revision}
              AND protocol_version=0 AND ((source_type='ADMIN' AND permission_user_id IS NULL)
                OR (source_type='ADMIN_ETL_BATCH' AND permission_user_id>0))
              AND (grant_status IS NULL OR grant_status<>3)
            """)
    int closeAdminGrant(@Param("id") long id, @Param("revision") long revision);

    /** 父行及聚合行已锁定；完整替换期限并清空旧边界，保留身份、批准合同和整体开关。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE dh_supply_app_api SET forever_flag=#{foreverFlag}, start_date=#{startDate}, end_date=#{endDate},
              valid_from=#{validFrom}, valid_to=#{validTo}, description=#{description},
              aggregate_revision=aggregate_revision+1, grant_revision=grant_revision+1,
              updater=CAST(#{actorId} AS text), update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{id} AND deleted=0 AND aggregate_revision=#{revision}
              AND protocol_version=0 AND source_type='ADMIN' AND permission_user_id IS NULL
              AND (grant_status IS NULL OR grant_status<>3)
            """)
    int updateAdminGrantValidity(@Param("id") long id,
            @Param("revision") long revision, @Param("foreverFlag") int foreverFlag,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate,
            @Param("validFrom") LocalDateTime validFrom, @Param("validTo") LocalDateTime validTo,
            @Param("description") String description, @Param("actorId") long actorId);

    /**
     * API 是否仍被授权引用：旧协议看聚合 grant_status；v2 聚合的 grant_status 固定为 3，
     * 必须按来源账本判断，只要还有未撤销（ACTIVE/SUSPENDED/REVIEW_REQUIRED）来源就仍被引用。
     */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT count(*) FROM dh_supply_app_api a
            WHERE a.api_id=#{apiId} AND a.deleted=0
              AND ((a.protocol_version IS DISTINCT FROM 2 AND (a.grant_status IS NULL OR a.grant_status<>3))
                OR (a.protocol_version=2 AND EXISTS (
                      SELECT 1 FROM dh_supply_grant_source s
                      WHERE s.app_id=a.app_id AND s.api_id=a.api_id
                        AND s.deleted=0 AND s.source_status<>'REVOKED')))
            """)
    long countOpenGrantReferences(@Param("apiId") long apiId);

    /**
     * 查询应用 API 授权的授权控制。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @return 应用 API 授权
     */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT * FROM dh_supply_app_api
            WHERE app_id=#{appId} AND api_id=#{apiId} AND deleted=0
            """)
    AppApiDO selectGrantControl(@Param("appId") long appId, @Param("apiId") long apiId);

    // 新协议尚未接入字段投影的旧网关：grant_status=3 保持 fail-closed，不能视为全字段授权。

    /**
     * 新增应用 API 授权的来源聚合记录。
     *
     * @param aggregate 聚合
     * @return 新增来源聚合数值
     */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO dh_supply_app_api
              (app_id,api_id,protocol_version,aggregate_revision,aggregate_disabled,
               migration_status,grant_status,forever_flag)
            VALUES (#{appId},#{apiId},2,0,false,'CONFIRMED',3,0)
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertSourceAggregate(AppApiDO aggregate);

    /**
     * 更新应用 API 授权的推进来源修订号。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @return 推进来源修订号数值
     */
    @org.apache.ibatis.annotations.Update("""
            UPDATE dh_supply_app_api SET aggregate_revision=aggregate_revision+1,
              update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{id} AND deleted=0
              AND protocol_version=2 AND aggregate_revision=#{revision}
            """)
    int advanceSourceRevision(@Param("id") long id,
            @Param("revision") long revision);

    /** 调用方先锁定应用父行；应用控制改变与全部来源聚合版本在同一事务中提交。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE dh_supply_app_api SET aggregate_revision=aggregate_revision+1,
              grant_revision=CASE WHEN protocol_version=0 AND source_type='ADMIN_ETL_BATCH' THEN grant_revision+1 ELSE grant_revision END,
              update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE app_id=#{appId} AND deleted=0
              AND (protocol_version=2 OR (protocol_version=0 AND source_type='ADMIN_ETL_BATCH'))
            """)
    int advanceAppControlRevision(@Param("appId") long appId);

    /**
     * 锁定并读取应用 API 授权记录。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @return 应用 API 授权
     */
    @Select("""
            SELECT * FROM dh_supply_app_api
            WHERE app_id=#{appId} AND api_id=#{apiId} AND deleted=0 FOR UPDATE
            """)
    AppApiDO selectAggregateForUpdate(@Param("appId") long appId, @Param("apiId") long apiId);

    /**
     * 以毫秒读取数据库当前时间。
     *
     * @return 数据源时间毫秒数数值
     */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    @Select("SELECT floor(extract(epoch FROM clock_timestamp())*1000)::bigint")
    long databaseTimeMillis();

    /**
     * 按应用编号查询应用 API 授权列表。
     *
     * @param appId 应用编号
     * @return 按应用编号查询应用 API 授权列表结果集合
     */
    default List<AppApiDO> selectListByAppId(Long appId) {
        return selectList(new LambdaQueryWrapperX<AppApiDO>()
                .eq(AppApiDO::getAppId, appId)
                .orderByDesc(AppApiDO::getId));
    }

    /**
     * 按应用编号与API 编号查询应用 API 授权。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     * @return 应用 API 授权
     */
    default AppApiDO selectByAppIdAndApiId(Long appId, Long apiId) {
        return selectOne(new LambdaQueryWrapperX<AppApiDO>()
                .eq(AppApiDO::getAppId, appId)
                .eq(AppApiDO::getApiId, apiId)
                .orderByDesc(AppApiDO::getGrantRevision)
                .orderByDesc(AppApiDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按API 编号查询应用 API 授权数量。
     *
     * @param apiId API 编号
     * @return SELECT数量按API 编号数值
     */
    default Long selectCountByApiId(Long apiId) {
        return selectCount(AppApiDO::getApiId, apiId);
    }

    /**
     * 按API 编号查询有效数量。
     *
     * @param apiId API 编号
     * @return SELECT有效数量按API 编号数值
     */
    default Long selectActiveCountByApiId(Long apiId) {
        return selectCount(new LambdaQueryWrapperX<AppApiDO>()
                .eq(AppApiDO::getApiId, apiId)
                .and(wrapper -> wrapper.isNull(AppApiDO::getGrantStatus)
                        .or().ne(AppApiDO::getGrantStatus, 3)));
    }

    /**
     * 按应用编号删除应用 API 授权。
     *
     * @param appId 应用编号
     */
    default void deleteByAppId(Long appId) {
        delete(new LambdaQueryWrapperX<AppApiDO>().eq(AppApiDO::getAppId, appId));
    }

}
