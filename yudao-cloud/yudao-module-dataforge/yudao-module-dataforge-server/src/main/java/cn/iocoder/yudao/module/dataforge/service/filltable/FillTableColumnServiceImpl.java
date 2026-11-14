package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableColumnSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableColumnDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable.FillTableDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableColumnMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.filltable.FillTableMapper;
import cn.iocoder.yudao.module.dataforge.service.fill.FillFieldRuleValidator;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.regex.Pattern;

import cn.hutool.core.util.StrUtil;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据填报-填报表字段 Service 实现类
 *
 * 已发布的填报表不允许增删改字段（物理表结构不做在线变更，需先取消发布重建）
 *
 * @author Li
 */
@Service
@Validated
public class FillTableColumnServiceImpl implements FillTableColumnService {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

    /** 系统来源列保留名（发布建表自动追加，用户字段不得占用） */
    private static final java.util.Set<String> RESERVED_COLUMN_NAMES = java.util.Set.of(
            "source_org_id", "period_key", "source_submission_id", "source_row_id");

    @Resource
    private FillTableColumnMapper columnMapper;

    @Resource
    private FillTableMapper fillTableMapper;

    @Resource
    private DictDataApi dictDataApi;

    /**
     * 创建填报表字段。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createColumn(FillTableColumnSaveReqVO createReqVO) {
        validateTableEditable(createReqVO.getTableId());
        validateColumn(createReqVO);
        FillTableColumnDO column = BeanUtils.toBean(createReqVO, FillTableColumnDO.class);
        fillDefaults(column);
        // 永久字段码：创建时取字段名，此后改名不再变更（公式/操作日志/来源追踪只引用 fieldCode）
        column.setFieldCode(column.getName());
        columnMapper.insert(column);
        return column.getId();
    }

    /**
     * 更新填报表字段。
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateColumn(FillTableColumnSaveReqVO updateReqVO) {
        FillTableColumnDO old = columnMapper.selectById(updateReqVO.getId());
        if (old == null) {
            throw exception(FILL_TABLE_COLUMN_NOT_EXISTS);
        }
        validateTableEditable(old.getTableId());
        updateReqVO.setTableId(old.getTableId());
        validateColumn(updateReqVO);
        FillTableColumnDO update = BeanUtils.toBean(updateReqVO, FillTableColumnDO.class);
        // 字段码永久不可变：改名也保留首次分配的 fieldCode；存量空值兜底取当前字段名
        update.setFieldCode(old.getFieldCode() != null ? old.getFieldCode() : old.getName());
        columnMapper.updateById(update);
        // updateById 忽略空值：可清空的数值配置单独显式写入
        columnMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(FillTableColumnDO.class)
                .set(FillTableColumnDO::getRefDatabaseId, updateReqVO.getRefDatabaseId())
                .set(FillTableColumnDO::getColumnWidth, updateReqVO.getColumnWidth())
                .eq(FillTableColumnDO::getId, old.getId()));
    }

    /**
     * 删除填报表字段。
     *
     * @param id 记录编号
     */
    @Override
    public void deleteColumn(Long id) {
        FillTableColumnDO column = columnMapper.selectById(id);
        if (column == null) {
            throw exception(FILL_TABLE_COLUMN_NOT_EXISTS);
        }
        validateTableEditable(column.getTableId());
        columnMapper.deleteById(id);
    }

    /**
     * 获得填报表的字段列表（按排序）。
     *
     * @param tableId 表编号
     * @return 填报表的字段列表（按排序）结果集合
     */
    @Override
    public List<FillTableColumnRespVO> getColumnListByTableId(Long tableId) {
        return BeanUtils.toBean(columnMapper.selectListByTableId(tableId), FillTableColumnRespVO.class);
    }

    /**
     * 校验表可编辑。
     *
     * @param tableId 表编号
     */
    private void validateTableEditable(Long tableId) {
        FillTableDO fillTable = fillTableMapper.selectById(tableId);
        if (fillTable == null) {
            throw exception(FILL_TABLE_NOT_EXISTS);
        }
        if (fillTable.getStatus() != null && fillTable.getStatus() == 1) {
            throw exception(FILL_TABLE_RELEASED_CANNOT_MODIFY);
        }
    }

    /**
     * 校验列。
     *
     * @param reqVO 请求参数
     */
    private void validateColumn(FillTableColumnSaveReqVO reqVO) {
        if (!NAME_PATTERN.matcher(reqVO.getName()).matches()
                || RESERVED_COLUMN_NAMES.contains(reqVO.getName())) {
            throw exception(FILL_TABLE_COLUMN_NAME_INVALID);
        }
        FillTableColumnDO exists = columnMapper.selectByTableIdAndName(reqVO.getTableId(), reqVO.getName());
        if (exists != null && !exists.getId().equals(reqVO.getId())) {
            throw exception(FILL_TABLE_COLUMN_NAME_DUPLICATE);
        }
        normalizeRules(reqVO);
        String ruleError = FillFieldRuleValidator.checkDefinition(reqVO.getFieldType(), reqVO.getMinValue(),
                reqVO.getMaxValue(), reqVO.getRegexPattern(), reqVO.getRuleSeverity());
        if (ruleError != null) {
            throw exception(FILL_TABLE_COLUMN_RULE_INVALID, ruleError);
        }
        String keyError = cn.iocoder.yudao.module.dataforge.service.fill.FillTableVersionServiceImpl.generatedKeyError(
                reqVO.getPk(), reqVO.getPkType(), reqVO.getFieldType(), reqVO.getFieldLength());
        if (keyError != null) {
            throw exception(FILL_TABLE_COLUMN_RULE_INVALID, "该字段" + keyError);
        }
        validateFormula(reqVO);
        boolean select = reqVO.getFillType() != null && (reqVO.getFillType() == 2 || reqVO.getFillType() == 3);
        if (reqVO.getRefDatabaseId() != null) {
            if (!select || StrUtil.isNotBlank(reqVO.getDictType())) {
                throw exception(FILL_TABLE_REF_SOURCE_INVALID, reqVO.getCnName(), "数据表取值只用于下拉选择或多选，且不能同时绑定字典");
            }
            if (StrUtil.isBlank(reqVO.getRefTableName()) || StrUtil.isBlank(reqVO.getRefValueColumn())) {
                throw exception(FILL_TABLE_REF_SOURCE_INVALID, reqVO.getCnName(), "请选择来源表和取值列");
            }
        } else if (StrUtil.isNotBlank(reqVO.getRefParentField())) {
            throw exception(FILL_TABLE_REF_SOURCE_INVALID, reqVO.getCnName(), "级联只支持数据表取值的下拉字段");
        }
        if (reqVO.getUniqueFlag() != null && reqVO.getUniqueFlag() != 0 && reqVO.getUniqueFlag() != 1) {
            throw exception(FILL_TABLE_COLUMN_RULE_INVALID, "唯一规则只能是开启或关闭");
        }
        if (StrUtil.isNotBlank(reqVO.getDictType())) {
            var items = dictDataApi.getDictDataList(reqVO.getDictType()).getCheckedData();
            boolean usable = items != null && items.stream()
                    .anyMatch(item -> item.getStatus() != null && item.getStatus() == 0
                            && StrUtil.isNotBlank(item.getLabel()));
            if (!usable) {
                throw exception(FILL_TABLE_COLUMN_DICT_INVALID, reqVO.getDictType());
            }
            // 选择值来自字典时，录入类型为“选择”或“多选”
            if (reqVO.getFillType() == null || reqVO.getFillType() != 3) {
                reqVO.setFillType(2);
            }
        }
    }

    /**
     * 公式：语法、引用字段（本表已有字段）与自引用；完整的循环引用检查在发布时进行。
     */
    private void validateFormula(FillTableColumnSaveReqVO reqVO) {
        if (StrUtil.isBlank(reqVO.getFormulaExpr())) {
            return;
        }
        String label = "字段【" + reqVO.getCnName() + "】的公式";
        if (reqVO.getPk() != null && reqVO.getPk() == 1) {
            throw exception(FILL_TABLE_EXPRESSION_INVALID, label, "主键字段不能设置公式");
        }
        FillTableColumnDO old = reqVO.getId() == null ? null : columnMapper.selectById(reqVO.getId());
        String selfCode = old != null && old.getFieldCode() != null ? old.getFieldCode() : reqVO.getName();
        java.util.Set<String> fieldCodes = new java.util.HashSet<>(cn.iocoder.yudao.framework.common.util.collection
                .CollectionUtils.convertSet(columnMapper.selectListByTableId(reqVO.getTableId()),
                        FillTableColumnDO::getFieldCode));
        fieldCodes.add(selfCode);
        var expression = cn.iocoder.yudao.module.dataforge.service.fill.FillTemplateExpressions.compile(
                reqVO.getFormulaExpr(), cn.iocoder.yudao.module.dataforge.service.fill.FillExpression.Scope.FORMULA,
                fieldCodes, label);
        if (expression.fieldRefs().contains(selfCode)) {
            throw exception(FILL_TABLE_EXPRESSION_INVALID, label, "不能引用字段自身");
        }
    }

    /**
     * 规整规则字段：去除首尾空白；空值统一存空串，使更新时能清除旧规则（updateById 会忽略 null）。
     */
    private void normalizeRules(FillTableColumnSaveReqVO reqVO) {
        reqVO.setMinValue(StrUtil.trimToEmpty(reqVO.getMinValue()));
        reqVO.setMaxValue(StrUtil.trimToEmpty(reqVO.getMaxValue()));
        reqVO.setRegexPattern(StrUtil.trimToEmpty(reqVO.getRegexPattern()));
        reqVO.setRegexMessage(StrUtil.trimToEmpty(reqVO.getRegexMessage()));
        reqVO.setDictType(StrUtil.trimToEmpty(reqVO.getDictType()));
        reqVO.setFormulaExpr(StrUtil.trimToEmpty(reqVO.getFormulaExpr()));
        reqVO.setHeaderGroup(StrUtil.trimToEmpty(reqVO.getHeaderGroup()));
        reqVO.setRefTableName(StrUtil.trimToEmpty(reqVO.getRefTableName()));
        reqVO.setRefValueColumn(StrUtil.trimToEmpty(reqVO.getRefValueColumn()));
        reqVO.setRefParentField(StrUtil.trimToEmpty(reqVO.getRefParentField()));
        reqVO.setRefParentColumn(StrUtil.trimToEmpty(reqVO.getRefParentColumn()));
        reqVO.setRefScope(StrUtil.trimToEmpty(reqVO.getRefScope()));
        if (reqVO.getUniqueFlag() == null) {
            reqVO.setUniqueFlag(0);
        }
        if (reqVO.getRuleSeverity() == null) {
            reqVO.setRuleSeverity(FillFieldRuleValidator.SEVERITY_ERROR);
        }
    }

    /**
     * 补充默认值。
     *
     * @param column 列
     */
    private void fillDefaults(FillTableColumnDO column) {
        if (column.getFillType() == null) {
            column.setFillType(1);
        }
        if (column.getFillHide() == null) {
            column.setFillHide(0);
        }
        if (column.getNullable() == null) {
            column.setNullable(1);
        }
        if (column.getIfQuery() == null) {
            column.setIfQuery(0);
        }
        if (column.getPk() == null) {
            column.setPk(0);
        }
        if (column.getSort() == null) {
            Long count = columnMapper.selectCount(FillTableColumnDO::getTableId, column.getTableId());
            column.setSort(count == null ? 0 : count.intValue());
        }
    }

}
