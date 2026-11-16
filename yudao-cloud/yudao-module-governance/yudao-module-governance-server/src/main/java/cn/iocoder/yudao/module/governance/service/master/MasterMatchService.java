package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.governance.service.master.match.MasterMatchConfig;
import cn.iocoder.yudao.module.governance.service.master.match.MasterMatcher;
import cn.iocoder.yudao.module.governance.service.master.match.MasterSurvivorship;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 主数据相似匹配、去重合并与黄金记录。
 * <p>
 * 匹配：按模型的匹配规则读取全部记录，分块后两两加权打分，相似度达到阈值的记录按传递关系聚成疑似重复组；
 * 合并：人工确认保留记录与各字段取值后，以“合并”变更申请走模型既有的审批与条件写，生效时保留记录被更新为黄金记录、
 * 其余记录删除，并登记合并来源追溯。主数据在平台内共享，权限由接口权限码控制。
 */
@Service
@Validated
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MasterMatchService {

    /** 执行中的匹配超过该时长视为中断 */
    private static final int ABANDONED_MINUTES = 30;
    private static final Set<Integer> NUMERIC_TYPES = Set.of(ColumnMetaData.TYPE_NUMBER, ColumnMetaData.TYPE_INTEGER,
            ColumnMetaData.TYPE_BIGNUMBER);

    @Resource
    private MasterMatchRuleMapper ruleMapper;
    @Resource
    private MasterMatchRunMapper runMapper;
    @Resource
    private MasterMatchGroupMapper groupMapper;
    @Resource
    private MasterMergeXrefMapper xrefMapper;
    @Resource
    private MasterDataChangeMapper changeMapper;
    @Resource
    private MasterColumnMapper columnMapper;
    @Resource
    private MasterModelService modelService;
    @Resource
    private MasterDataService dataService;
    @Resource
    private MasterDataChangeService changeService;
    @Resource
    private AdminUserApi userApi;
    @Resource
    private PlatformTransactionManager transactionManager;

    // ==================== 匹配规则 ====================

    /** 查询模型的匹配规则；未配置时返回默认值（configured=false） */
    public MasterMatchRuleVO getRule(Long modelId) {
        modelService.validateModelReleased(modelId);
        MasterMatchRuleDO rule = ruleMapper.selectByModelId(modelId);
        MasterMatchRuleVO vo = new MasterMatchRuleVO();
        vo.setModelId(modelId);
        if (rule == null) {
            vo.setConfigured(false);
            vo.setFields(List.of());
            vo.setBlockingFields(List.of());
            vo.setThreshold("0.85");
            vo.setSurvivorship(Map.of());
            vo.setMaxRows(MasterMatchConfig.DEFAULT_MAX_ROWS);
            return vo;
        }
        vo.setConfigured(true);
        vo.setFields(JsonUtils.parseArray(rule.getFieldsJson(), MasterMatchRuleVO.Field.class));
        vo.setBlockingFields(JsonUtils.parseArray(rule.getBlockingFieldsJson(), String.class));
        vo.setThreshold(rule.getThreshold().stripTrailingZeros().toPlainString());
        vo.setSurvivorship(JsonUtils.parseObject(rule.getSurvivorshipJson(), new TypeReference<>() {
        }));
        vo.setMaxRows(rule.getMaxRows());
        vo.setUpdateTime(rule.getUpdateTime());
        return vo;
    }

    /** 保存匹配规则（按当前模型字段校验） */
    public MasterMatchRuleVO saveRule(MasterMatchRuleVO req) {
        modelService.validateModelReleased(req.getModelId());
        MasterMatchConfig config = parse(req, columnMapper.selectListByModelId(req.getModelId()));
        tx().executeWithoutResult(status -> {
            MasterMatchRuleDO rule = ruleMapper.selectByModelId(req.getModelId());
            if (rule == null) {
                rule = new MasterMatchRuleDO();
                rule.setModelId(req.getModelId());
            }
            rule.setFieldsJson(JsonUtils.toJsonString(config.fields().stream().map(field -> {
                MasterMatchRuleVO.Field vo = new MasterMatchRuleVO.Field();
                vo.setName(field.name());
                vo.setAlgorithm(field.algorithm().name());
                vo.setWeight(field.weight());
                return vo;
            }).toList()));
            rule.setBlockingFieldsJson(JsonUtils.toJsonString(config.blockingFields()));
            rule.setThreshold(config.threshold());
            Map<String, String> survivorship = new TreeMap<>();
            config.survivorship().forEach((name, value) -> survivorship.put(name, value.name()));
            rule.setSurvivorshipJson(JsonUtils.toJsonString(survivorship));
            rule.setMaxRows(config.maxRows());
            if (rule.getId() == null) {
                ruleMapper.insert(rule);
            } else {
                ruleMapper.updateById(rule);
            }
        });
        return getRule(req.getModelId());
    }

    /**
     * 按模型字段校验规则并转为不可变配置：字段须存在且不是主键；文本算法只用于字符串字段，数值算法只用于数值字段；
     * 最大/最小取值规则不用于布尔字段。
     */
    static MasterMatchConfig parse(MasterMatchRuleVO req, List<MasterColumnDO> columns) {
        Map<String, MasterColumnDO> byName = columns.stream()
                .collect(Collectors.toMap(MasterColumnDO::getName, Function.identity(), (a, b) -> a));
        if (req.getFields() == null || req.getFields().isEmpty() || req.getFields().size() > 20) {
            throw exception(MASTER_MATCH_RULE_INVALID, "参与匹配的字段为1至20个");
        }
        List<MasterMatchConfig.Field> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MasterMatchRuleVO.Field field : req.getFields()) {
            MasterColumnDO column = requireColumn(byName, field == null ? null : field.getName());
            if (!seen.add(column.getName())) {
                throw exception(MASTER_MATCH_RULE_INVALID, "字段【" + column.getName() + "】重复");
            }
            MasterMatchConfig.Algorithm algorithm = parseEnum(MasterMatchConfig.Algorithm.class,
                    field.getAlgorithm(), "比较算法");
            boolean text = Integer.valueOf(ColumnMetaData.TYPE_STRING).equals(column.getFieldType());
            boolean numeric = NUMERIC_TYPES.contains(column.getFieldType());
            if ((algorithm == MasterMatchConfig.Algorithm.TEXT || algorithm == MasterMatchConfig.Algorithm.EDIT) && !text
                    || algorithm == MasterMatchConfig.Algorithm.NUMERIC && !numeric) {
                throw exception(MASTER_MATCH_RULE_INVALID, "字段【" + column.getName() + "】的类型不支持所选算法");
            }
            if (field.getWeight() == null || field.getWeight() < 1 || field.getWeight() > 10) {
                throw exception(MASTER_MATCH_RULE_INVALID, "权重为1至10");
            }
            fields.add(new MasterMatchConfig.Field(column.getName(), algorithm, field.getWeight()));
        }
        List<String> blocking = new ArrayList<>();
        for (String name : Optional.ofNullable(req.getBlockingFields()).orElse(List.of())) {
            MasterColumnDO column = requireColumn(byName, name);
            if (blocking.contains(column.getName())) {
                throw exception(MASTER_MATCH_RULE_INVALID, "分块字段【" + column.getName() + "】重复");
            }
            blocking.add(column.getName());
        }
        if (blocking.size() > 5) {
            throw exception(MASTER_MATCH_RULE_INVALID, "分块字段最多5个");
        }
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(String.valueOf(req.getThreshold()).trim());
        } catch (NumberFormatException ex) {
            throw exception(MASTER_MATCH_RULE_INVALID, "相似度阈值不是数值");
        }
        if (threshold.compareTo(MasterMatchConfig.MIN_THRESHOLD) < 0 || threshold.compareTo(BigDecimal.ONE) > 0
                || threshold.stripTrailingZeros().scale() > 4) {
            throw exception(MASTER_MATCH_RULE_INVALID, "相似度阈值为0.5至1、最多4位小数");
        }
        Map<String, MasterMatchConfig.Survivorship> survivorship = new LinkedHashMap<>();
        Optional.ofNullable(req.getSurvivorship()).orElse(Map.of()).forEach((name, value) -> {
            MasterColumnDO column = requireColumn(byName, name);
            MasterMatchConfig.Survivorship rule = parseEnum(MasterMatchConfig.Survivorship.class, value, "取值规则");
            if ((rule == MasterMatchConfig.Survivorship.MAX || rule == MasterMatchConfig.Survivorship.MIN)
                    && Integer.valueOf(ColumnMetaData.TYPE_BOOLEAN).equals(column.getFieldType())) {
                throw exception(MASTER_MATCH_RULE_INVALID, "布尔字段【" + column.getName() + "】不能取最大或最小值");
            }
            if (rule != MasterMatchConfig.Survivorship.SURVIVOR) {
                survivorship.put(column.getName(), rule);
            }
        });
        int maxRows = req.getMaxRows() == null ? MasterMatchConfig.DEFAULT_MAX_ROWS : req.getMaxRows();
        if (maxRows < 2 || maxRows > MasterMatchConfig.MAX_ROWS_LIMIT) {
            throw exception(MASTER_MATCH_RULE_INVALID, "最大读取记录数为2至100000");
        }
        return new MasterMatchConfig(fields, blocking, threshold.setScale(4, RoundingMode.UNNECESSARY),
                survivorship, maxRows);
    }

    private static MasterColumnDO requireColumn(Map<String, MasterColumnDO> byName, String name) {
        MasterColumnDO column = name == null ? null : byName.get(name);
        if (column == null) {
            throw exception(MASTER_MATCH_RULE_INVALID, "字段【" + name + "】不存在，请按当前模型重新配置");
        }
        if (Integer.valueOf(1).equals(column.getPk())) {
            throw exception(MASTER_MATCH_RULE_INVALID, "主键字段【" + name + "】不参与匹配和取值规则");
        }
        return column;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            throw exception(MASTER_MATCH_RULE_INVALID, label + "不正确");
        }
    }

    /** 读取已保存的规则并按当前模型字段重新校验 */
    private MasterMatchConfig requireConfig(Long modelId) {
        MasterMatchRuleDO rule = ruleMapper.selectByModelId(modelId);
        if (rule == null) {
            throw exception(MASTER_MATCH_RULE_NOT_CONFIGURED);
        }
        return parse(getRule(modelId), columnMapper.selectListByModelId(modelId));
    }

    // ==================== 执行匹配 ====================

    /**
     * 同步执行一次相似匹配：读取全部记录、打分聚类并保存疑似重复组。
     * 成功后旧的待处理组由新结果取代；已忽略的同一组（成员完全相同）和正在合并的同一组不再重复产生。
     */
    public MasterMatchRunRespVO run(Long modelId) {
        Long actor = requireActor();
        modelService.validateModelReleased(modelId);
        MasterMatchConfig config = requireConfig(modelId);
        MasterMatchRunDO run = new MasterMatchRunDO();
        run.setModelId(modelId);
        run.setRuleJson(JsonUtils.toJsonString(getRule(modelId)));
        run.setStatus(MasterMatchRunDO.STATUS_RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setRunBy(actor);
        try {
            tx().executeWithoutResult(status -> {
                runMapper.failAbandoned(modelId, LocalDateTime.now().minusMinutes(ABANDONED_MINUTES));
                runMapper.insert(run);
            });
        } catch (DuplicateKeyException ex) {
            throw exception(MASTER_MATCH_RUNNING);
        }
        try {
            List<String> primary = columnMapper.selectListByModelId(modelId).stream()
                    .filter(column -> Integer.valueOf(1).equals(column.getPk())).map(MasterColumnDO::getName).toList();
            MasterDataService.ScanResult scan = dataService.scanRows(modelId, config.maxRows());
            if (scan.total() > config.maxRows()) {
                throw exception(MASTER_MATCH_RUN_FAILED, "记录数 " + scan.total() + " 超过规则允许的最大读取数 "
                        + config.maxRows() + "，请调大上限或配置分块字段后分批治理");
            }
            long estimated = MasterMatcher.estimateComparisons(scan.rows(), config);
            if (estimated > MasterMatchConfig.MAX_COMPARISONS) {
                throw exception(MASTER_MATCH_RUN_FAILED, "需要比较 " + estimated + " 对记录，超过上限 "
                        + MasterMatchConfig.MAX_COMPARISONS + "，请配置或细化分块字段");
            }
            MasterMatcher.Result result = MasterMatcher.match(scan.rows(), config);
            List<MasterMatchGroupDO> groups = result.groups().stream()
                    .map(group -> toGroup(run, group, scan.rows(), primary)).toList();
            String message = summary(result);
            tx().executeWithoutResult(status -> {
                groupMapper.markPendingStale(modelId);
                Set<String> skipped = groupMapper.selectFingerprints(modelId,
                        List.of(MasterMatchGroupDO.STATUS_IGNORED, MasterMatchGroupDO.STATUS_MERGING));
                int inserted = 0;
                for (MasterMatchGroupDO group : groups) {
                    if (!skipped.contains(group.getFingerprint())) {
                        groupMapper.insert(group);
                        inserted++;
                    }
                }
                run.setStatus(MasterMatchRunDO.STATUS_SUCCESS);
                run.setScannedRows(scan.rows().size());
                run.setComparedPairs(result.comparedPairs());
                run.setGroupCount(inserted);
                run.setMessage(inserted < groups.size()
                        ? join(message, (groups.size() - inserted) + " 组已被忽略或正在合并，未重复列出") : message);
                run.setFinishedAt(LocalDateTime.now());
                runMapper.updateById(run);
            });
        } catch (RuntimeException ex) {
            String reason = ex instanceof ServiceException service ? service.getMessage() : "读取或计算失败，请检查数据源连接";
            if (!(ex instanceof ServiceException)) {
                log.warn("主数据相似匹配失败，模型编号={}", modelId, ex);
            }
            tx().executeWithoutResult(status -> {
                MasterMatchRunDO failed = new MasterMatchRunDO();
                failed.setId(run.getId());
                failed.setStatus(MasterMatchRunDO.STATUS_FAILED);
                failed.setMessage(reason.length() > 500 ? reason.substring(0, 500) : reason);
                failed.setFinishedAt(LocalDateTime.now());
                runMapper.updateById(failed);
            });
            throw ex instanceof ServiceException service ? service : exception(MASTER_MATCH_RUN_FAILED, reason);
        }
        return runVO(runMapper.selectById(run.getId()), users(Set.of(actor)));
    }

    private static String summary(MasterMatcher.Result result) {
        String message = null;
        if (result.unblockedRows() > 0) {
            message = result.unblockedRows() + " 条记录的分块字段为空，未参与比较";
        }
        if (result.oversizedClusters() > 0) {
            message = join(message, result.oversizedClusters() + " 个疑似重复簇超过 "
                    + MasterMatchConfig.MAX_GROUP_SIZE + " 条，已跳过，请提高阈值或增加分块字段");
        }
        return message;
    }

    private static String join(String left, String right) {
        return left == null ? right : left + "；" + right;
    }

    private static MasterMatchGroupDO toGroup(MasterMatchRunDO run, MasterMatcher.Group group,
            List<Map<String, Object>> rows, List<String> primary) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (int index : group.members()) {
            Map<String, Object> row = rows.get(index);
            Map<String, Object> keyValues = new LinkedHashMap<>();
            primary.forEach(name -> keyValues.put(name, row.get(name)));
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("key", MasterDataChangeService.dataId(row, primary));
            member.put("keyValues", keyValues);
            member.put("row", row);
            members.add(member);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (MasterMatcher.Pair pair : group.pairs()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("left", group.members().indexOf(pair.left()));
            value.put("right", group.members().indexOf(pair.right()));
            value.put("score", decimal(pair.score()));
            Map<String, String> fields = new LinkedHashMap<>();
            pair.fieldScores().forEach((name, score) -> fields.put(name, decimal(score)));
            value.put("fields", fields);
            pairs.add(value);
        }
        MasterMatchGroupDO result = new MasterMatchGroupDO();
        result.setRunId(run.getId());
        result.setModelId(run.getModelId());
        result.setFingerprint(MasterDataChangeService.digest(members.stream()
                .map(member -> (String) member.get("key")).sorted().collect(Collectors.joining("\n"))));
        result.setStatus(MasterMatchGroupDO.STATUS_PENDING);
        result.setMemberCount(members.size());
        result.setMaxScore(new BigDecimal(decimal(group.maxScore())));
        result.setMembersJson(JsonUtils.toJsonString(members));
        result.setPairsJson(JsonUtils.toJsonString(pairs));
        return result;
    }

    private static String decimal(double score) {
        return BigDecimal.valueOf(Math.min(1, Math.max(0, score))).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    public PageResult<MasterMatchRunRespVO> runPage(MasterMatchRunPageReqVO req) {
        PageResult<MasterMatchRunDO> page = runMapper.selectPage(req);
        Map<Long, AdminUserRespDTO> users = users(page.getList().stream().map(MasterMatchRunDO::getRunBy)
                .collect(Collectors.toSet()));
        return new PageResult<>(page.getList().stream().map(run -> runVO(run, users)).toList(), page.getTotal());
    }

    private static MasterMatchRunRespVO runVO(MasterMatchRunDO run, Map<Long, AdminUserRespDTO> users) {
        MasterMatchRunRespVO vo = new MasterMatchRunRespVO();
        vo.setId(run.getId());
        vo.setModelId(run.getModelId());
        vo.setStatus(run.getStatus());
        vo.setScannedRows(run.getScannedRows());
        vo.setComparedPairs(run.getComparedPairs());
        vo.setGroupCount(run.getGroupCount());
        vo.setMessage(run.getMessage());
        vo.setStartedAt(run.getStartedAt());
        vo.setFinishedAt(run.getFinishedAt());
        AdminUserRespDTO user = users.get(run.getRunBy());
        vo.setRunByName(user == null ? null : user.getNickname());
        return vo;
    }

    // ==================== 疑似重复组 ====================

    public PageResult<MasterMatchGroupRespVO> groupPage(MasterMatchGroupPageReqVO req) {
        tx().executeWithoutResult(status -> groupMapper.releaseClosedMerges(req.getModelId()));
        PageResult<MasterMatchGroupDO> page = groupMapper.selectPage(req);
        Map<Long, String> changeStatus = changeStatuses(page.getList());
        Map<Long, AdminUserRespDTO> users = users(page.getList().stream().map(MasterMatchGroupDO::getHandledBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return new PageResult<>(page.getList().stream().map(group -> {
            MasterMatchGroupRespVO vo = groupVO(group, changeStatus, users);
            vo.setMembers(snapshotMembers(group));
            return vo;
        }).toList(), page.getTotal());
    }

    /**
     * 疑似重复组详情：成员内容取数据库当前值（已不存在的成员保留匹配时快照并标记），
     * 并按当前规则给出黄金记录建议（只在仍存在的成员中选择）。
     */
    public MasterMatchGroupRespVO group(Long id) {
        MasterMatchGroupDO group = requireGroup(id);
        tx().executeWithoutResult(status -> groupMapper.releaseClosedMerges(group.getModelId()));
        MasterMatchGroupDO current = requireGroup(id);
        modelService.validateModelReleased(current.getModelId());
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(current.getModelId());
        List<String> fields = columns.stream().map(MasterColumnDO::getName).toList();
        Set<String> primary = columns.stream().filter(column -> Integer.valueOf(1).equals(column.getPk()))
                .map(MasterColumnDO::getName).collect(Collectors.toSet());
        List<Map<String, Object>> stored = members(current);
        List<Map<String, Object>> live = dataService.snapshotRows(current.getModelId(),
                stored.stream().map(MasterMatchService::keyValues).toList());
        List<MasterMatchGroupRespVO.Member> members = new ArrayList<>();
        List<Map<String, Object>> existing = new ArrayList<>();
        List<Integer> existingIndex = new ArrayList<>();
        for (int i = 0; i < stored.size(); i++) {
            Map<String, Object> row = live.get(i);
            MasterMatchGroupRespVO.Member member = new MasterMatchGroupRespVO.Member();
            member.setKey((String) stored.get(i).get("key"));
            member.setExists(row != null);
            member.setRow(row != null ? row : rowOf(stored.get(i)));
            members.add(member);
            if (row != null) {
                existing.add(row);
                existingIndex.add(i);
            }
        }
        MasterMatchGroupRespVO vo = groupVO(current, changeStatuses(List.of(current)),
                users(current.getHandledBy() == null ? Set.of() : Set.of(current.getHandledBy())));
        vo.setMembers(members);
        vo.setPairs(JsonUtils.parseArray(current.getPairsJson(), MasterMatchGroupRespVO.Pair.class));
        if (existing.size() >= 2) {
            Map<String, MasterMatchConfig.Survivorship> rules = ruleMapper.selectByModelId(current.getModelId()) == null
                    ? Map.of() : requireConfig(current.getModelId()).survivorship();
            MasterSurvivorship.Suggestion suggestion = MasterSurvivorship.suggest(existing, fields, primary, rules, null);
            MasterMatchGroupRespVO.Suggestion result = new MasterMatchGroupRespVO.Suggestion();
            result.setSurvivor(existingIndex.get(suggestion.survivor()));
            result.setValues(suggestion.values());
            Map<String, Integer> sources = new LinkedHashMap<>();
            suggestion.sources().forEach((name, index) -> sources.put(name, existingIndex.get(index)));
            result.setSources(sources);
            vo.setSuggestion(result);
        }
        return vo;
    }

    /** 忽略疑似重复组：确认不是重复，之后匹配出成员完全相同的组时不再列出 */
    public void ignore(MasterMatchIgnoreReqVO req) {
        Long actor = requireActor();
        tx().executeWithoutResult(status -> {
            MasterMatchGroupDO group = groupMapper.selectForUpdate(req.getGroupId());
            if (group == null) {
                throw exception(MASTER_MATCH_GROUP_NOT_EXISTS);
            }
            if (!MasterMatchGroupDO.STATUS_PENDING.equals(group.getStatus())) {
                throw exception(MASTER_MATCH_GROUP_STATE_INVALID);
            }
            group.setStatus(MasterMatchGroupDO.STATUS_IGNORED);
            group.setHandledBy(actor);
            group.setHandledAt(LocalDateTime.now());
            group.setHandleRemark(req.getRemark());
            groupMapper.updateById(group);
        });
    }

    // ==================== 合并 ====================

    /**
     * 提交合并：保留记录与被合并记录须是组内成员，黄金记录的每个字段值须取自参与合并的某条记录的当前值；
     * 以“合并”变更申请提交，组在同一平台事务内转为合并中，防止重复提交。
     */
    public MasterDataMutationRespVO merge(MasterMatchMergeReqVO req) {
        MasterMatchGroupDO group = requireGroup(req.getGroupId());
        // 客户端重试：合并可能已生效、被合并记录已删除，直接返回原申请而不重新校验
        MasterDataMutationRespVO replay = changeService.replayMerge(req.getRequestId(), group.getId());
        if (replay != null) {
            return replay;
        }
        tx().executeWithoutResult(status -> groupMapper.releaseClosedMerges(group.getModelId()));
        MasterMatchGroupDO current = requireGroup(req.getGroupId());
        if (!MasterMatchGroupDO.STATUS_PENDING.equals(current.getStatus())) {
            throw exception(MASTER_MATCH_GROUP_STATE_INVALID);
        }
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(current.getModelId());
        Map<String, Map<String, Object>> memberKeys = new LinkedHashMap<>();
        members(current).forEach(member -> memberKeys.put((String) member.get("key"), keyValues(member)));
        if (!memberKeys.containsKey(req.getSurvivorKey())) {
            throw exception(MASTER_MATCH_MERGE_INVALID, "保留记录不在疑似重复组中");
        }
        Set<String> merged = new LinkedHashSet<>(req.getMergedKeys());
        if (merged.size() != req.getMergedKeys().size() || merged.contains(req.getSurvivorKey())
                || !memberKeys.keySet().containsAll(merged)) {
            throw exception(MASTER_MATCH_MERGE_INVALID, "被合并记录须是组内除保留记录外的成员且不能重复");
        }
        List<Map<String, Object>> participants = new ArrayList<>();
        participants.add(memberKeys.get(req.getSurvivorKey()));
        merged.forEach(key -> participants.add(memberKeys.get(key)));
        List<Map<String, Object>> live = dataService.snapshotRows(current.getModelId(), participants);
        if (live.stream().anyMatch(Objects::isNull)) {
            throw exception(MASTER_MATCH_MERGE_INVALID, "参与合并的记录已不存在，请重新匹配");
        }
        Map<String, Object> golden = golden(req.getValues(), live, columns);
        List<Map<String, Object>> duplicates = participants.subList(1, participants.size()).stream()
                .map(key -> (Map<String, Object>) new LinkedHashMap<>(key)).toList();
        Long actor = requireActor();
        return changeService.submitMerge(current.getModelId(), req.getRequestId(), golden, duplicates,
                current.getId(), change -> {
                    MasterMatchGroupDO locked = groupMapper.selectForUpdate(current.getId());
                    if (locked == null || !MasterMatchGroupDO.STATUS_PENDING.equals(locked.getStatus())) {
                        throw exception(MASTER_MATCH_GROUP_STATE_INVALID);
                    }
                    locked.setStatus(MasterMatchGroupDO.STATUS_MERGING);
                    locked.setChangeId(change.getId());
                    locked.setHandledBy(actor);
                    locked.setHandledAt(LocalDateTime.now());
                    groupMapper.updateById(locked);
                });
    }

    /**
     * 校验并组装黄金记录：须覆盖模型全部字段；主键取保留记录；其余字段值须与参与合并的某条记录当前值一致
     * （按接口传输形式比较，避免在合并中引入未经审核的新值）。
     */
    static Map<String, Object> golden(Map<String, Object> values, List<Map<String, Object>> live,
            List<MasterColumnDO> columns) {
        Set<String> names = columns.stream().map(MasterColumnDO::getName).collect(Collectors.toSet());
        if (values == null || !values.keySet().equals(names)) {
            throw exception(MASTER_MATCH_MERGE_INVALID, "黄金记录须包含模型的全部字段");
        }
        Map<String, Object> golden = new LinkedHashMap<>();
        for (MasterColumnDO column : columns) {
            String name = column.getName();
            Object value = values.get(name);
            String wire = JsonUtils.toJsonString(value);
            if (Integer.valueOf(1).equals(column.getPk())) {
                if (!wire.equals(JsonUtils.toJsonString(live.getFirst().get(name)))) {
                    throw exception(MASTER_MATCH_MERGE_INVALID, "主键字段【" + name + "】须取保留记录的值");
                }
            } else if (live.stream().noneMatch(row -> wire.equals(JsonUtils.toJsonString(row.get(name))))) {
                throw exception(MASTER_MATCH_MERGE_INVALID, "字段【" + name + "】的值须取自参与合并的记录");
            }
            golden.put(name, value);
        }
        return golden;
    }

    // ==================== 黄金记录 ====================

    /** 黄金记录分页：按最近合并时间排序，附带合并来源 */
    public PageResult<MasterGoldenRespVO> goldenPage(MasterGoldenPageReqVO req) {
        int size = Math.min(Math.max(req.getPageSize(), 1), 100);
        long offset = (long) (Math.max(req.getPageNo(), 1) - 1) * size;
        List<MasterMergeXrefMapper.GoldenSummary> summaries = xrefMapper.selectGoldenPage(req.getModelId(), offset,
                size);
        List<MasterMergeXrefDO> sources = xrefMapper.selectSources(req.getModelId(),
                summaries.stream().map(MasterMergeXrefMapper.GoldenSummary::goldenKey).toList(), size * 50);
        Map<Long, AdminUserRespDTO> users = users(sources.stream().map(MasterMergeXrefDO::getMergedBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<String, List<MasterMergeXrefDO>> byGolden = sources.stream()
                .collect(Collectors.groupingBy(MasterMergeXrefDO::getGoldenKey, LinkedHashMap::new, Collectors.toList()));
        List<MasterGoldenRespVO> list = summaries.stream().map(summary -> {
            MasterGoldenRespVO vo = new MasterGoldenRespVO();
            vo.setGoldenKey(summary.goldenKey());
            vo.setMergedCount(summary.mergedCount());
            vo.setLastMergedAt(summary.lastMergedAt());
            vo.setSources(byGolden.getOrDefault(summary.goldenKey(), List.of()).stream().limit(50).map(xref -> {
                MasterGoldenRespVO.Source source = new MasterGoldenRespVO.Source();
                source.setSourceKey(xref.getSourceKey());
                source.setSourceRow(JsonUtils.parseObject(xref.getSourceRowJson(), new TypeReference<>() {
                }));
                source.setChangeId(xref.getChangeId());
                source.setGroupId(xref.getGroupId());
                source.setMergedAt(xref.getMergedAt());
                AdminUserRespDTO user = xref.getMergedBy() == null ? null : users.get(xref.getMergedBy());
                source.setMergedByName(user == null ? null : user.getNickname());
                return source;
            }).toList());
            return vo;
        }).toList();
        return new PageResult<>(list, xrefMapper.countGolden(req.getModelId()));
    }

    // ==================== 内部方法 ====================

    private MasterMatchGroupDO requireGroup(Long id) {
        MasterMatchGroupDO group = id == null ? null : groupMapper.selectById(id);
        if (group == null) {
            throw exception(MASTER_MATCH_GROUP_NOT_EXISTS);
        }
        return group;
    }

    private static List<Map<String, Object>> members(MasterMatchGroupDO group) {
        return JsonUtils.parseObject(group.getMembersJson(), new TypeReference<List<Map<String, Object>>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> keyValues(Map<String, Object> member) {
        return new LinkedHashMap<>((Map<String, Object>) member.get("keyValues"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowOf(Map<String, Object> member) {
        return (Map<String, Object>) member.get("row");
    }

    /** 列表中的成员只展示匹配时快照，不逐组回查数据源 */
    private static List<MasterMatchGroupRespVO.Member> snapshotMembers(MasterMatchGroupDO group) {
        return members(group).stream().map(stored -> {
            MasterMatchGroupRespVO.Member member = new MasterMatchGroupRespVO.Member();
            member.setKey((String) stored.get("key"));
            member.setRow(rowOf(stored));
            return member;
        }).toList();
    }

    private Map<Long, String> changeStatuses(List<MasterMatchGroupDO> groups) {
        List<Long> ids = groups.stream().map(MasterMatchGroupDO::getChangeId).filter(Objects::nonNull).toList();
        return ids.isEmpty() ? Map.of() : changeMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(MasterDataChangeDO::getId, MasterDataChangeDO::getStatus));
    }

    private static MasterMatchGroupRespVO groupVO(MasterMatchGroupDO group, Map<Long, String> changeStatus,
            Map<Long, AdminUserRespDTO> users) {
        MasterMatchGroupRespVO vo = new MasterMatchGroupRespVO();
        vo.setId(group.getId());
        vo.setRunId(group.getRunId());
        vo.setModelId(group.getModelId());
        vo.setStatus(group.getStatus());
        vo.setMemberCount(group.getMemberCount());
        vo.setMaxScore(group.getMaxScore() == null ? null : group.getMaxScore().toPlainString());
        vo.setChangeId(group.getChangeId());
        vo.setChangeStatus(group.getChangeId() == null ? null : changeStatus.get(group.getChangeId()));
        AdminUserRespDTO user = group.getHandledBy() == null ? null : users.get(group.getHandledBy());
        vo.setHandledByName(user == null ? null : user.getNickname());
        vo.setHandledAt(group.getHandledAt());
        vo.setHandleRemark(group.getHandleRemark());
        vo.setCreateTime(group.getCreateTime());
        return vo;
    }

    private Map<Long, AdminUserRespDTO> users(Set<Long> ids) {
        Set<Long> present = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return present.isEmpty() ? Map.of() : userApi.getUserMap(present);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static Long requireActor() {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw new IllegalStateException("缺少有效的用户上下文");
        }
        return actor;
    }

}
