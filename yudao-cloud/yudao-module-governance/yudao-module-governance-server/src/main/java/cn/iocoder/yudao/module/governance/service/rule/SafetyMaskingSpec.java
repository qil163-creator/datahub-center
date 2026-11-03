package cn.iocoder.yudao.module.governance.service.rule;

import cn.iocoder.yudao.module.governance.dal.dataobject.replaceitem.SafetyReplaceItemDO;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 一次查询内冻结的脱敏规则版本。不随规则当前指针变化。
 */
@Value
@Builder
public class SafetyMaskingSpec {

    Long ruleId;

    Long versionId;

    Integer versionNo;

    String methodCode;

    Map<String, Object> config;

    List<SafetyReplaceItemDO> replaceItems;
}
