package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MarketDeliveryOperationMapper extends BaseMapperX<MarketDeliveryOperationDO> {

    /**
     * 按指定条件统计市场交付操作的无限额策略记录数。
     */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM dh_market_resource_apply WHERE id=#{apply} AND deleted=0 AND COALESCE(apply_use_type,0)=0 AND COALESCE(apply_use_times,0)=0")
    int unlimitedPolicy(@org.apache.ibatis.annotations.Param("apply") long apply);

    /**
     * 按指定条件统计市场交付操作的有限额策略记录数。
     */
    @Select("SELECT COUNT(*) FROM dh_market_resource_apply WHERE id=#{apply} AND deleted=0 AND apply_use_type=#{type} AND apply_use_times=#{times} AND CAST(snapshot AS jsonb)->>'applyUseType'=CAST(#{type} AS text) AND CAST(snapshot AS jsonb)->>'applyUseTimes'=CAST(#{times} AS text)")
    int finitePolicy(@Param("apply") long apply, @Param("type") int type, @Param("times") long times);


    /**
     * PostgreSQL 生产为 INPUT 主键模式，自定义 INSERT 不会生成 {@code row.id}；未预置主键时
     * 取表序列默认值并由数据库回填，不能把 NULL 写入主键。
     */
    @Options(useGeneratedKeys = true, keyProperty = "row.id", keyColumn = "id")
    @Insert("""
            <script>
            INSERT INTO dh_market_delivery_operation
              (<if test="row.id != null">id,</if>operation_id,apply_id,source_grant_id,op_type,app_id,api_id,
               expected_revision,status,protocol_version,source_revision,payload_json,payload_hash,
               row_version,attempt_count,resolution_status)
            VALUES (<if test="row.id != null">#{row.id},</if>#{row.operationId},#{row.applyId},
               #{row.sourceGrantId},#{row.opType},#{row.appId},#{row.apiId},#{row.expectedRevision},'PENDING',2,
               #{row.sourceRevision},CAST(#{row.payloadJson} AS jsonb),#{row.payloadHash},0,0,'UNCONFIRMED')
            </script>
            """)
    int insertFrozen(@Param("row") MarketDeliveryOperationDO row);

    /**
     * 领取待处理的市场交付操作任务或记录。
     */
    @Select(value = """
            UPDATE dh_market_delivery_operation SET
              resolution_status=CASE WHEN status='RUNNING' THEN 'UNKNOWN' ELSE resolution_status END,
              status='RUNNING',claim_token=#{claimToken},
              row_version=row_version+1,attempt_count=attempt_count+1,
              lease_until=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')+interval '5 minutes',
              update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{id} AND deleted=0 AND protocol_version=2
              AND payload_json IS NOT NULL AND resolution_status <> 'REVIEW_REQUIRED'
              AND ((status IN ('PENDING','RETRY') AND
                (next_retry_time IS NULL OR next_retry_time<=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'))
                OR (status='RUNNING' AND lease_until<=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'))
            RETURNING *
            """, affectData = true)
    MarketDeliveryOperationDO claim(@Param("id") long id,
            @Param("claimToken") String claimToken);

    /**
     * 按业务条件更新市场交付操作的完成领取状态或数据，返回受影响行数。
     */
    @Update("""
            UPDATE dh_market_delivery_operation SET status=#{status},result_ref=#{resultRef},
              last_error=#{lastError},resolution_status=#{resolutionStatus},
              next_retry_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')+interval '1 minute',
              claim_token=NULL,lease_until=NULL,row_version=row_version+1,
              update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{id} AND deleted=0 AND protocol_version=2
              AND status='RUNNING' AND claim_token=#{claimToken} AND row_version=#{rowVersion}
              AND lease_until>clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
              AND #{status} IN ('SUCCESS','RETRY','MANUAL','CANCELLED')
              AND #{resolutionStatus} IN ('CONFIRMED','UNKNOWN','REVIEW_REQUIRED','UNCONFIRMED','SUPERSEDED')
            """)
    int completeClaim(@Param("id") long id,
            @Param("claimToken") String claimToken, @Param("rowVersion") long rowVersion,
            @Param("status") String status, @Param("resolutionStatus") String resolutionStatus,
            @Param("resultRef") String resultRef, @Param("lastError") String lastError);

    /**
     * 查询人工未知。
     */
    @Select("""
            SELECT * FROM dh_market_delivery_operation
            WHERE id=#{id} AND deleted=0 AND protocol_version=2
              AND status='MANUAL' AND resolution_status='UNKNOWN'
            """)
    MarketDeliveryOperationDO selectManualUnknown(@Param("id") long id);

    /**
     * 历史处置“确认原批准”：只把证据完整的原 GRANT 账本确认为成功，不改冻结命令、不进入自动投递队列。
     * 以锁内读取的行版本、载荷与状态为 CAS 条件。
     */
    @Update("""
            UPDATE dh_market_delivery_operation SET status='SUCCESS',resolution_status='CONFIRMED',
              result_ref=#{resultRef},last_error=#{note},
              row_version=row_version+1,update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{row.id} AND deleted=0 AND protocol_version=2
              AND op_type='GRANT' AND row_version=#{row.rowVersion}
              AND operation_id=#{row.operationId} AND apply_id=#{row.applyId}
              AND source_revision IS NOT DISTINCT FROM #{row.sourceRevision}
              AND payload_hash IS NOT DISTINCT FROM #{row.payloadHash}
              AND status=#{row.status} AND resolution_status=#{row.resolutionStatus}
              AND (resolution_status='REVIEW_REQUIRED' OR (status='MANUAL' AND resolution_status='UNKNOWN'))
            """)
    int confirmDisposedGrant(@Param("row") MarketDeliveryOperationDO row,
            @Param("resultRef") String resultRef, @Param("note") String note);

    /** 人工核查只更新原操作结果；绝不把 MANUAL 重新放进自动投递队列。 */
    @Update("""
            UPDATE dh_market_delivery_operation SET status=#{status},result_ref=#{resultRef},
              last_error=#{lastError},resolution_status=#{resolutionStatus},
              row_version=row_version+1,update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{row.id} AND deleted=0 AND protocol_version=2
              AND status='MANUAL' AND resolution_status='UNKNOWN' AND row_version=#{row.rowVersion}
              AND operation_id=#{row.operationId} AND apply_id=#{row.applyId}
              AND source_revision IS NOT DISTINCT FROM #{row.sourceRevision}
              AND payload_hash IS NOT DISTINCT FROM #{row.payloadHash}
              AND payload_json IS NOT DISTINCT FROM CAST(#{row.payloadJson} AS jsonb)
              AND #{status} IN ('SUCCESS','MANUAL')
              AND #{resolutionStatus} IN ('CONFIRMED','UNKNOWN','REVIEW_REQUIRED','SUPERSEDED')
            """)
    int reconcileManualUnknown(@Param("row") MarketDeliveryOperationDO row,
            @Param("status") String status, @Param("resolutionStatus") String resolutionStatus,
            @Param("resultRef") String resultRef, @Param("lastError") String lastError);

    /**
     * 受控重投收敛：只允许把仍处于 MANUAL/UNKNOWN 的原操作收敛为确定结果，CAS 行版本、身份与状态；
     * 不是自动投递入口，未知结果保持原状，不推进自动队列。
     */
    @Update("""
            UPDATE dh_market_delivery_operation SET status=#{status},result_ref=#{resultRef},
              last_error=#{lastError},resolution_status=#{resolutionStatus},
              row_version=row_version+1,update_time=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'
            WHERE id=#{row.id} AND deleted=0 AND protocol_version=2
              AND status='MANUAL' AND resolution_status='UNKNOWN' AND row_version=#{row.rowVersion}
              AND operation_id=#{row.operationId} AND apply_id=#{row.applyId}
              AND #{status} IN ('SUCCESS','MANUAL')
              AND #{resolutionStatus} IN ('CONFIRMED','UNKNOWN','REVIEW_REQUIRED','SUPERSEDED')
            """)
    int redeliverConverge(@Param("row") MarketDeliveryOperationDO row,
            @Param("status") String status, @Param("resolutionStatus") String resolutionStatus,
            @Param("resultRef") String resultRef, @Param("lastError") String lastError);

    /**
     * 查询到期V2。
     */
    @Select("""
            SELECT * FROM dh_market_delivery_operation
            WHERE deleted=0 AND protocol_version=2
              AND payload_json IS NOT NULL AND resolution_status <> 'REVIEW_REQUIRED'
              AND ((status IN ('PENDING','RETRY') AND
                (next_retry_time IS NULL OR next_retry_time<=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'))
                OR (status='RUNNING' AND lease_until<=clock_timestamp() AT TIME ZONE 'Asia/Shanghai'))
            ORDER BY id LIMIT 50
            """)
    List<MarketDeliveryOperationDO> selectDueV2();

    /**
     * 某个申请最近一次交付操作：交付结果的唯一权威来源。
     * 申请上的 delivery_status 只记录投递意图，不随远端回执收敛，不能当作"已生效"的证据。
     */
    @Select("""
            SELECT * FROM dh_market_delivery_operation
            WHERE apply_id=#{applyId} AND deleted=0
            ORDER BY source_revision DESC NULLS LAST, id DESC LIMIT 1
            """)
    // 当前确认不能沿用同一事务先前查询的成功行，其他节点可能已经发布更新的操作。
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    MarketDeliveryOperationDO selectLatestByApply(
            @Param("applyId") long applyId);

    /** 我的资产分页在同一只读快照中批量读取最近账本，避免逐条查询。 */
    @Select("""
            <script>
            SELECT DISTINCT ON (apply_id) * FROM dh_market_delivery_operation
            WHERE deleted=0 AND apply_id IN
              <foreach collection="applyIds" item="id" open="(" close=")" separator=",">#{id}</foreach>
            ORDER BY apply_id,source_revision DESC NULLS LAST,id DESC
            </script>
            """)
    List<MarketDeliveryOperationDO> selectLatestByApplies(@Param("applyIds") java.util.Collection<Long> applyIds);

    /**
     * 按操作编号查询市场交付操作。
     */
    default MarketDeliveryOperationDO selectByOperationId(String operationId) {
        return selectOne(MarketDeliveryOperationDO::getOperationId, operationId);
    }

    /**
     * 按申请编号查询市场交付操作。
     */
    default List<MarketDeliveryOperationDO> selectByApplyId(Long applyId) {
        return selectList(new LambdaQueryWrapperX<MarketDeliveryOperationDO>()
                .eq(MarketDeliveryOperationDO::getApplyId, applyId)
                .orderByDesc(MarketDeliveryOperationDO::getId));
    }

    /**
     * 查询可重试。
     */
    default List<MarketDeliveryOperationDO> selectRetryable() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return selectList(new LambdaQueryWrapperX<MarketDeliveryOperationDO>()
                .and(wrapper -> wrapper
                        .in(MarketDeliveryOperationDO::getStatus,
                                MarketDeliveryOperationDO.STATUS_PENDING,
                                MarketDeliveryOperationDO.STATUS_RETRY)
                        .and(inner -> inner.isNull(MarketDeliveryOperationDO::getNextRetryTime)
                                .or().le(MarketDeliveryOperationDO::getNextRetryTime, now))
                        .or(inner -> inner.eq(MarketDeliveryOperationDO::getStatus,
                                        MarketDeliveryOperationDO.STATUS_RUNNING)
                                .and(lease -> lease.isNull(MarketDeliveryOperationDO::getLeaseUntil)
                                        .or().le(MarketDeliveryOperationDO::getLeaseUntil, now))))
                .orderByAsc(MarketDeliveryOperationDO::getId)
                .last("LIMIT 50"));
    }
}
