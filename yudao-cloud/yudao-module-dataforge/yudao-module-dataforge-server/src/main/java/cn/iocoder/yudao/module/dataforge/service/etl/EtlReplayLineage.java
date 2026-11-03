package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlTargetBatchDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlTargetBatchMapper;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_TARGET_INTEGRITY_FAILED;

/** 源整改始终以原业务键范围为准，沿持久替换链定位当前物理批次。 */
final class EtlReplayLineage {

    /**
     * 限制ETL 重放血缘工具的实例化，统一通过静态能力使用。
     */
    private EtlReplayLineage() {
    }

    /**
     * 读取当前ETL 目标批次。
     *
     * @param mapper 数据访问
     * @param root 根
     * @return ETL 目标批次
     */
    static EtlTargetBatchDO current(EtlTargetBatchMapper mapper, EtlTargetBatchDO root) {
        var current = root;
        Set<Long> visited = new HashSet<>();
        for (int depth = 0; depth < 64; depth++) {
            if (current == null || !visited.add(current.getId())) {
                throw exception(ETL_TARGET_INTEGRITY_FAILED);
            }
            if (!"SUPERSEDED".equals(current.getCommitStatus())) {
                return current;
            }
            var next = current.getReplacedByBatchId() == null ? null : mapper.selectById(
                    current.getReplacedByBatchId());
            if (next == null || !Objects.equals(next.getReplayOfBatchId(), current.getId())
                    || !Objects.equals(next.getReplayOfRecordId(), current.getRecordId())
                    || !Objects.equals(next.getNodeNo(), root.getNodeNo())
                    || !Objects.equals(next.getInputCount(), root.getInputCount())
                    || !Objects.equals(next.getIdentityHash(), root.getIdentityHash())
                    || !Objects.equals(EtlTargetDatabaseAdapter.physicalKey(next),
                    EtlTargetDatabaseAdapter.physicalKey(root))
                    || !JSONUtil.parseObj(next.getConfigJson()).equals(JSONUtil.parseObj(root.getConfigJson()))) {
                throw exception(ETL_TARGET_INTEGRITY_FAILED);
            }
            current = next;
        }
        throw exception(ETL_TARGET_INTEGRITY_FAILED);
    }
}
