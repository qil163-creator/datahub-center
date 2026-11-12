package cn.iocoder.yudao.module.governance.dal.mysql.profile;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileRunDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据探查-探查记录 Mapper：状态迁移一律按 CAS（状态 + 租约持有者）条件更新。
 *
 * @author Li
 */
@Mapper
public interface ProfileRunMapper extends BaseMapperX<ProfileRunDO> {

    /**
     * 查询元数据表当前的 PENDING/RUNNING 记录（部分唯一索引保证至多一条）。
     */
    default ProfileRunDO selectActive(Long metaTableId) {
        return selectOne(new LambdaQueryWrapperX<ProfileRunDO>()
                .eq(ProfileRunDO::getMetaTableId, metaTableId)
                .in(ProfileRunDO::getStatus, ProfileRunDO.STATUS_PENDING, ProfileRunDO.STATUS_RUNNING)
                .last("LIMIT 1"));
    }

    /**
     * 查询元数据表最近一次探查（任意状态）。
     */
    default ProfileRunDO selectLatest(Long metaTableId) {
        return selectOne(new LambdaQueryWrapperX<ProfileRunDO>()
                .eq(ProfileRunDO::getMetaTableId, metaTableId)
                .orderByDesc(ProfileRunDO::getId).last("LIMIT 1"));
    }

    /**
     * 查询元数据表最近一次成功的探查。
     */
    default ProfileRunDO selectLatestSuccess(Long metaTableId) {
        return selectOne(new LambdaQueryWrapperX<ProfileRunDO>()
                .eq(ProfileRunDO::getMetaTableId, metaTableId)
                .eq(ProfileRunDO::getStatus, ProfileRunDO.STATUS_SUCCESS)
                .orderByDesc(ProfileRunDO::getId).last("LIMIT 1"));
    }

    /**
     * 按元数据表分页查询探查记录，按编号倒序。
     */
    default PageResult<ProfileRunDO> selectPage(ProfileRunPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ProfileRunDO>()
                .eq(ProfileRunDO::getMetaTableId, reqVO.getMetaTableId())
                .eqIfPresent(ProfileRunDO::getStatus, reqVO.getStatus())
                .orderByDesc(ProfileRunDO::getId));
    }

    /**
     * 可领取的记录：PENDING，或租约已过期且仍有剩余次数的 RUNNING（失联恢复）。
     */
    default List<ProfileRunDO> selectClaimable(LocalDateTime now, int maxAttempts, int limit) {
        return selectList(new LambdaQueryWrapperX<ProfileRunDO>()
                .and(q -> q.eq(ProfileRunDO::getStatus, ProfileRunDO.STATUS_PENDING)
                        .or(r -> r.eq(ProfileRunDO::getStatus, ProfileRunDO.STATUS_RUNNING)
                                .lt(ProfileRunDO::getLeaseUntil, now)))
                .lt(ProfileRunDO::getAttemptCount, maxAttempts)
                .orderByAsc(ProfileRunDO::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * CAS 领取：仅当记录仍为 PENDING，或 RUNNING 且租约已过期、次数未用尽时成功。返回 1 表示领取成功。
     */
    default int claim(Long id, String owner, LocalDateTime now, LocalDateTime leaseUntil, int maxAttempts) {
        ProfileRunDO update = new ProfileRunDO();
        update.setStatus(ProfileRunDO.STATUS_RUNNING);
        update.setLeaseOwner(owner);
        update.setLeaseUntil(leaseUntil);
        LambdaUpdateWrapper<ProfileRunDO> wrapper = Wrappers.lambdaUpdate(ProfileRunDO.class)
                .setSql("attempt_count = attempt_count + 1")
                .setSql("start_time = COALESCE(start_time, {0})", now)
                .eq(ProfileRunDO::getId, id)
                .lt(ProfileRunDO::getAttemptCount, maxAttempts)
                .and(q -> q.eq(ProfileRunDO::getStatus, ProfileRunDO.STATUS_PENDING)
                        .or(r -> r.eq(ProfileRunDO::getStatus, ProfileRunDO.STATUS_RUNNING)
                                .lt(ProfileRunDO::getLeaseUntil, now)));
        return update(update, wrapper);
    }

    /**
     * 续租：仅当前租约持有者且记录仍为 RUNNING 时成功。返回 0 表示租约已丢失，调用方必须放弃本次执行。
     */
    default int renew(Long id, String owner, LocalDateTime leaseUntil) {
        ProfileRunDO update = new ProfileRunDO();
        update.setLeaseUntil(leaseUntil);
        return update(update, Wrappers.lambdaUpdate(ProfileRunDO.class)
                .eq(ProfileRunDO::getId, id)
                .eq(ProfileRunDO::getStatus, ProfileRunDO.STATUS_RUNNING)
                .eq(ProfileRunDO::getLeaseOwner, owner));
    }

    /**
     * 以租约持有者身份结束执行（SUCCESS/FAILED），清空租约。返回 0 表示租约已丢失。
     */
    default int finish(Long id, String owner, ProfileRunDO result) {
        result.setId(null);
        return update(result, Wrappers.lambdaUpdate(ProfileRunDO.class)
                .set(ProfileRunDO::getLeaseOwner, null)
                .set(ProfileRunDO::getLeaseUntil, null)
                .eq(ProfileRunDO::getId, id)
                .eq(ProfileRunDO::getStatus, ProfileRunDO.STATUS_RUNNING)
                .eq(ProfileRunDO::getLeaseOwner, owner));
    }

    /**
     * 租约过期且领取次数用尽的记录标记为失败，释放单表并发名额。
     */
    default int failExhausted(LocalDateTime now, int maxAttempts, String reason) {
        ProfileRunDO update = new ProfileRunDO();
        update.setStatus(ProfileRunDO.STATUS_FAILED);
        update.setEndTime(now);
        update.setFailReason(reason);
        return update(update, Wrappers.lambdaUpdate(ProfileRunDO.class)
                .set(ProfileRunDO::getLeaseOwner, null)
                .set(ProfileRunDO::getLeaseUntil, null)
                .eq(ProfileRunDO::getStatus, ProfileRunDO.STATUS_RUNNING)
                .lt(ProfileRunDO::getLeaseUntil, now)
                .ge(ProfileRunDO::getAttemptCount, maxAttempts));
    }

}
