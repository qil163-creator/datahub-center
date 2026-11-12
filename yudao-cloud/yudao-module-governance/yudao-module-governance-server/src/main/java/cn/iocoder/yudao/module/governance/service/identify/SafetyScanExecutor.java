package cn.iocoder.yudao.module.governance.service.identify;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyScanRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyFieldTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyScanRunMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 敏感数据扫描执行器：
 * 遍历数据源下的表字段，按启用中的识别规则匹配（字段名正则 / 注释关键词 / 数据内容采样正则），
 * 命中的字段生成"待确认"打标记录，由人工在打标结果页复核后回写元数据。
 *
 * 参照 {@link cn.iocoder.yudao.module.governance.service.quality.QualityTaskExecutor} 的
 * 异步提交和运行中防并发模式。
 *
 * @author Li
 */
@Slf4j
@Component
public class SafetyScanExecutor {

    private static final int MAX_SAMPLE_ROWS = 100;

    @Resource
    private SafetyScanRunMapper runMapper;

    @Resource
    private SafetyFieldTagMapper tagMapper;

    @Resource
    private SafetyCategoryMapper categoryMapper;

    @Resource
    private MetaTableMapper metaTableMapper;

    @Resource
    private MetaColumnMapper metaColumnMapper;

    @Resource
    private DatabaseApi databaseApi;

    @Resource
    private cn.iocoder.yudao.module.governance.service.accesslog.SafetyAccessLogService accessLogService;

    private ExecutorService executor;

    /**
     * 运行中的扫描注册表，key 为数据源 id（单实例部署内有效）
     */
    private final ConcurrentMap<Long, Boolean> runningDatabases = new ConcurrentHashMap<>();

    /**
     * 初始化安全扫描使用的运行资源。
     */
    @PostConstruct
    public void init() {
        AtomicInteger counter = new AtomicInteger();
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "safety-scan-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 释放安全扫描持有的运行资源。
     */
    @PreDestroy
    public void destroy() {
        executor.shutdownNow();
    }

    /**
     * 判断安全扫描是否处于运行状态。
     */
    public boolean isRunning(Long databaseId) {
        return runningDatabases.containsKey(databaseId);
    }

    /**
     * 提交扫描（异步）
     *
     * @return 扫描记录编号；数据源已有进行中的扫描时返回 null
     */
    public Long submit(Long databaseId, List<String> tableNames, List<SafetyIdentifyRuleDO> rules) {
        if (runningDatabases.putIfAbsent(databaseId, Boolean.TRUE) != null) {
            return null;
        }
        SafetyScanRunDO run = SafetyScanRunDO.builder()
                .databaseId(databaseId)
                .tableScope(CollUtil.isEmpty(tableNames) ? null : String.join(",", tableNames))
                .runStatus(SafetyScanRunDO.RUN_STATUS_RUNNING)
                .tableCount(0).columnCount(0).hitCount(0)
                .startTime(LocalDateTime.now())
                .build();
        try {
            runMapper.insert(run);

            executor.submit(() -> {

                try {

                    execute(run.getId(), databaseId, tableNames, rules);
                } finally {

                    runningDatabases.remove(databaseId);
                }
            });
            return run.getId();
        } catch (Exception ex) {
            runningDatabases.remove(databaseId);
            throw ex;
        }
    }

    /**
     * 执行安全扫描处理流程，并维护相应运行结果。
     */
    private void execute(Long runId, Long databaseId, List<String> tableNames,
            List<SafetyIdentifyRuleDO> rules) {
        int tableCount = 0;
        int columnCount = 0;
        int hitCount = 0;
        try {
            // 1. 解析扫描表范围
            List<String> tables = CollUtil.isNotEmpty(tableNames) ? tableNames
                    : databaseApi.getTableNameList(databaseId).getCheckedData();
            // 2. 预编译规则与采样参数
            Map<Long, Pattern> patternCache = compilePatterns(rules);
            boolean needSample = rules.stream()
                    .anyMatch(rule -> SafetyIdentifyRuleDO.MATCH_TYPE_DATA_REGEX.equals(rule.getMatchType()));
            int sampleLimit = rules.stream()
                    .filter(rule -> SafetyIdentifyRuleDO.MATCH_TYPE_DATA_REGEX.equals(rule.getMatchType()))
                    .mapToInt(rule -> rule.getSampleRows() == null ? 50 : rule.getSampleRows())
                    .max().orElse(50);
            sampleLimit = Math.min(Math.max(sampleLimit, 1), MAX_SAMPLE_ROWS);
            // 3. 逐表扫描
            for (String tableName : CollUtil.emptyIfNull(tables)) {
                tableCount++;
                List<DatabaseColumnRespDTO> columns;
                try {
                    columns = databaseApi.getTableColumnList(databaseId, tableName).getCheckedData();
                } catch (Exception ex) {
                    log.warn("[execute][扫描({}) 表({}) 获取字段失败：{}]", runId, tableName, ex.getMessage());
                    continue;
                }
                if (CollUtil.isEmpty(columns)) {
                    continue;
                }
                Map<String, String> commentMap = loadColumnComments(databaseId, tableName);
                List<Map<String, Object>> sampleRows = needSample
                        ? previewRows(databaseId, tableName, sampleLimit) : null;
                for (DatabaseColumnRespDTO column : columns) {
                    columnCount++;
                    String columnName = column.getColumnName();
                    // 已有打标记录（含已确认/待确认/已驳回）的字段跳过，避免重复打扰
                    if (tagMapper.selectByColumn(databaseId, tableName, columnName) != null) {
                        continue;
                    }
                    for (SafetyIdentifyRuleDO rule : rules) {
                        String hitDesc = match(rule, patternCache.get(rule.getId()),
                                columnName, commentMap.get(columnName), sampleRows);
                        if (hitDesc == null) {
                            continue;
                        }
                        tagMapper.insert(SafetyFieldTagDO.builder()
                                .databaseId(databaseId)
                                .tableName(tableName)
                                .columnName(columnName)
                                .categoryId(rule.getCategoryId())
                                .levelId(resolveLevelId(rule))
                                .identifyRuleId(rule.getId())
                                .scanRunId(runId)
                                .source(SafetyFieldTagDO.SOURCE_AUTO)
                                .tagStatus(SafetyFieldTagDO.TAG_STATUS_PENDING)
                                .hitDesc(hitDesc)
                                .build());
                        hitCount++;
                        break;
                    }
                }
            }
            // 4. 记录成功
            runMapper.updateById(SafetyScanRunDO.builder()
                    .id(runId).runStatus(SafetyScanRunDO.RUN_STATUS_SUCCESS)
                    .tableCount(tableCount).columnCount(columnCount).hitCount(hitCount)
                    .endTime(LocalDateTime.now())
                    .build());
            log.info("[execute][扫描({}) 完成：表 {} / 字段 {} / 命中 {}]", runId, tableCount, columnCount, hitCount);
        } catch (Exception ex) {
            log.error("[execute][扫描({}) 执行失败]", runId, ex);
            runMapper.updateById(SafetyScanRunDO.builder()
                    .id(runId).runStatus(SafetyScanRunDO.RUN_STATUS_FAILED)
                    .tableCount(tableCount).columnCount(columnCount).hitCount(hitCount)
                    .endTime(LocalDateTime.now())
                    .errorInfo(StrUtil.maxLength(ExceptionUtil.stacktraceToString(ex), 3800))
                    .build());
        }
    }

    /**
     * 规则匹配。命中返回命中说明，未命中返回 null
     */
    private String match(SafetyIdentifyRuleDO rule, Pattern pattern, String columnName,
            String comment, List<Map<String, Object>> sampleRows) {
        switch (rule.getMatchType()) {
            case SafetyIdentifyRuleDO.MATCH_TYPE_COLUMN_NAME -> {
                if (pattern != null && pattern.matcher(columnName).find()) {
                    return StrUtil.format("规则[{}]字段名匹配", rule.getRuleName());
                }
            }
            case SafetyIdentifyRuleDO.MATCH_TYPE_COLUMN_COMMENT -> {
                if (StrUtil.isBlank(comment)) {
                    return null;
                }
                for (String keyword : StrUtil.split(rule.getPattern(), ',', true, true)) {
                    if (comment.contains(keyword)) {
                        return StrUtil.format("规则[{}]注释含关键词[{}]", rule.getRuleName(), keyword);
                    }
                }
            }
            case SafetyIdentifyRuleDO.MATCH_TYPE_DATA_REGEX -> {
                if (pattern == null || CollUtil.isEmpty(sampleRows)) {
                    return null;
                }
                int total = 0;
                int hits = 0;
                for (Map<String, Object> row : sampleRows) {
                    Object value = row.get(columnName);
                    if (value == null || StrUtil.isBlank(String.valueOf(value))) {
                        continue;
                    }
                    total++;
                    if (pattern.matcher(String.valueOf(value).trim()).matches()) {
                        hits++;
                    }
                }
                if (total == 0) {
                    return null;
                }
                BigDecimal ratio = BigDecimal.valueOf(hits * 100L).divide(
                        BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal threshold = rule.getMinHitRatio() == null
                        ? BigDecimal.valueOf(80) : rule.getMinHitRatio();
                if (ratio.compareTo(threshold) >= 0) {
                    return StrUtil.format("规则[{}]采样命中率 {}%（{}/{}）",
                            rule.getRuleName(), ratio, hits, total);
                }
            }
            default -> {
                // 未知匹配方式，忽略
            }
        }
        return null;
    }

    /**
     * 编译安全扫描的匹配规则。
     */
    private Map<Long, Pattern> compilePatterns(List<SafetyIdentifyRuleDO> rules) {
        Map<Long, Pattern> cache = new HashMap<>();
        for (SafetyIdentifyRuleDO rule : rules) {
            if (SafetyIdentifyRuleDO.MATCH_TYPE_COLUMN_COMMENT.equals(rule.getMatchType())) {
                continue;
            }
            try {
                cache.put(rule.getId(), Pattern.compile(rule.getPattern(), Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException ex) {
                log.warn("[compilePatterns][规则({}) 正则不合法，跳过：{}]", rule.getId(), ex.getMessage());
            }
        }
        return cache;
    }

    /**
     * 从元数据中加载字段注释（优先自定义注释 → 业务名称 → 源注释）；元数据未采集时返回空 Map
     */
    private Map<String, String> loadColumnComments(Long databaseId, String tableName) {
        try {
            MetaTableDO metaTable = metaTableMapper.selectByDatasourceIdAndName(databaseId, tableName);
            if (metaTable == null) {
                return Map.of();
            }
            Map<String, String> comments = new HashMap<>();
            for (MetaColumnDO column : metaColumnMapper.selectListByTableId(metaTable.getId())) {
                String comment = StrUtil.firstNonBlank(column.getCustomComment(),
                        column.getBusinessName(), column.getSourceComment());
                if (StrUtil.isNotBlank(comment)) {
                    comments.put(column.getColumnName(), comment);
                }
            }
            return comments;
        } catch (Exception ex) {
            log.warn("[loadColumnComments][表({}) 加载元数据注释失败：{}]", tableName, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * 原值采样是失败关闭原则的唯一例外，必须留痕：记一条明文访问审计（只记列名与行数，不记取值）。
     * 审计失败不影响扫描。
     */
    private void auditRawSample(Long databaseId, String tableName, DatabasePreviewRespDTO resp) {
        try {
            var entry = new cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO();
            entry.setAccessType(cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO
                    .ACCESS_TYPE_SCAN);
            entry.setResourceName(StrUtil.maxLength("敏感识别扫描采样：" + tableName, 200));
            entry.setDatabaseId(databaseId);
            entry.setTableName(tableName);
            List<String> columns = resp.getColumns() == null ? List.of()
                    : resp.getColumns().stream().map(DatabasePreviewRespDTO.Column::getName).toList();
            entry.setSensitiveColumns(StrUtil.maxLength(String.join(",", columns), 1000));
            entry.setColumnCount(columns.size());
            entry.setMaskedFlag(0);
            entry.setAccessTime(java.time.LocalDateTime.now());
            accessLogService.recordAccessLog(entry);
        } catch (RuntimeException failure) {
            log.warn("[auditRawSample][敏感识别采样审计写入失败 table={} failureType={}]", tableName,
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * 预览数据行。
     */
    private List<Map<String, Object>> previewRows(Long databaseId, String tableName, int limit) {
        try {
            DatabasePreviewReqDTO reqDTO = new DatabasePreviewReqDTO();
            reqDTO.setDatabaseId(databaseId);
            reqDTO.setTableName(tableName);
            reqDTO.setLimit(limit);
            // 敏感识别需要原值做分类判定；采样结果只用于规则匹配，不返回给用户。
            reqDTO.setMaskScene("SENSITIVE_SCAN");
            DatabasePreviewRespDTO resp = databaseApi.previewTable(reqDTO).getCheckedData();
            if (resp != null && resp.getRows() != null && !resp.getRows().isEmpty()) {
                auditRawSample(databaseId, tableName, resp);
            }
            return resp == null ? null : resp.getRows();
        } catch (Exception ex) {
            log.warn("[previewRows][表({}) 采样失败：{}]", tableName, ex.getMessage());
            return null;
        }
    }

    /**
     * 解析安全扫描的等级编号。
     */
    private Long resolveLevelId(SafetyIdentifyRuleDO rule) {
        if (rule.getLevelId() != null) {
            return rule.getLevelId();
        }
        SafetyCategoryDO category = categoryMapper.selectById(rule.getCategoryId());
        return category == null || category.getDataLevelId() == null
                ? null : category.getDataLevelId().longValue();
    }

}
