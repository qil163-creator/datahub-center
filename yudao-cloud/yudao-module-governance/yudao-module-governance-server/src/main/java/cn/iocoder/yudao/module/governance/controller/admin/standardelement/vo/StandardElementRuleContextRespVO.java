package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import java.util.List;

public record StandardElementRuleContextRespVO(List<Field> fields, List<ReferenceObject> referenceObjects,
                                               String message) {

    public record Field(String label, String value, Long modelId) {

    }

    public record ReferenceObject(Long id, String name, List<Field> fields) {

    }
}
