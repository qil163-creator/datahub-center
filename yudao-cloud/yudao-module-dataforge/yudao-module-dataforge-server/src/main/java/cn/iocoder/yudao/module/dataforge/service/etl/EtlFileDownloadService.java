package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileStore;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlFileOutputDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlFileOutputMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 按归属者作用域制定发布及权限策略；有容量上限的哈希计算与文件流传输由 biz-etl 负责。 */
@Service
public class EtlFileDownloadService {

    private static final Set<String> FILE_TOOLS = Set.of("write-csv", "write-json", "write-excel", "write-text");

    @Resource
    private EtlFileOutputMapper files;

    @Resource
    private EtlRecordMapper records;

    @Resource
    private EtlFileRecoveryService recovery;

    @Resource
    private ExecutionAccessService access;

    @Resource
    private EtlNodeAccessService nodeAccess;

    @Resource
    private EtlFileSourceProofService sourceProof;

    public record Download(String fileName, EtlFileStore.Download content) implements AutoCloseable {

        /**
         * 关闭当前实例持有的连接、会话或执行资源。
         */
        @Override
        public void close() throws IOException {
            content.close();
        }
    }

    /** 客户端唯一输入是无内部含义的台账编号，不接受路径、租户或执行者信息。 */
    public Download open(Long id) {
        if (id == null || id <= 0) {
            throw exception(ETL_FILE_NOT_OWNER);
        }
        Long actor = SecurityFrameworkUtils.getLoginUserId();

        if (actor == null) {
            throw exception(ETL_FILE_NOT_OWNER);
        }
        access.requireUserPermission(actor, "dataforge:etl:query");
        var row = owned(id, actor);
        var run = successful(row, actor);
        authorizeFrozenSource(row, run, actor);
        EtlFileStore.Download content = null;
        try {
            var binding = recovery.downloadBinding(row);
            var fingerprint = new EtlFileStore.Fingerprint(row.getTemporaryFileKey(), row.getPayloadBytes(),
                    row.getPayloadHash());
            content = EtlFileStore.openPublished(binding, fingerprint);
            // 哈希计算可能耗时：发送任何响应头或字节之前，重新读取领域凭据并校验授权。
            access.requireUserPermission(actor, "dataforge:etl:query");
            var current = owned(id, actor);
            var currentRun = successful(current, actor);
            if (!sameReceipt(row, current) || !Objects.equals(run.getRowVersion(), currentRun.getRowVersion())
                    || !Objects.equals(run.getConfigJson(), currentRun.getConfigJson())
                    || !Objects.equals(run.getResourceIds(), currentRun.getResourceIds())) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            authorizeFrozenSource(current, currentRun, actor);
            content.verifyUnchanged();
            return new Download(row.getFileName(), content);
        } catch (IOException | IllegalArgumentException | NullPointerException unverified) {
            closeQuietly(content);
            // 对外记录的服务异常不得保留含路径的文件系统或解析器异常原因。
            throw exception(ETL_FILE_STATE_CHANGED);
        } catch (RuntimeException denied) {
            closeQuietly(content);
            throw denied;
        }
    }

    /**
     * 构造绑定当前执行权的状态筛选或更新条件。
     *
     * @param id 记录编号
     * @param actor 操作人编号
     * @return ETL 文件输出
     */
    private EtlFileOutputDO owned(Long id, Long actor) {
        var row = files.selectById(id);
        if (row == null || !Objects.equals(row.getId(), id) || !Objects.equals(row.getOwnerId(), actor)) {
            throw exception(ETL_FILE_NOT_OWNER);
        }
        if (!"KEEP".equals(row.getRetentionStatus()) || !"PUBLISHED".equals(row.getState()) || !"DONE".equals(
                row.getRecoveryStatus())
                || !Boolean.TRUE.equals(row.getPublicationApproved()) || row.getRevision() == null
                || row.getPayloadBytes() == null || row.getTemporaryFileKey() == null || row.getPayloadHash() == null) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        return row;
    }

    /**
     * 获取ETL 文件下载的成功。
     *
     * @param row 当前行数据
     * @param actor 操作人编号
     * @return ETL 运行记录
     */
    private EtlRecordDO successful(EtlFileOutputDO row, Long actor) {
        var run = records.selectById(row.getRecordId());
        return requireSuccessful(row, run, actor);
    }

    /** 共享领域准入规则：退役绝不能将未完成或仍在执行的运行转为成功发布。 */
    static EtlRecordDO requireSuccessful(EtlFileOutputDO row, EtlRecordDO run, Long actor) {
        if (run == null || !Objects.equals(run.getExecutionUserId(), actor)
                || !Objects.equals(run.getPipelineId(), row.getPipelineId())) {
            throw exception(ETL_FILE_NOT_OWNER);
        }
        // 租约未关闭、执行副作用未完整复核时，成功状态不足以证明可发布。
        if (!Objects.equals(run.getProtocolVersion(), 1) || !Objects.equals(run.getRunStatus(),
                EtlRecordDO.RUN_STATUS_SUCCESS)
                || !"COMPLETE".equals(run.getEffectState()) || !Boolean.FALSE.equals(run.getReviewRequired())
                || !Boolean.FALSE.equals(
                run.getOccupiesSlot()) || run.getExecutionToken() != null || run.getLeaseUntil() != null
                || run.getResourceIds() == null || run.getConfigJson() == null || run.getRowVersion() == null) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
        return run;
    }

    /**
     * 授权冻结来源。
     *
     * @param row 当前行数据
     * @param run 运行
     * @param actor 操作人编号
     */
    private void authorizeFrozenSource(EtlFileOutputDO row, EtlRecordDO run, Long actor) {
        try {
            EtlFileStore.validateName(row.getFileName());
            if (row.getFileName().codePoints().anyMatch(Character::isISOControl)) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            var snapshot = JSONUtil.parseObj(run.getConfigJson());
            if (!snapshot.containsKey("executionNodes") || snapshot.get("executionNodes") == null
                    || !snapshot.containsKey("parameterValues") || snapshot.get("parameterValues") == null) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            // 解码时不使用 JsonUtils 的错误日志；损坏的快照可能包含私有数据行或配置值。
            List<Map<String, Object>> definitions = new ArrayList<>();
            for (Object item : snapshot.getJSONArray("executionNodes")) {
                if (!(item instanceof cn.hutool.json.JSONObject node)) {
                    throw exception(ETL_FILE_STATE_CHANGED);
                }
                definitions.add(new LinkedHashMap<>(node));
            }
            var nodes = EtlGraphConverter.toNodeDefs(definitions);
            var values = EtlRunParameterResolver.resolve(snapshot.getJSONArray("parameters"),
                    snapshot.getJSONObject("parameterValues"));
            var output = nodes.stream().filter(node -> Objects.equals(node.getNo(), row.getNodeNo())).toList();
            if (output.size() != 1 || !FILE_TOOLS.contains(output.getFirst().getToolCode())) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            var config = JSONUtil.parseObj(new EtlVariableContext(values).resolveJson(output.getFirst().getNodeJson()));
            if (!Objects.equals(config.getStr("filePath"), "etl://" + row.getStorageKey())
                    || !Objects.equals(config.getStr("fileName"), row.getFileName())) {
                throw exception(ETL_FILE_STATE_CHANGED);
            }
            var reads = nodes.stream().filter(node -> "read-db".equals(node.getToolCode())).toList();
            nodeAccess.authorize(reads, actor, values);
            if (!reads.isEmpty()) {
                List<Map<String, Object>> edges = new ArrayList<>();
                var items = snapshot.getJSONArray("executionEdges");
                if (items == null) {
                    throw exception(ETL_FILE_STATE_CHANGED);
                }
                for (Object item : items) {
                    if (!(item instanceof cn.hutool.json.JSONObject edge)) {
                        throw exception(ETL_FILE_STATE_CHANGED);
                    }
                    edges.add(new LinkedHashMap<>(edge));
                }
                sourceProof.verify(run, cn.iocoder.yudao.framework.etl.core.model.EtlDag.build(nodes,
                        EtlGraphConverter.toEdgeDefs(edges)), values);
            }
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException denied) {
            throw denied;
        } catch (RuntimeException unsafe) {
            throw exception(ETL_FILE_STATE_CHANGED);
        }
    }

    /**
     * 判断是否一致回执条件。
     *
     * @param first 首个
     * @param second 秒数
     * @return 判断结果
     */
    private static boolean sameReceipt(EtlFileOutputDO first, EtlFileOutputDO second) {
        return Objects.equals(first.getRevision(), second.getRevision()) && Objects.equals(first.getRecordId(),
                second.getRecordId())
                && Objects.equals(first.getNodeNo(), second.getNodeNo()) && Objects.equals(first.getOperationKey(),
                second.getOperationKey())
                && Objects.equals(first.getStorageKey(), second.getStorageKey()) && Objects.equals(
                first.getStorageIdentity(), second.getStorageIdentity())
                && Objects.equals(first.getFileName(), second.getFileName()) && Objects.equals(
                first.getTemporaryFileKey(), second.getTemporaryFileKey())
                && Objects.equals(first.getPayloadHash(), second.getPayloadHash()) && Objects.equals(
                first.getPayloadBytes(), second.getPayloadBytes())
                && Objects.equals(first.getTemporaryResourcesJson(), second.getTemporaryResourcesJson());
    }

    /**
     * 关闭持有的资源，并忽略清理阶段的关闭异常。
     *
     * @param content 内容
     */
    private static void closeQuietly(EtlFileStore.Download content) {
        if (content != null) {
            try {
                content.close();
            } catch (IOException ignored) { /* 流已关闭也不能放宽准入校验。 */ }
        }
    }
}
