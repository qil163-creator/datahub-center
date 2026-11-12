package cn.iocoder.yudao.module.governance.service.profile;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileRunMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileValueMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 探查结果落库：以租约持有者身份 CAS 结束记录，与字段指标、Top 值在同一事务中写入。
 * 只接收已经过 {@link ProfileValueMasker} 处理的 DO。
 *
 * @author Li
 */
@Component
public class ProfileResultWriter {

    /** 失败原因最大长度 */
    public static final int MAX_FAIL_REASON = 480;

    @Resource
    private ProfileRunMapper runMapper;

    @Resource
    private ProfileColumnMapper columnMapper;

    @Resource
    private ProfileValueMapper valueMapper;

    /**
     * 单字段结果。
     *
     * @param column 字段指标（runId、id 由本类填充）
     * @param values Top 值（runId、profileColumnId、id 由本类填充）
     */
    public record ColumnResult(ProfileColumnDO column, List<ProfileValueDO> values) {

    }

    /**
     * 成功完成：CAS 迁移到 SUCCESS 后写入明细。租约已丢失时返回 false 且不写任何数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(Long runId, String owner, long sampleRows, Long totalRows, LocalDateTime endTime,
            List<ColumnResult> results) {
        ProfileRunDO done = new ProfileRunDO();
        done.setStatus(ProfileRunDO.STATUS_SUCCESS);
        done.setSampleRows(sampleRows);
        done.setTotalRows(totalRows);
        done.setColumnCount(results.size());
        done.setEndTime(endTime);
        if (runMapper.finish(runId, owner, done) != 1) {
            return false;
        }
        for (ColumnResult result : results) {
            ProfileColumnDO column = result.column();
            column.setId(null);
            column.setRunId(runId);
            columnMapper.insert(column);
            for (ProfileValueDO value : result.values()) {
                value.setId(null);
                value.setRunId(runId);
                value.setProfileColumnId(column.getId());
            }
            if (!result.values().isEmpty()) {
                valueMapper.insertBatch(result.values());
            }
        }
        return true;
    }

    /**
     * 以租约持有者身份标记失败；原因由调用方脱敏（不含 SQL 与连接信息）。租约已丢失时返回 false。
     */
    public boolean fail(Long runId, String owner, String reason, LocalDateTime endTime) {
        ProfileRunDO failed = new ProfileRunDO();
        failed.setStatus(ProfileRunDO.STATUS_FAILED);
        failed.setFailReason(StrUtil.maxLength(StrUtil.blankToDefault(reason, "探查执行失败"), MAX_FAIL_REASON));
        failed.setEndTime(endTime);
        return runMapper.finish(runId, owner, failed) == 1;
    }

}
