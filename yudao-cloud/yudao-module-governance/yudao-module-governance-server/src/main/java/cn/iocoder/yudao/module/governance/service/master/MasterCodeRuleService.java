package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/** 编码属于主数据领域；计数与启用切换由数据库事务保护。 */
@Service
@Validated
public class MasterCodeRuleService {

    @Resource
    private MasterCodeRuleMapper ruleMapper;

    @Resource
    private MasterCodeCounterMapper counterMapper;

    @Resource
    private MasterModelMapper modelMapper;

    @Resource
    private MasterColumnMapper columnMapper;

    /**
     * 创建编码规则。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(MasterCodeRuleSaveReqVO req) {
        lockModel(req.getModelId());
        validate(req);
        MasterCodeRuleDO rule = BeanUtils.toBean(req, MasterCodeRuleDO.class);
        rule.setId(null);
        rule.setSegmentsJson(JsonUtils.toJsonString(req.getSegments()));
        if (req.getStatus() == 0) {
            disableOthers(req.getModelId(), null);
        }
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException ex) {
            throw exception(MASTER_CODE_RULE_CODE_DUPLICATE);
        }
        return rule.getId();
    }

    /**
     * 修改编码规则。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(MasterCodeRuleSaveReqVO req) {
        MasterCodeRuleDO old = requireRule(req.getId());
        if (!Objects.equals(old.getModelId(), req.getModelId())) {
            throw exception(MASTER_CODE_RULE_INVALID, "所属模型不能修改");
        }
        lockModel(old.getModelId());
        validate(req);
        MasterCodeRuleDO update = BeanUtils.toBean(req, MasterCodeRuleDO.class);
        update.setSegmentsJson(JsonUtils.toJsonString(req.getSegments()));
        if (req.getStatus() == 0) {
            disableOthers(req.getModelId(), req.getId());
        }
        try {
            ruleMapper.updateById(update);
        } catch (DuplicateKeyException ex) {
            throw exception(MASTER_CODE_RULE_CODE_DUPLICATE);
        }
    }

    /**
     * 启停编码规则。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(MasterCodeRuleStatusReqVO req) {
        MasterCodeRuleDO rule = requireRule(req.getId());
        lockModel(rule.getModelId());
        if (req.getStatus() == 0) {
            MasterCodeRuleSaveReqVO check = BeanUtils.toBean(rule, MasterCodeRuleSaveReqVO.class);
            check.setSegments(JsonUtils.parseArray(rule.getSegmentsJson(), MasterCodeSegmentVO.class));
            validate(check);
            disableOthers(rule.getModelId(), rule.getId());
        }
        ruleMapper.update(null, Wrappers.lambdaUpdate(MasterCodeRuleDO.class)
                .eq(MasterCodeRuleDO::getId, rule.getId()).set(MasterCodeRuleDO::getStatus, req.getStatus()));
    }

    /**
     * 批量删除编码规则。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteList(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 200) {
            throw exception(MASTER_CODE_RULE_INVALID, "请选择不超过200条规则");
        }
        // 全批预校验，稳定锁顺序；计数记录不删除，避免重新建规则后重用已发放编码。
        List<MasterCodeRuleDO> rules = ids.stream().distinct().map(this::requireRule).toList();
        rules.stream().map(MasterCodeRuleDO::getModelId).distinct().sorted().forEach(this::lockModel);
        rules.forEach(rule -> ruleMapper.deleteById(rule.getId()));
    }

    /**
     * 查询编码规则。
     */
    public MasterCodeRuleRespVO get(Long id) {
        return toResp(requireRule(id));
    }

    /**
     * 分页查询编码规则。
     */
    public PageResult<MasterCodeRuleRespVO> page(MasterCodeRulePageReqVO req) {
        PageResult<MasterCodeRuleDO> page = ruleMapper.selectPage(req);
        return new PageResult<>(page.getList().stream().map(this::toResp).toList(), page.getTotal());
    }

    /**
     * 预览编码，不发放流水号。
     */
    public String preview(MasterCodeRuleSaveReqVO req) {
        validate(req);
        MasterCodeCounterDO counter = counterMapper.selectOne(MasterCodeCounterDO::getModelId, req.getModelId());
        return render(req.getSegments(), counter == null ? 1L : counter.getNextValue(), Map.of(), LocalDate.now(),
                true);
    }

    /** 允许出现流水号空洞；发放后不回收，未知外部写入绝不重用编码。 */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> generateForCreate(Long modelId, List<Map<String, Object>> rows) {
        lockModel(modelId);
        MasterCodeRuleDO rule = ruleMapper.selectEnabled(modelId);
        List<Map<String, Object>> result = rows.stream()
                .map(row -> (Map<String, Object>) new LinkedHashMap<String, Object>(row)).toList();
        if (rule == null) {
            return result;
        }
        List<MasterCodeSegmentVO> segments = JsonUtils.parseArray(rule.getSegmentsJson(), MasterCodeSegmentVO.class);
        MasterCodeCounterDO counter = counterMapper.selectForUpdate(modelId);
        if (counter == null) {
            counter = new MasterCodeCounterDO();
            counter.setModelId(modelId);
            counter.setNextValue(1L);
            counterMapper.insert(counter);
        }
        long next = counter.getNextValue();
        for (Map<String, Object> row : result) {
            Object supplied = row.get(rule.getFieldName());
            if (supplied != null && !String.valueOf(supplied).isBlank()) {
                throw exception(MASTER_CODE_RULE_INVALID, "启用编码规则后无需手工填写编码字段");
            }
            row.put(rule.getFieldName(), render(segments, next++, row, LocalDate.now(), false));
        }
        counter.setNextValue(next);
        counterMapper.updateById(counter);
        return result;
    }

    /**
     * 校验主数据编码规则配置满足当前业务约束。
     */
    private void validate(MasterCodeRuleSaveReqVO req) {
        MasterModelDO model = modelMapper.selectById(req.getModelId());
        if (model == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
        List<MasterColumnDO> columns = columnMapper.selectListByModelId(model.getId());
        Map<String, MasterColumnDO> fields = new HashMap<>();
        columns.forEach(c -> fields.put(c.getName(), c));
        MasterColumnDO target = fields.get(req.getFieldName());
        if (target == null || target.getFieldType() != ColumnMetaData.TYPE_STRING) {
            throw exception(MASTER_CODE_RULE_INVALID, "编码字段须是模型中的字符串字段");
        }
        long sequences = req.getSegments().stream().filter(s -> "SEQUENCE".equals(s.getSegmentType())).count();
        if (sequences != 1) {
            throw exception(MASTER_CODE_RULE_INVALID, "必须且只能包含一个流水号段");
        }
        for (MasterCodeSegmentVO segment : req.getSegments()) {
            switch (segment.getSegmentType()) {
                case "DATE" -> {
                    if (!Set.of("yyyyMMdd", "yyyyMM", "yyyy", "yyMMdd").contains(segment.getSegmentValue())) {
                        throw exception(MASTER_CODE_RULE_INVALID, "日期格式不支持");
                    }
                }
                case "SEQUENCE" -> {
                    if (!segment.getSegmentValue().matches("(?:[1-9]|10)")) {
                        throw exception(MASTER_CODE_RULE_INVALID, "流水号位数为1至10");
                    }
                }
                case "ATTRIBUTE" -> {
                    if (!fields.containsKey(segment.getSegmentValue()) || req.getFieldName()
                            .equals(segment.getSegmentValue())) {
                        throw exception(MASTER_CODE_RULE_INVALID, "属性段须引用其他模型字段");
                    }
                }
                case "CONSTANT" -> {
                }
                default -> throw exception(MASTER_CODE_RULE_INVALID, "编码段类型不支持");
            }
        }
        String example = render(req.getSegments(), 1L, Map.of(), LocalDate.now(), true);
        if (target.getFieldLength() != null && example.length() > target.getFieldLength()) {
            throw exception(MASTER_CODE_RULE_INVALID, "编码长度超过目标字段");
        }
    }

    /**
     * 按编码规则模板及输入上下文生成主数据编码。
     */
    static String render(List<MasterCodeSegmentVO> segments, long sequence, Map<String, Object> row,
            LocalDate date, boolean preview) {
        StringBuilder result = new StringBuilder();
        for (MasterCodeSegmentVO segment : segments) {
            String part;
            switch (segment.getSegmentType()) {
                case "CONSTANT" -> part = segment.getSegmentValue();
                case "DATE" -> part = date.format(DateTimeFormatter.ofPattern(segment.getSegmentValue()));
                case "SEQUENCE" -> {
                    int width = Integer.parseInt(segment.getSegmentValue());
                    if (sequence < 1 || BigInteger.valueOf(sequence).compareTo(BigInteger.TEN.pow(width)) >= 0) {
                        throw exception(MASTER_CODE_RULE_EXHAUSTED);
                    }
                    part = String.format(Locale.ROOT, "%0" + width + "d", sequence);
                }
                case "ATTRIBUTE" -> {
                    Object value = row.get(segment.getSegmentValue());
                    if (!preview && (value == null || String.valueOf(value).isBlank())) {
                        throw exception(MASTER_CODE_RULE_INVALID, "编码引用的属性不能为空");
                    }
                    part = value == null ? "{" + segment.getSegmentValue() + "}" : String.valueOf(value);
                }
                default -> throw exception(MASTER_CODE_RULE_INVALID, "编码段类型不支持");
            }
            result.append(part);
            if (segment.getSepChar() != null) {
                result.append(segment.getSepChar());
            }
        }
        return result.toString();
    }

    /**
     * 校验主数据编码规则的规则，不满足约束时拒绝当前操作。
     */
    private MasterCodeRuleDO requireRule(Long id) {
        MasterCodeRuleDO rule = id == null ? null : ruleMapper.selectById(id);
        if (rule == null) {
            throw exception(MASTER_CODE_RULE_NOT_EXISTS);
        }
        return rule;
    }

    /**
     * 按指定条件读取并锁定主数据编码规则记录，供事务内一致性校验或修改使用。
     */
    private void lockModel(Long id) {
        MasterModelDO model = modelMapper.selectOne(new LambdaQueryWrapperX<MasterModelDO>()
                .eq(MasterModelDO::getId, id).last("FOR UPDATE"));
        if (model == null) {
            throw exception(MASTER_MODEL_NOT_EXISTS);
        }
    }

    /**
     * 停用同一模型下的其他编码规则。
     */
    private void disableOthers(Long modelId, Long exceptId) {
        ruleMapper.update(null, Wrappers.lambdaUpdate(MasterCodeRuleDO.class)
                .eq(MasterCodeRuleDO::getModelId, modelId).eq(MasterCodeRuleDO::getStatus, 0)
                .ne(exceptId != null, MasterCodeRuleDO::getId, exceptId)
                .set(MasterCodeRuleDO::getStatus, 1));
    }

    /**
     * 将主数据编码规则业务记录转换为接口响应。
     */
    private MasterCodeRuleRespVO toResp(MasterCodeRuleDO rule) {
        MasterCodeRuleRespVO resp = BeanUtils.toBean(rule, MasterCodeRuleRespVO.class);
        resp.setSegments(JsonUtils.parseArray(rule.getSegmentsJson(), MasterCodeSegmentVO.class));
        MasterModelDO model = modelMapper.selectById(rule.getModelId());
        resp.setModelName(model == null ? "已删除模型" : model.getTableCn());
        resp.setExample(render(resp.getSegments(), 1L, Map.of(), LocalDate.now(), true));
        return resp;
    }
}
