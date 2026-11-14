package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillAttachmentDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 在线填报-附件 Mapper
 *
 * @author Li
 */
@Mapper
public interface FillAttachmentMapper extends BaseMapperX<FillAttachmentDO> {

    /** 意图 → 就绪（CAS：只有仍为 PENDING 的意图才能写入受管文件编号） */
    default int markReady(Long id, Long storageFileId) {
        return update(null, new LambdaUpdateWrapper<FillAttachmentDO>()
                .set(FillAttachmentDO::getState, FillAttachmentDO.STATE_READY)
                .set(FillAttachmentDO::getStorageFileId, storageFileId)
                .eq(FillAttachmentDO::getId, id)
                .eq(FillAttachmentDO::getState, FillAttachmentDO.STATE_PENDING));
    }

    /** 标记已删除（CAS：从给定状态迁移） */
    default int markDeleted(Long id, String fromState) {
        return update(null, new LambdaUpdateWrapper<FillAttachmentDO>()
                .set(FillAttachmentDO::getState, FillAttachmentDO.STATE_DELETED)
                .eq(FillAttachmentDO::getId, id)
                .eq(FillAttachmentDO::getState, fromState));
    }

    /** 绑定提交快照：绑定后不再过期 */
    default int bindSubmission(Collection<Long> ids, Long submissionId) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return update(null, new LambdaUpdateWrapper<FillAttachmentDO>()
                .set(FillAttachmentDO::getSubmissionId, submissionId)
                .in(FillAttachmentDO::getId, ids)
                .eq(FillAttachmentDO::getState, FillAttachmentDO.STATE_READY));
    }

    /** 延长未绑定附件的过期时间（仍被草稿引用） */
    default int extendExpiry(Long id, LocalDateTime expiresAt) {
        return update(null, new LambdaUpdateWrapper<FillAttachmentDO>()
                .set(FillAttachmentDO::getExpiresAt, expiresAt)
                .eq(FillAttachmentDO::getId, id));
    }

    /** 已过期、未绑定的就绪附件 */
    default List<FillAttachmentDO> selectExpiredReady(LocalDateTime now, int limit) {
        return selectList(new LambdaQueryWrapperX<FillAttachmentDO>()
                .eq(FillAttachmentDO::getState, FillAttachmentDO.STATE_READY)
                .isNull(FillAttachmentDO::getSubmissionId)
                .lt(FillAttachmentDO::getExpiresAt, now)
                .orderByAsc(FillAttachmentDO::getId)
                .last("LIMIT " + limit));
    }

    /** 长时间未完成的上传意图（上传超时或进程中断） */
    default List<FillAttachmentDO> selectStalePending(LocalDateTime before, int limit) {
        return selectList(new LambdaQueryWrapperX<FillAttachmentDO>()
                .eq(FillAttachmentDO::getState, FillAttachmentDO.STATE_PENDING)
                .lt(FillAttachmentDO::getCreateTime, before)
                .orderByAsc(FillAttachmentDO::getId)
                .last("LIMIT " + limit));
    }

}
