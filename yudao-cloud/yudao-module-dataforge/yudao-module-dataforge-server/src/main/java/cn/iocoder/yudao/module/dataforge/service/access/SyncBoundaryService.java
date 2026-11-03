package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.SyncBoundaryReqDTO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.SyncBoundaryMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 领域提交边界：认证/对象权限先检查，再短事务登记。不是通用分布式事务，也不触发搬数。 */
@Service
public class SyncBoundaryService {

    @Resource
    private SyncBatchObservationService observations;

    @Resource
    private DataAccessTaskMapper runs;

    private TransactionTemplate transactions;

    /**
     * 配置事务。
     *
     * @param manager 管理器
     */
    @Resource
    public void configureTransactions(PlatformTransactionManager manager) {
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    /**
     * 确保同步边界。
     *
     * @param request 请求参数
     * @return 同步边界
     */
    public SyncBoundaryDTO ensure(SyncBoundaryReqDTO request) {
        if (request == null || request.requestKey() == null || !request.requestKey().matches("quality:[a-f0-9]{64}")) {
            throw exception(DATA_ACCESS_RUN_REQUEST_CONFLICT);
        }
        var target = observations.authorize(request.target());
        var database = target.database();
        if (database.getUpdateTime() == null) {
            throw exception(DATA_ACCESS_RUN_DEFINITION_CHANGED);
        }
        String revision = database.getUpdateTime().toString();
        var candidate = new SyncBoundaryMapper.Boundary(request.requestKey(), database.getId(), revision,
                target.product().name(), target.relation().getSchemaName(), target.relation().getTableName(), null);
        return transactions.execute(tx -> {
            // 锁顺序：目标时钟 → 该目标的幂等记录；不回锁任务/运行，也不执行任何远程操作。
            runs.ensureSyncClock(database.getId());
            if (runs.lockSyncClock(database.getId()) == null) {
                throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
            }
            var existing = runs.findSyncBoundary(request.requestKey());
            if (existing != null) {
                if (!sameTarget(existing, candidate)) {
                    throw exception(DATA_ACCESS_RUN_REQUEST_CONFLICT);
                }
                return response(existing);
            }
            Long position = runs.nextSyncPosition(database.getId());
            if (position == null || position <= 0) {
                throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
            }
            var boundary = new SyncBoundaryMapper.Boundary(candidate.requestKey(), candidate.databaseId(),
                    candidate.configurationRevision(), candidate.product(), candidate.schemaName(),
                    candidate.tableName(), position);
            if (runs.insertSyncBoundary(boundary, request.target().executionUserId().toString()) != 1) {
                throw exception(DATA_ACCESS_RUN_VERSION_CONFLICT);
            }
            return response(boundary);
        });
    }

    /**
     * 判断是否一致目标条件。
     *
     * @param a 首个值
     * @param b 第二个值
     * @return 判断结果
     */
    private static boolean sameTarget(SyncBoundaryMapper.Boundary a, SyncBoundaryMapper.Boundary b) {
        return Objects.equals(a.databaseId(), b.databaseId()) && Objects.equals(a.configurationRevision(),
                b.configurationRevision())
                && Objects.equals(a.product(), b.product()) && Objects.equals(a.schemaName(), b.schemaName())
                && Objects.equals(a.tableName(), b.tableName());
    }

    /**
     * 组装同步边界响应数据。
     *
     * @param row 当前行数据
     * @return 同步边界
     */
    private static SyncBoundaryDTO response(SyncBoundaryMapper.Boundary row) {
        return new SyncBoundaryDTO(1, row.requestKey(), row.databaseId(), row.configurationRevision(),
                row.product(), row.schemaName(), row.tableName(), row.position());
    }
}
