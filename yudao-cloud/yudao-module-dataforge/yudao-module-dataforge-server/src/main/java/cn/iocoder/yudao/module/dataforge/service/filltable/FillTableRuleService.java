package cn.iocoder.yudao.module.dataforge.service.filltable;

import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableRuleRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.filltable.vo.FillTableRuleSaveReqVO;

import java.util.List;

/**
 * 数据填报-填报表校验规则 Service
 *
 * @author Li
 */
public interface FillTableRuleService {

    /** 创建规则（填报表须未发布） */
    Long createRule(FillTableRuleSaveReqVO reqVO);

    /** 更新规则（填报表须未发布） */
    void updateRule(FillTableRuleSaveReqVO reqVO);

    /** 删除规则（填报表须未发布） */
    void deleteRule(Long id);

    /** 填报表的全部规则 */
    List<FillTableRuleRespVO> getRuleListByTableId(Long tableId);

}
