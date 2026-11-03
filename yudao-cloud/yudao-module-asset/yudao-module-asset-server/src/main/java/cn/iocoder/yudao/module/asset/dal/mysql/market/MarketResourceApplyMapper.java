package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface MarketResourceApplyMapper extends BaseMapperX<MarketResourceApplyDO> {

    /** 首次申请（含需求草稿）：只忽略0050在途唯一冲突，其他约束透传；主键沿用平台默认序列。 */
    @Options(useGeneratedKeys = true, keyProperty = "et.id", keyColumn = "id")
    @Insert("""
            INSERT INTO dh_market_resource_apply
                (apply_no,title,asset_id,asset_type,demand_id,apply_dept_id,apply_user_id,purpose,
                 status,if_auth,apply_use_type,revision,paused,delivery_status,field_scope,duty_matter,legal_basis,
                 api_id,app_id,phone,email,apply_note,apply_start_time,apply_end_time,apply_use_times,remark,
                 snapshot,catalog_version_id,received_time,reply_deadline,delivery_deadline,
                 creator,updater,create_time,update_time,deleted)
            VALUES (#{et.applyNo},#{et.title},#{et.assetId},#{et.assetType},#{et.demandId},#{et.applyDeptId},
                    #{et.applyUserId},#{et.purpose},#{et.status},#{et.ifAuth},#{et.applyUseType},#{et.revision},
                    #{et.paused},#{et.deliveryStatus},#{et.fieldScope},#{et.dutyMatter},#{et.legalBasis},
                    #{et.apiId},#{et.appId},#{et.phone},#{et.email},#{et.applyNote},#{et.applyStartTime},
                    #{et.applyEndTime},#{et.applyUseTimes},#{et.remark},CAST(#{et.snapshot} AS jsonb),#{et.catalogVersionId},
                    #{et.receivedTime},#{et.replyDeadline},#{et.deliveryDeadline},
                    #{et.creator},#{et.updater},#{et.createTime},#{et.updateTime},0)
            ON CONFLICT (apply_user_id,asset_id) WHERE deleted=0 AND status IN (0,1,8) DO NOTHING
            """)
    int insertInitialApplyIfAbsent(@Param(Constants.ENTITY) MarketResourceApplyDO apply);

    /** 续期复制原冻结证据，显式绑定JSONB；唯一冲突交由调用方保存点回滚后核对，不忽略其他约束。 */
    @Options(useGeneratedKeys = true, keyProperty = "et.id", keyColumn = "id")
    @Insert("""
            INSERT INTO dh_market_resource_apply
                (apply_no,title,asset_id,asset_type,demand_id,apply_dept_id,apply_user_id,purpose,
                 status,if_auth,apply_use_type,revision,paused,delivery_status,field_scope,duty_matter,legal_basis,
                 api_id,app_id,phone,email,apply_note,apply_start_time,apply_end_time,apply_use_times,remark,
                 snapshot,catalog_version_id,supplement_note,received_time,reply_deadline,delivery_deadline,
                 renewal_of_apply_id,renewal_operation_id,renewal_requested_end_time,
                 creator,updater,create_time,update_time,deleted)
            VALUES (#{et.applyNo},#{et.title},#{et.assetId},#{et.assetType},#{et.demandId},#{et.applyDeptId},
                    #{et.applyUserId},#{et.purpose},#{et.status},#{et.ifAuth},#{et.applyUseType},#{et.revision},
                    #{et.paused},#{et.deliveryStatus},#{et.fieldScope},#{et.dutyMatter},#{et.legalBasis},
                    #{et.apiId},#{et.appId},#{et.phone},#{et.email},#{et.applyNote},#{et.applyStartTime},
                    #{et.applyEndTime},#{et.applyUseTimes},#{et.remark},CAST(#{et.snapshot} AS jsonb),#{et.catalogVersionId},
                    #{et.supplementNote},#{et.receivedTime},#{et.replyDeadline},#{et.deliveryDeadline},
                    #{et.renewalOfApplyId},#{et.renewalOperationId},#{et.renewalRequestedEndTime},
                    #{et.creator},#{et.updater},#{et.createTime},#{et.updateTime},0)
            """)
    int insertRenewal(@Param(Constants.ENTITY) MarketResourceApplyDO apply);

    /** 权限期限使用同一权威时钟，不采用节点 JVM 时间，也不采用调用方事务开始时间。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT clock_timestamp() AT TIME ZONE 'Asia/Shanghai'")
    LocalDateTime databaseNow();

    /**
     * 查询用于访问。
     */
    @Select("""
            SELECT * FROM dh_market_resource_apply
            WHERE id=#{id} AND apply_user_id=#{userId} AND deleted=0
            """)
    MarketResourceApplyDO selectForAccess(@Param("id") long id,
            @Param("userId") long userId);

    /** 同一数据库求值时点的完整有效来源集合；调用方多取一条以检测超限，不能静默截断授权。 */
    @Select("""
            SELECT * FROM dh_market_resource_apply
            WHERE asset_id=#{assetId} AND apply_user_id=#{userId} AND deleted=0
              AND status=2 AND if_auth=1 AND (paused IS NULL OR paused=0)
              AND (apply_start_time IS NULL OR apply_start_time<=#{evaluatedAt})
              AND (apply_end_time IS NULL OR apply_end_time>#{evaluatedAt})
            ORDER BY id LIMIT #{limit}
            """)
    List<MarketResourceApplyDO> selectQuerySources(
            @Param("assetId") long assetId, @Param("userId") long userId,
            @Param("evaluatedAt") LocalDateTime evaluatedAt, @Param("limit") int limit);

    /** 唯一身份包含墓碑；锁等待/保存点回滚后的查询不能复用 MyBatis 一级缓存中的旧空结果。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT * FROM dh_market_resource_apply
            WHERE renewal_operation_id=#{operationId}
            """)
    MarketResourceApplyDO selectRenewalByOperation(
            @Param("operationId") String operationId);

    /** 续期、需求复用和验收共用的所属申请行锁；与撤销、到期、停用写入串行化。保留旧方法名兼容调用方。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT * FROM dh_market_resource_apply
            WHERE id=#{id} AND apply_user_id=#{userId} AND deleted=0
            FOR UPDATE
            """)
    MarketResourceApplyDO selectOwnedForRenewal(
            @Param("id") long id, @Param("userId") long userId);

    /** 原申请行锁下检查不同操作号创建的有效子申请；数据库部分唯一索引负责最终并发门禁。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT EXISTS(SELECT 1 FROM dh_market_resource_apply
            WHERE renewal_of_apply_id=#{originalId} AND deleted=0
              AND status IN (0,1,2,8))
            """)
    boolean hasActiveRenewalByParent(
            @Param("originalId") long originalId);

    /** 保存点冲突后必须重读数据库；不能使用事务内插入前缓存的空结果。 */
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT EXISTS(SELECT 1 FROM dh_market_resource_apply
            WHERE apply_user_id=#{userId} AND asset_id=#{assetId}
              AND deleted=0 AND status IN (0,1,8))
            """)
    boolean hasActivePendingApply(
            @Param("userId") long userId, @Param("assetId") long assetId);

    /**
     * 按业务条件更新市场资源申请的推进来源修订号状态或数据，返回受影响行数。
     */
    @Update("""
            UPDATE dh_market_resource_apply SET source_revision=#{nextRevision}
            WHERE id=#{id} AND deleted=0
              AND source_revision=#{expectedRevision} AND #{nextRevision}>source_revision
            """)
    int advanceSourceRevision(@Param("id") long id,
            @Param("expectedRevision") long expectedRevision, @Param("nextRevision") long nextRevision);

    /**
     * 读取市场资源申请的下一个申请无序列。
     */
    @Select("SELECT nextval('dh_market_apply_no_seq')")
    Long nextApplyNoSeq();

    /**
     * 查询市场资源申请分页。
     */
    default PageResult<MarketResourceApplyDO> selectPage(MarketApplyPageReqVO reqVO, Long viewerUserId,
            boolean ownOnly, boolean providerOnly,
            Collection<Long> providerAssetIds) {
        LambdaQueryWrapperX<MarketResourceApplyDO> wrapper = new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .eqIfPresent(MarketResourceApplyDO::getAssetId, reqVO.getAssetId())
                .eqIfPresent(MarketResourceApplyDO::getAssetType, reqVO.getAssetType())
                .eqIfPresent(MarketResourceApplyDO::getStatus, reqVO.getStatus())
                .eqIfPresent(MarketResourceApplyDO::getIfAuth, reqVO.getIfAuth())
                .eqIfPresent(MarketResourceApplyDO::getApplyDeptId, reqVO.getApplyDeptId())
                .likeIfPresent(MarketResourceApplyDO::getTitle, reqVO.getKeyword())
                .likeIfPresent(MarketResourceApplyDO::getApplyNo, reqVO.getApplyNo());
        wrapper.isNull(MarketResourceApplyDO::getSourceWorkOrderId);
        if (ownOnly) {
            wrapper.eq(MarketResourceApplyDO::getApplyUserId, viewerUserId);
        } else if (providerOnly) {
            wrapper.in(MarketResourceApplyDO::getAssetId, providerAssetIds);
        } else {
            wrapper.and(query -> {
                query.eq(MarketResourceApplyDO::getApplyUserId, viewerUserId);
                if (providerAssetIds != null && !providerAssetIds.isEmpty()) {
                    query.or().in(MarketResourceApplyDO::getAssetId, providerAssetIds);
                }
            });
        }
        wrapper.orderByDesc(MarketResourceApplyDO::getId);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 按用户查询已通过已授权。
     */
    default List<MarketResourceApplyDO> selectApprovedAuthorizedByUser(Long userId) {
        return selectList(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .eq(MarketResourceApplyDO::getApplyUserId, userId)
                .eq(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_APPROVED)
                .eq(MarketResourceApplyDO::getIfAuth, MarketResourceApplyDO.AUTH_YES)
                .orderByDesc(MarketResourceApplyDO::getId));
    }

    /**
     * 按用户与资产查询已通过已授权。
     */
    default List<MarketResourceApplyDO> selectApprovedAuthorizedByUserAndAsset(Long userId, Long assetId) {
        return selectList(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .eq(MarketResourceApplyDO::getApplyUserId, userId)
                .eq(MarketResourceApplyDO::getAssetId, assetId)
                .eq(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_APPROVED)
                .eq(MarketResourceApplyDO::getIfAuth, MarketResourceApplyDO.AUTH_YES)
                .orderByDesc(MarketResourceApplyDO::getId));
    }

    /**
     * 查询生效。
     */
    default MarketResourceApplyDO selectEffective(Long userId, Long assetId, LocalDateTime now) {
        return selectOne(effectiveQuery(userId, now)
                .eq(MarketResourceApplyDO::getAssetId, assetId)
                .orderByDesc(MarketResourceApplyDO::getId).last("LIMIT 1"));
    }

    /**
     * 查询生效用于用户。
     */
    default List<MarketResourceApplyDO> selectEffectiveForUser(Long userId, Collection<Long> assetIds,
            LocalDateTime now) {
        if (assetIds != null && assetIds.isEmpty()) {
            return List.of();
        }
        return selectList(effectiveQuery(userId, now).inIfPresent(MarketResourceApplyDO::getAssetId, assetIds)
                .apply("EXISTS(SELECT 1 FROM dh_asset_data_asset visible_asset"
                        + " WHERE visible_asset.id=dh_market_resource_apply.asset_id AND visible_asset.status=1 AND visible_asset.deleted=0)")
                .orderByDesc(MarketResourceApplyDO::getId));
    }

    /**
     * 构造在指定时点仍然生效的资产申请查询条件。
     */
    private LambdaQueryWrapperX<MarketResourceApplyDO> effectiveQuery(Long userId, LocalDateTime now) {
        var query = new LambdaQueryWrapperX<MarketResourceApplyDO>();
        query.eq(MarketResourceApplyDO::getApplyUserId, userId)
                .eq(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_APPROVED)
                .eq(MarketResourceApplyDO::getIfAuth, MarketResourceApplyDO.AUTH_YES)
                .and(wrapper -> wrapper.isNull(MarketResourceApplyDO::getPaused)
                        .or().eq(MarketResourceApplyDO::getPaused, 0))
                .and(wrapper -> wrapper.isNull(MarketResourceApplyDO::getApplyStartTime)
                        .or().le(MarketResourceApplyDO::getApplyStartTime, now))
                .and(wrapper -> wrapper.isNull(MarketResourceApplyDO::getApplyEndTime)
                        .or().gt(MarketResourceApplyDO::getApplyEndTime, now));
        return query;
    }

    /**
     * 按指定条件统计市场资源申请的查询执行中已发布资产数量记录数。
     */
    @Select("""
            SELECT COUNT(DISTINCT a.id) FROM dh_asset_data_asset a
            JOIN dh_market_resource_apply r ON r.asset_id=a.id AND r.deleted=0
            WHERE a.status=1 AND a.deleted=0 AND r.apply_user_id=#{userId} AND r.status IN(1,8)
            """)
    Long selectApplyingPublishedAssetCount(@Param("userId") Long userId);

    /**
     * 按应用API查询生效。
     */
    default MarketResourceApplyDO selectEffectiveByAppApi(Long appId, Long apiId, LocalDateTime now) {
        return selectOne(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .eq(MarketResourceApplyDO::getAppId, appId)
                .eq(MarketResourceApplyDO::getApiId, apiId)
                .eq(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_APPROVED)
                .eq(MarketResourceApplyDO::getIfAuth, MarketResourceApplyDO.AUTH_YES)
                .and(wrapper -> wrapper.isNull(MarketResourceApplyDO::getApplyStartTime)
                        .or().le(MarketResourceApplyDO::getApplyStartTime, now))
                .and(wrapper -> wrapper.isNull(MarketResourceApplyDO::getApplyEndTime)
                        .or().ge(MarketResourceApplyDO::getApplyEndTime, now))
                .orderByDesc(MarketResourceApplyDO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按用户与资产查询有效。
     */
    default MarketResourceApplyDO selectActiveByUserAndAsset(Long userId, Long assetId) {
        return selectOne(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .eq(MarketResourceApplyDO::getApplyUserId, userId)
                .eq(MarketResourceApplyDO::getAssetId, assetId)
                .in(MarketResourceApplyDO::getStatus,
                        MarketResourceApplyDO.STATUS_DRAFT,
                        MarketResourceApplyDO.STATUS_PENDING,
                        MarketResourceApplyDO.STATUS_SUPPLEMENT)
                .last("LIMIT 1"));
    }

    /**
     * 按用户与资产编号查询最新。
     */
    default List<MarketResourceApplyDO> selectLatestByUserAndAssetIds(Long userId, Collection<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .eq(MarketResourceApplyDO::getApplyUserId, userId)
                .in(MarketResourceApplyDO::getAssetId, assetIds)
                .orderByDesc(MarketResourceApplyDO::getId));
    }

    /**
     * 按状态查询市场资源申请数量。
     */
    default long selectCountByStatus(Integer status) {
        return selectCount(MarketResourceApplyDO::getStatus, status);
    }

    /**
     * 查询逾期待处理数量。
     */
    default long selectOverduePendingCount(LocalDateTime now) {
        return selectCount(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .eq(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_PENDING)
                .isNotNull(MarketResourceApplyDO::getReplyDeadline)
                .lt(MarketResourceApplyDO::getReplyDeadline, now));
    }

    /**
     * 查询首个已使用数量。
     */
    default long selectFirstUsedCount() {
        return selectCount(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .isNotNull(MarketResourceApplyDO::getFirstUsedTime));
    }

    /** 按原申请与幂等编号定位续期申请；在平台范围内唯一。 */
    default MarketResourceApplyDO selectRenewal(Long originalApplyId, String operationId) {
        if (originalApplyId == null || operationId == null || operationId.isBlank()) {
            return null;
        }
        return selectOne(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .eq(MarketResourceApplyDO::getRenewalOfApplyId, originalApplyId)
                .eq(MarketResourceApplyDO::getRenewalOperationId, operationId)
                .last("LIMIT 1"));
    }

    /** 到期扫描使用数据库时间与有界批量，避免各节点本机时钟差异与一次性全表处理。 */
    @Select("""
            SELECT * FROM dh_market_resource_apply
            WHERE deleted=0 AND status=2 AND source_work_order_id IS NULL
              AND apply_end_time IS NOT NULL
              AND apply_end_time <= clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            ORDER BY id LIMIT #{limit}
            """)
    List<MarketResourceApplyDO> selectExpiredDue(@Param("limit") int limit);

    /**
     * 查询已过期已通过。
     */
    default List<MarketResourceApplyDO> selectExpiredApproved(LocalDateTime now) {
        return selectList(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .isNull(MarketResourceApplyDO::getSourceWorkOrderId)
                .eq(MarketResourceApplyDO::getStatus, MarketResourceApplyDO.STATUS_APPROVED)
                .isNotNull(MarketResourceApplyDO::getApplyEndTime)
                .lt(MarketResourceApplyDO::getApplyEndTime, now)
                .last("LIMIT 100"));
    }

    /**
     * 按资产查询生效。
     */
    default List<MarketResourceApplyDO> selectEffectiveByAsset(Long userId, Long assetId, LocalDateTime now) {
        return selectApprovedAuthorizedByUserAndAsset(userId, assetId);
    }

    /**
     * 查询市场资源申请数量当日。
     */
    default long selectCountToday(LocalDateTime start, LocalDateTime end) {
        return selectCount(new LambdaQueryWrapperX<MarketResourceApplyDO>()
                .ge(MarketResourceApplyDO::getCreateTime, start)
                .lt(MarketResourceApplyDO::getCreateTime, end));
    }

}
