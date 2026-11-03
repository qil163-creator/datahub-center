package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.governance.api.etl.GovernanceEtlApi;
import cn.iocoder.yudao.module.governance.api.etl.dto.QualityEtlArchiveAuthorizeReqDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 逻辑归档完整保留审计凭据；保留策略限制访问与重放，绝不静默删除未解决的凭据。 */
@Service
public class EtlArchiveService {

    @Resource
    private EtlArchiveMapper archives;

    @Resource
    private EtlQualityBatchMapper batches;

    @Resource
    private EtlRecordMapper records;

    @Resource
    private EtlRunCoordinator coordinator;

    @Resource
    private GovernanceEtlApi governance;

    @Resource
    private ExecutionAccessService access;

    /**
     * 读取ETL归档响应数据。
     *
     * @param id 记录编号
     * @return ETL归档响应数据
     */
    public EtlArchiveRespVO get(Long id) {
        access.requireUserPermission(SecurityFrameworkUtils.getLoginUserId(), "dataforge:etl:query");
        if (records.selectById(id) == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        return BeanUtils.toBean(find(id), EtlArchiveRespVO.class);
    }

    /**
     * 归档ETL归档。
     *
     * @param request 请求参数
     */
    public void archive(EtlArchiveReqVO request) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        access.requireUserPermission(actor, "dataforge:etl:save");
        if (records.selectById(request.getRecordId()) == null) {
            throw exception(ETL_RECORD_NOT_EXISTS);
        }
        var qualityBatches = batches.selectList(Wrappers.lambdaQuery(EtlQualityBatchDO.class)
                .eq(EtlQualityBatchDO::getRecordId, request.getRecordId()));
        if (qualityBatches.stream()
                .anyMatch(b -> !"REGISTERED".equals(b.getRegistrationStatus()) || b.getGovernanceReportId() == null)) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "质量报告尚未登记完成，不能归档");
        }
        if (!qualityBatches.isEmpty()) {
            var authorization = new QualityEtlArchiveAuthorizeReqDTO();
            authorization.setExecutionUserId(actor);
            authorization.setReportIds(
                    qualityBatches.stream().map(EtlQualityBatchDO::getGovernanceReportId).distinct().toList());
            if (!Boolean.TRUE.equals(governance.canArchiveReports(authorization).getCheckedData())) {
                throw exception(ETL_TARGET_CONFIG_INVALID, "存在未完成整改、复检或不可见报告，归档被阻止");
            }
        }
        coordinator.archiveRun(request, actor);
    }

    /**
     * 恢复ETL归档。
     *
     * @param request 请求参数
     */
    public void restore(EtlReplayRetryReqVO request) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        access.requireUserPermission(actor, "dataforge:etl:save");
        coordinator.restoreArchivedRun(request.getRecordId(), actor, request.getReason());
    }

    /**
     * 校验有效。
     *
     * @param recordId 记录编号
     */
    public void requireActive(Long recordId) {
        var archive = find(recordId);
        if (archive != null && archive.getRestoredAt() == null) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "该运行已归档，请在保留期内恢复后操作");
        }
    }

    /**
     * 校验证据访问。
     *
     * @param recordId 记录编号
     */
    public void requireEvidenceAccess(Long recordId) {
        var archive = find(recordId);
        if (archive != null && archive.getRestoredAt() == null && !archive.getEvidenceAccessUntil()
                .isAfter(records.databaseNow())) {
            throw exception(ETL_TARGET_CONFIG_INVALID, "原始证据访问保留期已结束，仅保留报告和审计摘要");
        }
    }

    /**
     * 查找ETL归档。
     *
     * @param recordId 记录编号
     * @return ETL归档
     */
    private EtlArchiveDO find(Long recordId) {
        return archives.selectOne(Wrappers.lambdaQuery(EtlArchiveDO.class).eq(EtlArchiveDO::getRecordId, recordId));
    }
}
