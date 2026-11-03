package cn.iocoder.yudao.module.modeling.api.model;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelColumnMapper;
import org.springframework.security.access.AccessDeniedException;
import cn.iocoder.yudao.module.modeling.api.model.dto.ModelStandardFieldRespDTO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import cn.iocoder.yudao.module.modeling.service.model.LogicModelColumnService;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 模型字段 RPC：扫描逻辑模型字段表，返回绑定了指定数据标准的字段（治理侧调用）
 */
@RestController
@Validated
public class ModelFieldApiImpl implements ModelFieldApi, ModelRuleContextApi {

    @Resource
    private LogicModelMapper logicModelMapper;

    @Resource
    private DataDomainMapper dataDomains;

    @Resource
    private SubjectDomainMapper subjectDomains;

    @Resource
    private LogicModelColumnService columnService;

    @Resource
    private LogicModelColumnMapper columnMapper;

    @Resource
    private SecurityFrameworkService security;

    @Resource
    private jakarta.servlet.http.HttpServletRequest request;

    /**
     * 读取规则上下文字段列表。
     *
     * @param standardId 标准编号
     * @return 规则上下文字段列表结果集合
     */
    @Override
    public CommonResult<List<ModelStandardFieldRespDTO>> getRuleContextFieldList(Long standardId) {
        cn.iocoder.yudao.module.modeling.framework.security.ModelRuleContextRpcFilter.requireVerified(request);
        if (!security.hasPermission("modeling:model:query")) {
            throw new AccessDeniedException("无模型查询权限");
        }
        if (standardId == null) {
            return success(List.of());
        }
        // 只查询引用了该数据元的模型，避免全库扫描并暴露无关模型字段。
        List<Long> modelIds = columnMapper.selectListByStandardId(standardId).stream()
                .map(column -> column.getModelId()).filter(Objects::nonNull).distinct().toList();
        if (modelIds.isEmpty()) {
            return success(List.of());
        }
        List<ModelStandardFieldRespDTO> result = new ArrayList<>();
        for (LogicModelDO model : logicModelMapper.selectByIds(modelIds)) {
            List<ModelFieldItem> fields = columnService.listByModelId(model.getId());
            if (fields == null || fields.stream().filter(Objects::nonNull)
                    .noneMatch(field -> Objects.equals(field.getStandardId(), standardId))) {
                continue;
            }
            fields.stream().filter(Objects::nonNull).map(field -> toField(model, field)).forEach(result::add);
        }
        return success(result);
    }

    /**
     * 查询绑定了指定数据标准的模型字段（读取当前模型字段）。
     *
     * @param standardId 标准编号
     * @return 绑定了指定数据标准的模型字段（读取当前模型字段）结果集合
     */
    @Override
    public CommonResult<List<ModelStandardFieldRespDTO>> getFieldListByStandardId(Long standardId) {
        if (standardId == null) {
            return success(List.of());
        }
        return success(collectStandardBoundFields(field -> Objects.equals(field.getStandardId(), standardId)));
    }

    @Override
    public CommonResult<List<ModelStandardFieldRespDTO>> getFieldListByCodeTableId(Long codeTableId) {
        if (codeTableId == null) return success(List.of());
        return success(collectStandardBoundFields(field -> Objects.equals(field.getCodeTableId(), codeTableId)));
    }

    /**
     * 查询绑定了任意数据标准的模型字段（读取当前模型字段，供全局落标对账）。
     *
     * @return 标准边界字段列表
     */
    @Override
    public CommonResult<List<ModelStandardFieldRespDTO>> getStandardBoundFieldList() {
        return success(collectStandardBoundFields(field -> field.getStandardId() != null));
    }

    /**
     * 收集标准边界字段。
     *
     * @param predicate 判断条件
     * @return 收集标准边界字段结果集合
     */
    private List<ModelStandardFieldRespDTO> collectStandardBoundFields(Predicate<ModelFieldItem> predicate) {
        List<ModelStandardFieldRespDTO> result = new ArrayList<>();
        Map<Long, String> domainNames = new HashMap<>();
        Map<Long, String> subjectNames = new HashMap<>();
        for (LogicModelDO model : logicModelMapper.selectList()) {
            List<ModelFieldItem> fields = columnService.listByModelId(model.getId());
            if (fields == null || fields.isEmpty()) {
                continue;
            }
            var matched = fields.stream().filter(Objects::nonNull).filter(predicate).toList();
            if (matched.isEmpty()) {
                continue;
            }
            String domainName = model.getDataDomainId() == null ? null : domainNames.computeIfAbsent(model.getDataDomainId(), id -> {
                var domain = dataDomains.selectById(id);
                return domain == null ? null : domain.getName();
            });
            String subjectName = model.getSubjectDomainId() == null ? null : subjectNames.computeIfAbsent(model.getSubjectDomainId(), id -> {
                var subject = subjectDomains.selectById(id);
                return subject == null ? null : subject.getName();
            });
            for (ModelFieldItem field : matched) {
                var item = toField(model, field);
                item.setDescription(model.getDescription());
                item.setDataDomainName(domainName);
                item.setSubjectDomainName(subjectName);
                item.setSourceInfo(model.getDbName());
                item.setCreator(model.getCreator());
                item.setCreateTime(model.getCreateTime());
                item.setEnabled(model.getEnabled());
                item.setTableType(model.getTableType());
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 转换为模型字段的字段。
     *
     * @param model 模型
     * @param field 字段
     * @return 模型标准字段响应数据
     */
    private ModelStandardFieldRespDTO toField(LogicModelDO model, ModelFieldItem field) {
        ModelStandardFieldRespDTO dto = new ModelStandardFieldRespDTO();
        dto.setModelId(model.getId());
        dto.setModelStatus(model.getStatus());
        dto.setDesignModelId(model.getId());
        dto.setModelName(model.getName());
        dto.setTableName(model.getCode());
        dto.setFieldUid(field.getFieldUid());
        dto.setFieldName(field.getFieldName());
        dto.setCnName(field.getCnName());
        dto.setDataType(field.getDataType());
        dto.setDataLength(field.getDataLength());
        dto.setStandardId(field.getStandardId());
        return dto;
    }
}
