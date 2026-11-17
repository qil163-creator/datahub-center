package cn.iocoder.yudao.module.governance.service.categorycleanrule;

import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleListReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.categorycleanrule.vo.CategoryCleanRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import jakarta.validation.Valid;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 清洗规则 类目管理 Service 接口
 *
 * @author Li
 */
public interface CategoryCleanRuleService {

    /**
     * 创建清洗规则 类目管理
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createCategoryCleanRule(@Valid CategoryCleanRuleSaveReqVO createReqVO);

    /**
     * 更新清洗规则 类目管理
     *
     * @param updateReqVO 更新信息
     */
    void updateCategoryCleanRule(@Valid CategoryCleanRuleSaveReqVO updateReqVO);

    /**
     * 删除清洗规则 类目管理
     *
     * @param id 编号
     */
    void deleteCategoryCleanRule(Long id);

    void deleteCategoryCleanRuleList(List<Long> ids);


    /**
     * 获得清洗规则 类目管理
     *
     * @param id 编号
     * @return 清洗规则 类目管理
     */
    CategoryCleanRuleDO getCategoryCleanRule(Long id);

    /**
     * 获得清洗规则 类目管理列表
     *
     * @param listReqVO 查询条件
     * @return 清洗规则 类目管理列表
     */
    List<CategoryCleanRuleDO> getCategoryCleanRuleList(CategoryCleanRuleListReqVO listReqVO);

    /** 批量查询清洗规则所属类目的名称。 */
    Map<Long, String> getCategoryNames(Collection<Long> ids);

}
