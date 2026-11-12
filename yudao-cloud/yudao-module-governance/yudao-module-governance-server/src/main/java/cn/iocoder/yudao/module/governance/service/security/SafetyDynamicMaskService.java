package cn.iocoder.yudao.module.governance.service.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskRespDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyFieldMaskReqDTO;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyMaskExemptRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyMaskSceneDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyMaskSceneMapper;
import cn.iocoder.yudao.module.governance.enums.security.SafetyMaskSceneEnum;
import cn.iocoder.yudao.module.governance.service.accesslog.SafetyAccessLogService;
import cn.iocoder.yudao.module.governance.service.rule.SafetyMaskEngine;
import cn.iocoder.yudao.module.governance.service.security.SafetyColumnClassificationResolver.ColumnKey;
import cn.iocoder.yudao.module.governance.service.security.SafetyColumnClassificationResolver.Resolution;
import cn.iocoder.yudao.module.governance.service.security.SafetyColumnClassificationResolver.TableKey;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyFieldMaskService;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyWhitelistService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.SAFETY_MASK_INVALID;

/**
 * 动态脱敏：在查询结果返回给用户前，按字段分类、白名单豁免与场景开关处理数据。
 * <p>
 * 策略复用 {@link SafetyFieldMaskService}（应用场景 "1"），分类来自 {@link SafetyColumnClassificationResolver}。
 * 失败关闭：未分类敏感列、来源无法追溯且涉及敏感表的列、豁免或规则无法核验的列，一律用不可逆掩码替换，不返回原值。
 * 只有白名单的全局豁免可以跳过失败关闭。调用方仍须先完成对象与字段读取授权。
 */
@Slf4j
@Service
public class SafetyDynamicMaskService {

    /** 动态脱敏对应的规则应用场景。 */
    static final String APPLICATION_SCENE_DYNAMIC = "1";
    /** 单元格文本上限；超长值不参与原值返回判定之外的处理，按失败关闭截断后掩码。 */
    private static final int MAX_CELL_CHARS = 65_536;

    @Resource
    private SafetyMaskSceneMapper sceneMapper;
    @Resource
    private SafetyColumnClassificationResolver classificationResolver;
    @Resource
    private SafetyFieldMaskService fieldMaskService;
    @Resource
    private SafetyWhitelistService whitelistService;
    @Resource
    private SafetyCategoryMapper categoryMapper;
    @Resource
    private SafetyAccessLogService accessLogService;

    /**
     * 处理查询结果。
     */
    public SafetyDynamicMaskRespDTO maskRows(SafetyDynamicMaskReqDTO req) {
        SafetyMaskSceneEnum scene = SafetyMaskSceneEnum.of(req.getScene());
        if (scene == null) {
            throw exception(SAFETY_MASK_INVALID, "未知的脱敏场景");
        }
        validateShape(req);
        SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
        resp.setEnabled(enabled(scene));
        if (!resp.getEnabled()) {
            resp.setRows(copy(req.getRows()));
            return resp;
        }
        Decision decision = decide(req.getUserId(), scene, req.getColumns());
        List<Map<String, Object>> rows = decision.policy() == null ? copy(req.getRows())
                : fieldMaskService.apply(decision.policy(), req.getRows());
        if (!decision.failClosed().isEmpty() && !decision.globallyExempt()) {
            for (Map<String, Object> row : rows) {
                for (String column : decision.failClosed().keySet()) {
                    if (row.containsKey(column)) {
                        row.put(column, SafetyMaskEngine.unavailableMask(row.get(column)));
                    }
                }
            }
        }
        resp.setRows(rows);
        resp.setMaskedColumns(decision.maskedColumns());
        resp.setPolicyFingerprint(decision.policy() == null ? null : decision.policy().fingerprint());
        audit(req, scene, resp, decision.revealedColumns());
        return resp;
    }

    /**
     * 持久化前脱敏：结果会被保存并展示给多个用户（如数据探查的 Top 值），没有确定的查看者。
     * <p>
     * 不看场景开关、不看白名单豁免：只要列来源命中分类、被标记敏感，或来源无法追溯且涉及敏感表，
     * 就用不可逆掩码整体替换。分类无法解析时由调用方异常处理（失败关闭，不保存取值）。不写访问审计。
     *
     * @param columns 输出列与物理来源，规则同 {@link #maskRows}
     * @param rows    待保存的行；不会被修改
     */
    public SafetyDynamicMaskRespDTO maskForStorage(List<SafetyDynamicMaskReqDTO.Column> columns,
                                                   List<Map<String, Object>> rows) {
        SafetyDynamicMaskReqDTO shape = new SafetyDynamicMaskReqDTO();
        shape.setColumns(columns);
        shape.setRows(rows);
        validateShape(shape);
        Set<ColumnKey> columnKeys = new LinkedHashSet<>();
        Set<TableKey> tableKeys = new LinkedHashSet<>();
        for (SafetyDynamicMaskReqDTO.Column column : columns) {
            column.getSources().forEach(source -> columnKeys.add(columnKey(source)));
            column.getCandidateTables().forEach(table -> tableKeys.add(
                    TableKey.of(table.getDatasourceId(), table.getSchemaName(), table.getTableName())));
        }
        Resolution resolution = classificationResolver.resolve(columnKeys, tableKeys);
        Map<String, String> reasons = new LinkedHashMap<>();
        Map<String, Set<Long>> categoriesByColumn = new LinkedHashMap<>();
        for (SafetyDynamicMaskReqDTO.Column column : columns) {
            String name = column.getOutputName();
            if (Boolean.FALSE.equals(column.getTraceable())) {
                boolean risky = column.getCandidateTables().isEmpty() || column.getCandidateTables().stream()
                        .anyMatch(table -> resolution.sensitive(TableKey.of(table.getDatasourceId(),
                                table.getSchemaName(), table.getTableName())));
                if (risky) {
                    reasons.put(name, SafetyDynamicMaskRespDTO.REASON_UNTRACEABLE);
                }
                continue;
            }
            Set<Long> categoryIds = new LinkedHashSet<>();
            boolean unclassified = false;
            for (SafetyDynamicMaskReqDTO.Source source : column.getSources()) {
                var classification = resolution.of(columnKey(source));
                categoryIds.addAll(classification.categoryIds());
                unclassified = unclassified || classification.unclassifiedSensitive();
            }
            if (!categoryIds.isEmpty()) {
                categoriesByColumn.put(name, categoryIds);
                reasons.put(name, SafetyDynamicMaskRespDTO.REASON_RULE);
            }
            if (unclassified) {
                reasons.put(name, SafetyDynamicMaskRespDTO.REASON_UNCLASSIFIED);
            }
        }
        List<Map<String, Object>> masked = copy(rows);
        for (Map<String, Object> row : masked) {
            for (String column : reasons.keySet()) {
                if (row.containsKey(column)) {
                    row.put(column, SafetyMaskEngine.unavailableMask(row.get(column)));
                }
            }
        }
        // 复用汇总逻辑：没有策略、没有豁免，所有原因都进入被脱敏列。
        SafetyDynamicMaskRespDTO resp = new SafetyDynamicMaskRespDTO();
        resp.setEnabled(true);
        resp.setRows(masked);
        resp.setMaskedColumns(maskedColumns(null, categoriesByColumn, reasons, false));
        return resp;
    }

    /**
     * 列级决策，不处理数据；供管理端"策略预览"与 {@link #maskRows} 共用。
     */
    public Decision decide(Long userId, SafetyMaskSceneEnum scene, List<SafetyDynamicMaskReqDTO.Column> columns) {
        Set<ColumnKey> columnKeys = new LinkedHashSet<>();
        Set<TableKey> tableKeys = new LinkedHashSet<>();
        for (SafetyDynamicMaskReqDTO.Column column : columns) {
            for (SafetyDynamicMaskReqDTO.Source source : column.getSources()) {
                columnKeys.add(columnKey(source));
            }
            for (SafetyDynamicMaskReqDTO.Source source : column.getCandidateTables()) {
                tableKeys.add(TableKey.of(source.getDatasourceId(), source.getSchemaName(), source.getTableName()));
            }
        }
        Resolution resolution = classificationResolver.resolve(columnKeys, tableKeys);

        List<SafetyFieldMaskReqDTO.Field> fields = new ArrayList<>();
        Map<String, String> failClosed = new LinkedHashMap<>();
        Map<String, Set<Long>> categoriesByColumn = new LinkedHashMap<>();
        for (SafetyDynamicMaskReqDTO.Column column : columns) {
            String name = column.getOutputName();
            if (!Boolean.FALSE.equals(column.getTraceable())) {
                Set<Long> categoryIds = new LinkedHashSet<>();
                boolean unclassified = false;
                for (SafetyDynamicMaskReqDTO.Source source : column.getSources()) {
                    var classification = resolution.of(columnKey(source));
                    categoryIds.addAll(classification.categoryIds());
                    unclassified = unclassified || classification.unclassifiedSensitive();
                }
                if (unclassified) {
                    failClosed.put(name, SafetyDynamicMaskRespDTO.REASON_UNCLASSIFIED);
                }
                if (!categoryIds.isEmpty()) {
                    SafetyFieldMaskReqDTO.Field field = new SafetyFieldMaskReqDTO.Field();
                    field.setFieldName(name);
                    field.setCategoryIds(new ArrayList<>(categoryIds));
                    fields.add(field);
                    categoriesByColumn.put(name, categoryIds);
                }
                continue;
            }
            // 来源无法追溯：候选表任一含敏感列（或连候选表都没有）就失败关闭。
            boolean risky = column.getCandidateTables().isEmpty() || column.getCandidateTables().stream()
                    .anyMatch(table -> resolution.sensitive(TableKey.of(table.getDatasourceId(),
                            table.getSchemaName(), table.getTableName())));
            if (risky) {
                failClosed.put(name, SafetyDynamicMaskRespDTO.REASON_UNTRACEABLE);
            }
        }

        SafetyFieldMaskService.Policy policy = null;
        String exemptScene = whitelistScene(scene);
        if (!fields.isEmpty()) {
            try {
                policy = fieldMaskService.prepare(userId, exemptScene, fields, APPLICATION_SCENE_DYNAMIC);
            } catch (ServiceException unverifiable) {
                // 豁免、分类或规则版本无法核验：全部分类列失败关闭。
                log.warn("[decide][脱敏策略无法核验，失败关闭 scene={} code={}]", scene, unverifiable.getCode());
                fields.forEach(field -> failClosed.put(field.getFieldName(), SafetyDynamicMaskRespDTO.REASON_UNVERIFIED));
            }
        }
        boolean globallyExempt = !failClosed.isEmpty() && globallyExempt(userId, exemptScene);
        Set<String> sensitive = new LinkedHashSet<>(categoriesByColumn.keySet());
        sensitive.addAll(failClosed.keySet());
        return new Decision(policy, failClosed, globallyExempt,
                maskedColumns(policy, categoriesByColumn, failClosed, globallyExempt), List.copyOf(sensitive));
    }

    /**
     * 列级决策结果。
     *
     * @param policy         分类脱敏策略；没有分类列或无法核验时为 null
     * @param failClosed     失败关闭的列及原因
     * @param globallyExempt 用户在该场景下是否全局豁免（只有全局豁免才跳过失败关闭）
     * @param maskedColumns  最终会被脱敏的列
     * @param sensitiveColumns 命中分类或失败关闭条件的全部列；其中未被脱敏的就是因豁免看到原值的列
     */
    public record Decision(SafetyFieldMaskService.Policy policy, Map<String, String> failClosed,
                           boolean globallyExempt, List<SafetyDynamicMaskRespDTO.MaskedColumn> maskedColumns,
                           List<String> sensitiveColumns) {

        /**
         * 因白名单豁免而返回原值的敏感列。
         */
        public List<String> revealedColumns() {
            Set<String> masked = new LinkedHashSet<>();
            maskedColumns.forEach(column -> masked.add(column.getOutputName()));
            return sensitiveColumns.stream().filter(column -> !masked.contains(column)).toList();
        }
    }

    /**
     * 场景是否启用；没有配置行时按启用处理。
     */
    public boolean enabled(SafetyMaskSceneEnum scene) {
        SafetyMaskSceneDO row = sceneMapper.selectByScene(scene.name());
        return row == null || !Boolean.FALSE.equals(row.getEnabled());
    }

    /**
     * 场景生效的白名单豁免口径：优先取管理端配置，配置缺失或不合法时取场景默认值。
     */
    public String whitelistScene(SafetyMaskSceneEnum scene) {
        SafetyMaskSceneDO row = sceneMapper.selectByScene(scene.name());
        String configured = row == null ? null : row.getWhitelistScene();
        return configured != null && SafetyMaskSceneEnum.WHITELIST_SCENES.contains(configured)
                ? configured : scene.getWhitelistScene();
    }

    // ==================== 内部 ====================

    /**
     * 汇总最终会被脱敏的列。
     */
    private List<SafetyDynamicMaskRespDTO.MaskedColumn> maskedColumns(SafetyFieldMaskService.Policy policy,
            Map<String, Set<Long>> categoriesByColumn, Map<String, String> failClosed, boolean globallyExempt) {
        Set<Long> allIds = new LinkedHashSet<>();
        categoriesByColumn.values().forEach(allIds::addAll);
        Map<Long, String> names = new HashMap<>();
        if (!allIds.isEmpty()) {
            for (SafetyCategoryDO category : categoryMapper.selectByIds(allIds)) {
                names.put(category.getId(), category.getName());
            }
        }
        Map<String, SafetyDynamicMaskRespDTO.MaskedColumn> result = new LinkedHashMap<>();
        if (policy != null) {
            categoriesByColumn.forEach((column, ids) -> {
                List<String> effective = ids.stream()
                        .filter(id -> !policy.exempt().contains(id) && !policy.ignored().contains(id))
                        .map(id -> names.getOrDefault(id, String.valueOf(id))).toList();
                if (!effective.isEmpty()) {
                    result.put(column, masked(column, SafetyDynamicMaskRespDTO.REASON_RULE, effective));
                }
            });
        }
        if (!globallyExempt) {
            failClosed.forEach((column, reason) -> {
                List<String> categories = categoriesByColumn.getOrDefault(column, Set.of()).stream()
                        .map(id -> names.getOrDefault(id, String.valueOf(id))).toList();
                result.put(column, masked(column, reason, categories));
            });
        }
        return new ArrayList<>(result.values());
    }

    /**
     * 构造被脱敏列描述。
     */
    private static SafetyDynamicMaskRespDTO.MaskedColumn masked(String column, String reason, List<String> categories) {
        SafetyDynamicMaskRespDTO.MaskedColumn masked = new SafetyDynamicMaskRespDTO.MaskedColumn();
        masked.setOutputName(column);
        masked.setReason(reason);
        masked.setCategoryNames(new ArrayList<>(categories));
        return masked;
    }

    /**
     * 是否全局豁免；无法核验时视为不豁免。
     */
    private boolean globallyExempt(Long userId, String exemptScene) {
        if (userId == null) {
            return false;
        }
        try {
            SafetyMaskExemptRespDTO exempt = whitelistService.getMaskExemptStrict(userId, exemptScene);
            return exempt != null && Boolean.TRUE.equals(exempt.getExempt());
        } catch (ServiceException unverifiable) {
            return false;
        }
    }

    /**
     * 校验请求形状：列名唯一、行宽有界、单元格有界。
     */
    private static void validateShape(SafetyDynamicMaskReqDTO req) {
        Set<String> names = new LinkedHashSet<>();
        for (SafetyDynamicMaskReqDTO.Column column : req.getColumns()) {
            if (!names.add(column.getOutputName())) {
                throw exception(SAFETY_MASK_INVALID, "输出列重复");
            }
        }
        for (Map<String, Object> row : req.getRows()) {
            if (row.size() > SafetyDynamicMaskReqDTO.MAX_COLUMNS) {
                throw exception(SAFETY_MASK_INVALID, "数据列超过有界大小");
            }
            for (Object value : row.values()) {
                if (value instanceof CharSequence text && text.length() > MAX_CELL_CHARS) {
                    throw exception(SAFETY_MASK_INVALID, "单元格超过有界大小");
                }
            }
        }
    }

    /**
     * 列键。
     */
    private static ColumnKey columnKey(SafetyDynamicMaskReqDTO.Source source) {
        return ColumnKey.of(source.getDatasourceId(), source.getSchemaName(), source.getTableName(),
                source.getColumnName());
    }

    /**
     * 复制行，避免修改调用方对象。
     */
    private static List<Map<String, Object>> copy(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copies = new ArrayList<>(rows.size());
        rows.forEach(row -> copies.add(new LinkedHashMap<>(row)));
        return copies;
    }

    /**
     * 访问审计：只记列名，不记原值。审计失败不影响返回。
     * <p>
     * 有敏感列因白名单豁免返回原值时记为明文访问（masked_flag=0，列出看到原值的列），
     * 否则有列被脱敏时记为已脱敏访问；两者都没有则不记。
     */
    private void audit(SafetyDynamicMaskReqDTO req, SafetyMaskSceneEnum scene, SafetyDynamicMaskRespDTO resp,
                       List<String> revealed) {
        boolean plaintext = !revealed.isEmpty();
        if (!plaintext && resp.getMaskedColumns().isEmpty()) {
            return;
        }
        try {
            SafetyAccessLogCreateReqDTO entry = new SafetyAccessLogCreateReqDTO();
            entry.setUserId(req.getUserId());
            entry.setAccessType(scene.getAccessType());
            entry.setResourceName(StrUtil.maxLength(StrUtil.blankToDefault(req.getResourceName(), scene.getLabel()), 200));
            SafetyDynamicMaskReqDTO.Source first = req.getColumns().stream()
                    .flatMap(column -> column.getSources().stream()).findFirst().orElse(null);
            if (first != null) {
                entry.setDatabaseId(first.getDatasourceId());
                entry.setTableName(first.getTableName());
            }
            List<String> columns = plaintext ? revealed : resp.getMaskedColumns().stream()
                    .map(SafetyDynamicMaskRespDTO.MaskedColumn::getOutputName).toList();
            entry.setSensitiveColumns(StrUtil.maxLength(String.join(",", columns), 1000));
            entry.setColumnCount(req.getColumns().size());
            entry.setMaskedFlag(plaintext ? 0 : 1);
            entry.setAccessTime(LocalDateTime.now());
            accessLogService.recordAccessLog(entry);
        } catch (RuntimeException failure) {
            log.warn("[audit][脱敏访问审计写入失败 scene={} failureType={}]", scene,
                    failure.getClass().getSimpleName());
        }
    }
}
