package cn.iocoder.yudao.module.governance.service.standardcodeset;

import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset.StandardCodeSetMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.modeling.api.model.ModelFieldApi;
import cn.iocoder.yudao.module.modeling.api.model.dto.ModelStandardFieldRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_CODE_SET_NOT_EXISTS;

/** 查询直接绑定代码表或通过数据元间接关联代码表的当前模型字段。 */
@Service
@RequiredArgsConstructor
public class CodeSetModelAssociationService {

    private final StandardCodeSetMapper codeSets;
    private final StandardElementMapper elements;
    private final ModelFieldApi modelFields;

    public List<ModelStandardFieldRespDTO> getFields(Integer codeSetId) {
        if (codeSetId == null || codeSets.selectById(codeSetId) == null) {
            throw exception(STANDARD_CODE_SET_NOT_EXISTS);
        }
        Set<Long> elementIds = elements.selectList(StandardElementDO::getCodeSetId, codeSetId).stream()
                .map(StandardElementDO::getId).map(Integer::longValue).collect(Collectors.toSet());
        // 由建模服务读取当前字段，失败时向调用方报告，不能将失败显示为无关联。
        Map<String, ModelStandardFieldRespDTO> result = new LinkedHashMap<>();
        modelFields.getFieldListByCodeTableId(codeSetId.longValue()).getCheckedData()
                .forEach(field -> result.put(fieldKey(field), field));
        if (!elementIds.isEmpty()) {
            modelFields.getStandardBoundFieldList().getCheckedData().stream()
                    .filter(field -> field.getStandardId() != null && elementIds.contains(field.getStandardId()))
                    .forEach(field -> result.putIfAbsent(fieldKey(field), field));
        }
        return List.copyOf(result.values());
    }

    private static String fieldKey(ModelStandardFieldRespDTO field) {
        String identifier = field.getFieldUid() == null || field.getFieldUid().isBlank()
                ? field.getFieldName() : field.getFieldUid();
        return field.getModelId() + ":" + identifier;
    }
}
