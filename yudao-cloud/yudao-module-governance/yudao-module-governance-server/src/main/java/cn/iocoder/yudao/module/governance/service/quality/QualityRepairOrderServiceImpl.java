package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedDmlPlan;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRepairOrderDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityCheckObjectMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRepairOrderMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据治理-数据质量-修复工单 Service 实现
 *
 * <p>审批绑定不可变内容快照；执行使用原子认领；远端结果未知时禁止自动重放。
 * 修复成功只保存执行证据，不关闭质量工单、不自动触发复检。
 */
@Service
@Validated
public class QualityRepairOrderServiceImpl implements QualityRepairOrderService {

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final int DEFAULT_MAX_AFFECTED_ROWS = 10_000;

    @Resource
    private QualityRepairOrderMapper repairOrderMapper;

    @Resource
    private QualityCheckObjectMapper checkObjectMapper;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 创建修复工单。
     */
    @Override
    public Long createRepairOrder(QualityRepairOrderSaveReqVO createReqVO) {
        QualityRepairOrderDO order = BeanUtils.toBean(createReqVO, QualityRepairOrderDO.class);
        if (StrUtil.isBlank(order.getRepairSql())) {
            order.setRepairSql(generateRepairSql(createReqVO));
        }
        validateRepairPlan(order.getRepairSql(),
                resolveAuthorizedTable(createReqVO.getObjectId(), createReqVO.getTableName()),
                resolveProduct(createReqVO.getObjectId()));
        order.setMaxAffectedRows(normalizeMaxRows(createReqVO.getMaxAffectedRows()));
        order.setApproveStatus(QualityRepairOrderDO.APPROVE_PENDING);
        order.setExecStatus(QualityRepairOrderDO.EXEC_PENDING);
        order.setVerifyStatus(QualityRepairOrderDO.VERIFY_UNVERIFIED);
        order.setContentVersion(0);
        repairOrderMapper.insert(order);
        return order.getId();
    }

    /**
     * 更新修复工单。
     */
    @Override
    public void updateRepairOrder(QualityRepairOrderSaveReqVO updateReqVO) {
        QualityRepairOrderDO exist = validateExists(updateReqVO.getId());
        if (QualityRepairOrderDO.EXEC_RUNNING.equals(exist.getExecStatus())
                || QualityRepairOrderDO.EXEC_UNKNOWN.equals(exist.getExecStatus())
                || QualityRepairOrderDO.EXEC_SUCCESS.equals(exist.getExecStatus())) {
            throw exception(QUALITY_REPAIR_STATUS_INVALID);
        }
        QualityRepairOrderDO order = BeanUtils.toBean(updateReqVO, QualityRepairOrderDO.class);
        if (StrUtil.isBlank(order.getRepairSql())) {
            order.setRepairSql(generateRepairSql(updateReqVO));
        }
        validateRepairPlan(order.getRepairSql(),
                resolveAuthorizedTable(updateReqVO.getObjectId(), updateReqVO.getTableName()),
                resolveProduct(updateReqVO.getObjectId()));
        order.setMaxAffectedRows(normalizeMaxRows(updateReqVO.getMaxAffectedRows()));
        boolean contentChanged = !Objects.equals(exist.getRepairSql(), order.getRepairSql())
                || !Objects.equals(exist.getRepairConfig(), order.getRepairConfig())
                || !Objects.equals(exist.getRepairType(), order.getRepairType())
                || !Objects.equals(exist.getObjectId(), order.getObjectId())
                || !Objects.equals(exist.getTableName(), order.getTableName())
                || !Objects.equals(exist.getMaxAffectedRows(), order.getMaxAffectedRows());
        if (contentChanged) {
            order.setApproveStatus(QualityRepairOrderDO.APPROVE_PENDING);
            order.setApprovedSql(null);
            order.setApprovedContentHash(null);
            order.setApprovedSnapshot(null);
            order.setApproveUserId(null);
            order.setApproveTime(null);
            order.setContentVersion((exist.getContentVersion() == null ? 0 : exist.getContentVersion()) + 1);
            order.setExecStatus(QualityRepairOrderDO.EXEC_PENDING);
        } else {
            order.setApproveStatus(exist.getApproveStatus());
            order.setExecStatus(exist.getExecStatus());
            order.setContentVersion(exist.getContentVersion());
        }
        order.setVerifyStatus(exist.getVerifyStatus());
        // 锁定本地审批记录之前，先解析远程数据源。
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            QualityRepairOrderDO current = lockOrder(exist.getId());
            if (!Objects.equals(exist.getContentVersion(), current.getContentVersion())
                    || !Objects.equals(contentHash(exist), contentHash(current))
                    || !Objects.equals(exist.getApproveStatus(), current.getApproveStatus())
                    || !Objects.equals(exist.getExecStatus(), current.getExecStatus())) {
                throw exception(QUALITY_REPAIR_STATUS_INVALID);
            }
            if (contentChanged) {
                repairOrderMapper.update(null,
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(QualityRepairOrderDO.class)
                                .eq(QualityRepairOrderDO::getId, exist.getId())
                                .set(QualityRepairOrderDO::getApprovedSql, null)
                                .set(QualityRepairOrderDO::getApprovedContentHash, null)
                                .set(QualityRepairOrderDO::getApprovedSnapshot, null)
                                .set(QualityRepairOrderDO::getApproveUserId, null)
                                .set(QualityRepairOrderDO::getApproveTime, null));
            }
            repairOrderMapper.updateById(order);
        });
    }

    /**
     * 删除修复工单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRepairOrder(Long id) {
        QualityRepairOrderDO exist = lockOrder(id);
        if (QualityRepairOrderDO.EXEC_RUNNING.equals(exist.getExecStatus())
                || QualityRepairOrderDO.EXEC_UNKNOWN.equals(exist.getExecStatus())
                || QualityRepairOrderDO.EXEC_SUCCESS.equals(exist.getExecStatus())) {
            throw exception(QUALITY_REPAIR_STATUS_INVALID);
        }
        repairOrderMapper.deleteById(id);
    }

    /**
     * 获得修复工单。
     */
    @Override
    public QualityRepairOrderDO getRepairOrder(Long id) {
        return repairOrderMapper.selectById(id);
    }

    /**
     * 获得修复工单分页。
     */
    @Override
    public PageResult<QualityRepairOrderDO> getRepairOrderPage(QualityRepairOrderPageReqVO pageReqVO) {
        return repairOrderMapper.selectPage(pageReqVO);
    }

    /**
     * 按修复配置生成 SQL 预览（不落库）
     */
    @Override
    public String previewRepairSql(QualityRepairOrderSaveReqVO reqVO) {
        String sql = StrUtil.isNotBlank(reqVO.getRepairSql()) ? reqVO.getRepairSql() : generateRepairSql(reqVO);
        validateRepairPlan(sql, resolveAuthorizedTable(reqVO.getObjectId(), reqVO.getTableName()),
                resolveProduct(reqVO.getObjectId()));
        return sql;
    }

    /**
     * 审批：通过/拒绝
     */
    @Override
    public void approveRepairOrder(Long id, String approveStatus) {
        QualityRepairOrderDO exist = validateExists(id);
        if (!QualityRepairOrderDO.APPROVE_PENDING.equals(exist.getApproveStatus())) {
            throw exception(QUALITY_REPAIR_STATUS_INVALID);
        }
        if (!QualityRepairOrderDO.APPROVE_PASSED.equals(approveStatus)
                && !QualityRepairOrderDO.APPROVE_REJECTED.equals(approveStatus)) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, "审批结果只允许 PASSED 或 REJECTED");
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId != null && Objects.equals(String.valueOf(userId), exist.getCreator())) {
            throw exception(QUALITY_REPAIR_SELF_APPROVE);
        }
        QualityRepairOrderDO update = new QualityRepairOrderDO();
        update.setId(id);
        update.setApproveStatus(approveStatus);
        update.setApproveUserId(userId);
        update.setApproveTime(LocalDateTime.now());
        RepairTarget target = null;
        if (QualityRepairOrderDO.APPROVE_PASSED.equals(approveStatus)) {
            target = resolveTarget(exist);
            validateBoundRepairPlan(exist.getRepairSql(), target);
            update.setApprovedSql(exist.getRepairSql());
            update.setApprovedContentHash(contentHash(exist));
            update.setApprovedSnapshot(snapshotJson(exist, target));
            update.setMaxAffectedRows(normalizeMaxRows(exist.getMaxAffectedRows()));
        }
        RepairTarget approvedTarget = target;
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            QualityRepairOrderDO locked = lockOrder(id);
            if (!QualityRepairOrderDO.APPROVE_PENDING.equals(locked.getApproveStatus())
                    || !Objects.equals(contentHash(exist), contentHash(locked))
                    || !Objects.equals(exist.getContentVersion(), locked.getContentVersion())) {
                throw exception(QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH);
            }
            if (approvedTarget != null) {
                assertObjectUnchanged(approvedTarget);
            }
            repairOrderMapper.updateById(update);
        });
    }

    /**
     * 内容或物理目标变更后，由编辑人员撤销旧批准并重新送审。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resubmitRepairOrder(Long id) {
        QualityRepairOrderDO current = lockOrder(id);
        if (!Set.of(QualityRepairOrderDO.EXEC_PENDING, QualityRepairOrderDO.EXEC_FAILED_CONFIRMED)
                .contains(current.getExecStatus())) {
            throw exception(QUALITY_REPAIR_STATUS_INVALID);
        }
        repairOrderMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(QualityRepairOrderDO.class)
                        .eq(QualityRepairOrderDO::getId, id)
                        .set(QualityRepairOrderDO::getApproveStatus, QualityRepairOrderDO.APPROVE_PENDING)
                        .set(QualityRepairOrderDO::getContentVersion,
                                current.getContentVersion() == null ? 1 : current.getContentVersion() + 1)
                        .set(QualityRepairOrderDO::getApprovedSql, null)
                        .set(QualityRepairOrderDO::getApprovedContentHash, null)
                        .set(QualityRepairOrderDO::getApprovedSnapshot, null)
                        .set(QualityRepairOrderDO::getApproveTime, null)
                        .set(QualityRepairOrderDO::getApproveUserId, null));
    }

    /**
     * 执行修复：真实回源执行修复 SQL，回写执行状态与结果
     */
    @Override
    public void executeRepairOrder(Long id) {
        QualityRepairOrderDO order = validateExists(id);
        if (QualityRepairOrderDO.EXEC_UNKNOWN.equals(order.getExecStatus())) {
            throw exception(QUALITY_REPAIR_UNKNOWN_NO_REPLAY);
        }
        if (QualityRepairOrderDO.EXEC_SUCCESS.equals(order.getExecStatus())
                || QualityRepairOrderDO.EXEC_RUNNING.equals(order.getExecStatus())) {
            throw exception(QUALITY_REPAIR_STATUS_INVALID);
        }
        if (!QualityRepairOrderDO.APPROVE_PASSED.equals(order.getApproveStatus())) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, "工单尚未审批通过，不能执行");
        }
        if (StrUtil.isBlank(order.getApprovedContentHash())
                || !Objects.equals(order.getApprovedContentHash(), contentHash(order))
                || !Objects.equals(order.getApprovedSql(), order.getRepairSql())) {
            throw exception(QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH);
        }
        RepairTarget target = resolveTarget(order);
        ProductTypeEnum product = target.connection().getProductType();
        RestrictedDmlPlan plan = validateBoundRepairPlan(order.getRepairSql(), target);
        if (!RestrictedDmlGuard.supportsTransactionalRowLimit(product)) {
            throw exception(QUALITY_REPAIR_PRODUCT_UNSUPPORTED, product == null ? "UNKNOWN" : product.name());
        }
        if (!Objects.equals(order.getApprovedSnapshot(), snapshotJson(order, target))) {
            throw exception(QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH);
        }
        String token = UUID.randomUUID().toString();
        String operationId = "repair-" + id + "-" + token;
        LocalDateTime leaseUntil = LocalDateTime.now().plusMinutes(30);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            QualityRepairOrderDO locked = lockOrder(id);
            if (!Objects.equals(order.getApprovedSnapshot(), locked.getApprovedSnapshot())
                    || !Objects.equals(contentHash(order), contentHash(locked))
                    || !Objects.equals(order.getContentVersion(), locked.getContentVersion())) {
                throw exception(QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH);
            }
            assertObjectUnchanged(target);
            if (repairOrderMapper.claimExecution(id, token, operationId, leaseUntil) == 0) {
                throw exception(QUALITY_REPAIR_STATUS_INVALID);
            }
        });
        String execStatus = QualityRepairOrderDO.EXEC_UNKNOWN;
        String execResult = "修复结果未知，需人工核对远端事务";
        boolean executed = false;
        try {
            // 使用与审批信息核对过的同一个连接，绝不再解析第二条可变路由。
            MasterDatabaseResolver.ConnectionInfo conn = target.connection();
            try (Connection connection = dbSwitchRuntime.openConnection(conn.getProductType(), null,
                    conn.getJdbcUrl(), conn.getUsername(), conn.getPassword())) {
                connection.setAutoCommit(false);
                if (conn.getProductType().isLikeMysql()) {
                    connection.setCatalog(conn.getSchema());
                } else {
                    connection.setSchema(conn.getSchema());
                }
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(300);
                    int affected = statement.executeUpdate(plan.getNormalizedSql());
                    executed = true;
                    int maxRows = normalizeMaxRows(order.getMaxAffectedRows());
                    if (affected > maxRows) {
                        connection.rollback();
                        execStatus = QualityRepairOrderDO.EXEC_FAILED_CONFIRMED;
                        execResult = "实际影响 " + affected + " 行，超过上限 " + maxRows + "，已回滚";
                    } else {
                        connection.commit();
                        execStatus = QualityRepairOrderDO.EXEC_SUCCESS;
                        execResult = "修复执行成功：影响 " + affected + " 行。该结果不是工单关闭依据";
                    }
                } catch (Exception ex) {
                    safeRollback(connection);
                    if (executed) {
                        execStatus = QualityRepairOrderDO.EXEC_UNKNOWN;
                        execResult = "修复执行后结果未知：" + StrUtil.maxLength(ex.getMessage(), 500);
                    } else {
                        execStatus = QualityRepairOrderDO.EXEC_FAILED_CONFIRMED;
                        execResult = "修复未提交：" + StrUtil.maxLength(ex.getMessage(), 500);
                    }
                }
            }
        } catch (Exception ex) {
            if (executed) {
                execStatus = QualityRepairOrderDO.EXEC_UNKNOWN;
                execResult = "修复执行后结果未知：" + StrUtil.maxLength(ex.getMessage(), 500);
            } else if (QualityRepairOrderDO.EXEC_UNKNOWN.equals(execStatus)
                    && !"修复结果未知，需人工核对远端事务".equals(execResult)) {
                // 保留
            } else if (!executed) {
                execStatus = QualityRepairOrderDO.EXEC_FAILED_CONFIRMED;
                execResult = "修复未提交：" + StrUtil.maxLength(ex.getMessage(), 500);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        if (repairOrderMapper.completeIfTokenMatches(id, token, execStatus, execResult, now) == 0) {
            throw exception(QUALITY_REPAIR_STATUS_INVALID);
        }
        if (QualityRepairOrderDO.EXEC_UNKNOWN.equals(execStatus)) {
            throw exception(QUALITY_REPAIR_UNKNOWN_NO_REPLAY);
        }
        if (QualityRepairOrderDO.EXEC_FAILED_CONFIRMED.equals(execStatus)
                && execResult != null && execResult.contains("超过上限")) {
            throw exception(QUALITY_REPAIR_ROW_LIMIT_EXCEEDED);
        }
    }

    /**
     * 验证：验证通过/验证未通过
     */
    @Override
    public void verifyRepairOrder(Long id, String verifyStatus) {
        QualityRepairOrderDO exist = validateExists(id);
        if (!QualityRepairOrderDO.VERIFY_PASSED.equals(verifyStatus)
                && !QualityRepairOrderDO.VERIFY_FAILED.equals(verifyStatus)) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, "验证结果只允许 PASSED 或 FAILED");
        }
        if (QualityRepairOrderDO.VERIFY_PASSED.equals(verifyStatus)
                && !QualityRepairOrderDO.EXEC_SUCCESS.equals(exist.getExecStatus())) {
            throw exception(QUALITY_REPAIR_STATUS_INVALID);
        }
        repairOrderMapper.updateById(QualityRepairOrderDO.builder()
                .id(id).verifyStatus(verifyStatus).build());
    }

    // ==================== 修复 SQL 生成 ====================

    /**
     * 按修复类型 + 配置 JSON 生成修复 SQL（与 DataQ 6 种修复类型一致）
     */
    @SuppressWarnings("unchecked")
    private String generateRepairSql(QualityRepairOrderSaveReqVO reqVO) {
        Map<String, Object> config = StrUtil.isBlank(reqVO.getRepairConfig())
                ? Map.of() : JsonUtils.parseObject(reqVO.getRepairConfig(), Map.class);
        String table = reqVO.getTableName();
        String targetField = StrUtil.blankToDefault(reqVO.getTargetField(), reqVO.getCheckField());
        if (!QualityRepairOrderDO.TYPE_CUSTOM_SQL.equals(reqVO.getRepairType())) {
            assertIdent(table, "表名");
            if (StrUtil.isNotBlank(targetField)) {
                assertIdent(targetField, "字段名");
            }
        }
        switch (StrUtil.nullToEmpty(reqVO.getRepairType())) {
            case QualityRepairOrderDO.TYPE_FILL_DEFAULT: {
                String defaultValue = str(config, "defaultValue");
                boolean includeBlank = Boolean.parseBoolean(str(config, "includeBlank"));
                String where = includeBlank
                        ? "(" + targetField + " IS NULL OR " + targetField + " = '')"
                        : targetField + " IS NULL";
                return "UPDATE " + table + " SET " + targetField + " = '" + escape(defaultValue)
                        + "' WHERE " + where;
            }
            case QualityRepairOrderDO.TYPE_DELETE_RECORD: {
                Object conditionsObj = config.get("conditions");
                String relation = StrUtil.blankToDefault(str(config, "relation"), "AND");
                StringBuilder where = new StringBuilder();
                if (conditionsObj instanceof List<?> conditions) {
                    for (Object item : conditions) {
                        if (!(item instanceof Map<?, ?> cond)) {
                            continue;
                        }
                        if (!where.isEmpty()) {
                            where.append(" ").append("OR".equalsIgnoreCase(relation) ? "OR" : "AND").append(" ");
                        }
                        String field = String.valueOf(cond.get("field"));
                        String operator = StrUtil.blankToDefault(String.valueOf(cond.get("operator")), "=");
                        assertIdent(field, "条件字段");
                        assertOperator(operator);
                        where.append(field).append(" ").append(operator)
                                .append(" '").append(escape(String.valueOf(cond.get("value")))).append("'");
                    }
                }
                if (where.isEmpty()) {
                    throw exception(QUALITY_REPAIR_SQL_INVALID, "删除异常记录必须配置删除条件");
                }
                return "DELETE FROM " + table + " WHERE " + where;
            }
            case QualityRepairOrderDO.TYPE_FORMAT_FIX: {
                String find = str(config, "find");
                String replace = str(config, "replace");
                return "UPDATE " + table + " SET " + targetField + " = REPLACE(" + targetField
                        + ", '" + escape(find) + "', '" + escape(replace) + "') WHERE " + targetField
                        + " LIKE '%" + escape(find) + "%'";
            }
            case QualityRepairOrderDO.TYPE_DEDUP: {
                String groupFields = str(config, "groupFields");
                String pkField = StrUtil.blankToDefault(str(config, "pkField"), "id");
                String keep = StrUtil.blankToDefault(str(config, "keepStrategy"), "FIRST");
                String aggFunc = "LAST".equalsIgnoreCase(keep) || "LATEST".equalsIgnoreCase(keep)
                        ? "MAX" : "MIN";
                if (StrUtil.isBlank(groupFields)) {
                    throw exception(QUALITY_REPAIR_SQL_INVALID, "去重清洗必须配置分组字段");
                }
                assertIdent(pkField, "主键字段");
                for (String part : groupFields.split(",")) {
                    assertIdent(part.trim(), "分组字段");
                }
                return "DELETE FROM " + table + " WHERE " + pkField + " NOT IN (SELECT " + aggFunc
                        + "(" + pkField + ") FROM " + table + " GROUP BY " + groupFields + ")";
            }
            case QualityRepairOrderDO.TYPE_STANDARD_MAP: {
                Object mappingsObj = config.get("mappings");
                StringBuilder caseWhen = new StringBuilder();
                StringBuilder inValues = new StringBuilder();
                if (mappingsObj instanceof List<?> mappings) {
                    for (Object item : mappings) {
                        if (!(item instanceof Map<?, ?> mapping)) {
                            continue;
                        }
                        String from = escape(String.valueOf(mapping.get("from")));
                        String to = escape(String.valueOf(mapping.get("to")));
                        caseWhen.append(" WHEN '").append(from).append("' THEN '").append(to).append("'");
                        if (!inValues.isEmpty()) {
                            inValues.append(", ");
                        }
                        inValues.append("'").append(from).append("'");
                    }
                }
                if (caseWhen.isEmpty()) {
                    throw exception(QUALITY_REPAIR_SQL_INVALID, "标准化映射必须配置映射规则");
                }
                return "UPDATE " + table + " SET " + targetField + " = CASE " + targetField
                        + caseWhen + " ELSE " + targetField + " END WHERE " + targetField
                        + " IN (" + inValues + ")";
            }
            case QualityRepairOrderDO.TYPE_CUSTOM_SQL: {
                String sql = str(config, "sql");
                if (StrUtil.isBlank(sql)) {
                    throw exception(QUALITY_REPAIR_SQL_INVALID, "自定义SQL不能为空");
                }
                return sql;
            }
            default:
                throw exception(QUALITY_REPAIR_SQL_INVALID, "不支持的修复类型：" + reqVO.getRepairType());
        }
    }

    /**
     * 校验质量修复工单的修复计划，不满足约束时拒绝当前操作。
     */
    private RestrictedDmlPlan validateRepairPlan(String sql, String authorizedTable, ProductTypeEnum product) {
        if (StrUtil.isBlank(sql)) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, "修复SQL不能为空");
        }
        ProductTypeEnum resolved = product == null ? ProductTypeEnum.POSTGRESQL : product;
        try {
            RestrictedDmlPlan plan = RestrictedDmlGuard.parse(resolved, sql);
            RestrictedDmlGuard.assertAuthorizedTable(plan, authorizedTable);
            return plan;
        } catch (UnsupportedOperationException ex) {
            throw exception(QUALITY_REPAIR_PRODUCT_UNSUPPORTED, resolved.name());
        } catch (IllegalArgumentException ex) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, ex.getMessage());
        }
    }

    /**
     * 校验质量修复工单的已绑定修复计划，不满足约束时拒绝当前操作。
     */
    private RestrictedDmlPlan validateBoundRepairPlan(String sql, RepairTarget target) {
        ProductTypeEnum product = target.connection().getProductType();
        if (!RestrictedDmlGuard.supportsTransactionalRowLimit(product)) {
            throw exception(QUALITY_REPAIR_PRODUCT_UNSUPPORTED, String.valueOf(product));
        }
        try {
            return RestrictedDmlGuard.bindAuthorizedTable(dbSwitchRuntime.products().metadata(product), sql,
                    target.connection().getSchema(), target.object().getTableName());
        } catch (IllegalArgumentException ex) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, ex.getMessage());
        }
    }

    /**
     * 解析质量修复工单的已授权表。
     */
    private String resolveAuthorizedTable(Long objectId, String fallbackTable) {
        if (objectId != null) {
            QualityCheckObjectDO object = checkObjectMapper.selectById(objectId);
            if (object == null) {
                throw exception(QUALITY_CHECK_OBJECT_NOT_EXISTS);
            }
            if (StrUtil.isNotBlank(object.getTableName())) {
                return object.getTableName();
            }
        }
        if (StrUtil.isBlank(fallbackTable)) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, "修复目标表未授权");
        }
        return fallbackTable;
    }

    /**
     * 解析质量修复工单的数据库产品。
     */
    private ProductTypeEnum resolveProduct(Long objectId) {
        if (objectId == null) {
            return ProductTypeEnum.POSTGRESQL;
        }
        QualityCheckObjectDO object = checkObjectMapper.selectById(objectId);
        if (object == null || object.getDatasourceConnId() == null) {
            throw exception(QUALITY_CHECK_OBJECT_NOT_EXISTS);
        }
        return databaseResolver.resolve(object.getDatasourceConnId(), QUALITY_CHECK_OBJECT_NOT_EXISTS).getProductType();
    }

    /**
     * 对修复工单执行内容生成稳定摘要。
     */
    private String contentHash(QualityRepairOrderDO order) {
        return DigestUtil.sha256Hex(String.join("|",
                String.valueOf(order.getObjectId()),
                StrUtil.nullToEmpty(order.getTableName()),
                StrUtil.nullToEmpty(order.getRepairType()),
                StrUtil.nullToEmpty(order.getRepairSql()),
                StrUtil.nullToEmpty(order.getRepairConfig()),
                String.valueOf(normalizeMaxRows(order.getMaxAffectedRows()))));
    }

    private record RepairTarget(QualityCheckObjectDO object, MasterDatabaseResolver.ConnectionInfo connection) {

    }

    /**
     * 解析质量修复工单的目标。
     */
    private RepairTarget resolveTarget(QualityRepairOrderDO order) {
        QualityCheckObjectDO object = order.getObjectId() == null ? null : checkObjectMapper.selectById(
                order.getObjectId());
        if (object == null || object.getDatasourceConnId() == null) {
            throw exception(QUALITY_CHECK_OBJECT_NOT_EXISTS);
        }
        MasterDatabaseResolver.ConnectionInfo connection = databaseResolver.resolve(
                object.getDatasourceConnId(), QUALITY_CHECK_OBJECT_NOT_EXISTS);
        if (StrUtil.isBlank(connection.getConfigurationRevision()) || StrUtil.isBlank(connection.getSchema())) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, "审批要求已验证的数据源配置修订及明确物理 schema");
        }
        return new RepairTarget(object, connection);
    }

    /**
     * 校验质量修复工单的对象未变化，不满足约束时拒绝当前操作。
     */
    private void assertObjectUnchanged(RepairTarget target) {
        QualityCheckObjectDO current = checkObjectMapper.selectOne(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<QualityCheckObjectDO>()
                        .eq(QualityCheckObjectDO::getId, target.object().getId()).last("FOR UPDATE"));
        if (current == null || !Objects.equals(current.getDatasourceConnId(), target.object().getDatasourceConnId())
                || !Objects.equals(current.getTableName(), target.object().getTableName())
                || !Objects.equals(current.getTableId(), target.object().getTableId())) {
            throw exception(QUALITY_REPAIR_APPROVAL_CONTENT_MISMATCH);
        }
    }

    /**
     * 将修复计划和授权目标序列化为冻结快照。
     */
    private String snapshotJson(QualityRepairOrderDO order, RepairTarget target) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 2);
        snapshot.put("objectId", order.getObjectId());
        snapshot.put("datasourceId", target.object().getDatasourceConnId());
        snapshot.put("metadataTableId", target.object().getTableId());
        snapshot.put("schemaName", target.connection().getSchema());
        snapshot.put("tableName", target.object().getTableName());
        snapshot.put("productType", target.connection().getProductType().name());
        snapshot.put("configurationRevision", target.connection().getConfigurationRevision());
        snapshot.put("repairType", order.getRepairType());
        snapshot.put("repairSql", order.getRepairSql());
        snapshot.put("maxAffectedRows", normalizeMaxRows(order.getMaxAffectedRows()));
        snapshot.put("contentVersion", order.getContentVersion() == null ? 0 : order.getContentVersion());
        return JsonUtils.toJsonString(snapshot);
    }

    /**
     * 规范化质量修复工单的上限数据行。
     */
    private int normalizeMaxRows(Integer maxAffectedRows) {
        if (maxAffectedRows == null || maxAffectedRows <= 0) {
            return DEFAULT_MAX_AFFECTED_ROWS;
        }
        return Math.min(maxAffectedRows, 1_000_000);
    }

    /**
     * 校验质量修复工单的标识符，不满足约束时拒绝当前操作。
     */
    private void assertIdent(String name, String label) {
        if (StrUtil.isBlank(name) || !IDENT.matcher(name).matches()) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, label + "不是合法标识符");
        }
    }

    /**
     * 校验质量修复工单的运算符，不满足约束时拒绝当前操作。
     */
    private void assertOperator(String operator) {
        if (!Set.of("=", "!=", "<>", ">", "<", ">=", "<=", "LIKE").contains(operator.toUpperCase())) {
            throw exception(QUALITY_REPAIR_SQL_INVALID, "条件运算符不在允许范围");
        }
    }

    /**
     * 尝试回滚修复事务并处理回滚异常。
     */
    private void safeRollback(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // 回滚失败时上层按 UNKNOWN 处理
        }
    }

    /**
     * 锁定工单。
     */
    private QualityRepairOrderDO lockOrder(Long id) {
        QualityRepairOrderDO order = id == null ? null : repairOrderMapper.selectForUpdate(id);
        if (order == null) {
            throw exception(QUALITY_REPAIR_ORDER_NOT_EXISTS);
        }
        return order;
    }

    /**
     * 将输入转换为字符串表示。
     */
    private String str(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 按修复 SQL 生成约定转义文本值。
     */
    private String escape(String value) {
        return StrUtil.nullToEmpty(value).replace("'", "''");
    }

    /**
     * 读取并校验质量修复工单记录存在，不存在时抛出业务异常。
     */
    private QualityRepairOrderDO validateExists(Long id) {
        QualityRepairOrderDO order = id == null ? null : repairOrderMapper.selectById(id);
        if (order == null) {
            throw exception(QUALITY_REPAIR_ORDER_NOT_EXISTS);
        }
        return order;
    }

}
