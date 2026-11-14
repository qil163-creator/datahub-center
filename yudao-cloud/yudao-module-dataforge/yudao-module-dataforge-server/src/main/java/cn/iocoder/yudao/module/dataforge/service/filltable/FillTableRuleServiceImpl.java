package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableRuleRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableRuleSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableRuleDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableColumnMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableRuleMapper;
import cn.iocoder.yudao.module.dataforge.service.fill.FillExpression;
import cn.iocoder.yudao.module.dataforge.service.fill.FillTemplateExpressions;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据填报-填报表校验规则 Service 实现类
 *
 * 数据范围：与填报表字段配置一致，持有填报表修改按钮权限即可维护（平台内全部填报表）。
 *
 * @author Li
 */
@Service
@Validated
public class FillTableRuleServiceImpl implements FillTableRuleService {

    @Resource
    private FillTableRuleMapper ruleMapper;

    @Resource
    private FillTableMapper fillTableMapper;

    @Resource
    private FillTableColumnMapper columnMapper;

    @Override
    public Long createRule(FillTableRuleSaveReqVO reqVO) {
        validateTableEditable(reqVO.getTableId());
        validateRule(reqVO);
        FillTableRuleDO rule = BeanUtils.toBean(reqVO, FillTableRuleDO.class);
        rule.setId(null);
        ruleMapper.insert(rule);
        return rule.getId();
    }

    @Override
    public void updateRule(FillTableRuleSaveReqVO reqVO) {
        FillTableRuleDO old = validateRuleExists(reqVO.getId());
        validateTableEditable(old.getTableId());
        reqVO.setTableId(old.getTableId());
        validateRule(reqVO);
        ruleMapper.updateById(BeanUtils.toBean(reqVO, FillTableRuleDO.class));
    }

    @Override
    public void deleteRule(Long id) {
        FillTableRuleDO rule = validateRuleExists(id);
        validateTableEditable(rule.getTableId());
        ruleMapper.deleteById(id);
    }

    @Override
    public List<FillTableRuleRespVO> getRuleListByTableId(Long tableId) {
        return BeanUtils.toBean(ruleMapper.selectListByTableId(tableId), FillTableRuleRespVO.class);
    }

    private FillTableRuleDO validateRuleExists(Long id) {
        FillTableRuleDO rule = id == null ? null : ruleMapper.selectById(id);
        if (rule == null) {
            throw exception(FILL_TABLE_RULE_NOT_EXISTS);
        }
        return rule;
    }

    private void validateTableEditable(Long tableId) {
        FillTableDO table = fillTableMapper.selectById(tableId);
        if (table == null) {
            throw exception(FILL_TABLE_NOT_EXISTS);
        }
        if (table.getStatus() != null && table.getStatus() == 1) {
            throw exception(FILL_TABLE_RELEASED_CANNOT_MODIFY);
        }
    }

    /** 表达式按范围编译、引用字段必须存在；定位字段同样必须存在 */
    private void validateRule(FillTableRuleSaveReqVO reqVO) {
        Set<String> fieldCodes = convertSet(columnMapper.selectListByTableId(reqVO.getTableId()),
                FillTableColumnDO::getFieldCode);
        FillTemplateExpressions.compile(reqVO.getExpression(), FillExpression.Scope.valueOf(reqVO.getScope()),
                fieldCodes, "规则【" + reqVO.getName() + "】的");
        reqVO.setFieldCode(StrUtil.trimToEmpty(reqVO.getFieldCode()));
        if (StrUtil.isNotEmpty(reqVO.getFieldCode()) && !fieldCodes.contains(reqVO.getFieldCode())) {
            throw exception(FILL_TABLE_EXPRESSION_INVALID, "规则【" + reqVO.getName() + "】的",
                    "定位字段 " + reqVO.getFieldCode() + " 不存在");
        }
        if (reqVO.getSort() == null) {
            reqVO.setSort(0);
        }
        if (reqVO.getEnabled() == null) {
            reqVO.setEnabled(1);
        }
    }

}
