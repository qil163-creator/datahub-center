package cn.iocoder.yudao.module.asset.dal.mysql.dataexport;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataexport.DataExportRequestDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 导出申请 Mapper。状态迁移一律按“当前状态 + 修订号”CAS。
 */
@Mapper
public interface DataExportRequestMapper extends BaseMapperX<DataExportRequestDO> {

    /**
     * 本人的申请分页。
     */
    default PageResult<DataExportRequestDO> selectMyPage(Long userId, PageParam page) {
        return selectPage(page, new LambdaQueryWrapperX<DataExportRequestDO>()
                .eq(DataExportRequestDO::getUserId, userId)
                .orderByDesc(DataExportRequestDO::getId));
    }

    /**
     * 记录流程实例编号（只在尚未记录时写入）。
     */
    default int bindProcess(Long id, String processInstanceId) {
        return update(null, new LambdaUpdateWrapper<DataExportRequestDO>()
                .set(DataExportRequestDO::getProcessInstanceId, processInstanceId)
                .setSql("revision = revision + 1")
                .eq(DataExportRequestDO::getId, id)
                .isNull(DataExportRequestDO::getProcessInstanceId));
    }

    /**
     * 从指定状态迁移。
     */
    default int transit(Long id, String from, String to, String resultReason, LocalDateTime approvedTime,
                        LocalDateTime expireTime) {
        LambdaUpdateWrapper<DataExportRequestDO> update = new LambdaUpdateWrapper<DataExportRequestDO>()
                .set(DataExportRequestDO::getStatus, to)
                .setSql("revision = revision + 1")
                .eq(DataExportRequestDO::getId, id)
                .eq(DataExportRequestDO::getStatus, from);
        if (resultReason != null) {
            update.set(DataExportRequestDO::getResultReason, resultReason);
        }
        if (approvedTime != null) {
            update.set(DataExportRequestDO::getApprovedTime, approvedTime);
            update.set(DataExportRequestDO::getExpireTime, expireTime);
        }
        return update(null, update);
    }

    /**
     * 一次性下载：只有已批准、未过期、修订号未变的申请可以标记为已下载。
     */
    default int markDownloaded(Long id, Integer expectedRevision, LocalDateTime now, int rows) {
        return update(null, new LambdaUpdateWrapper<DataExportRequestDO>()
                .set(DataExportRequestDO::getStatus, DataExportRequestDO.STATUS_DOWNLOADED)
                .set(DataExportRequestDO::getDownloadTime, now)
                .set(DataExportRequestDO::getDownloadRows, rows)
                .setSql("revision = revision + 1")
                .eq(DataExportRequestDO::getId, id)
                .eq(DataExportRequestDO::getStatus, DataExportRequestDO.STATUS_APPROVED)
                .eq(DataExportRequestDO::getRevision, expectedRevision)
                .gt(DataExportRequestDO::getExpireTime, now));
    }
}
