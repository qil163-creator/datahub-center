package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategoryRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategorySaveReqVO;

import java.util.List;
import java.util.Collection;
import java.util.Map;

public interface FillPlanCategoryService {

    /**
     * 创建采集计划分类。
     *
     * @param reqVO 请求参数
     * @return 生成或保存的记录编号
     */
    Long createCategory(FillPlanCategorySaveReqVO reqVO);

    /**
     * 更新采集计划分类。
     *
     * @param reqVO 请求参数
     */
    void updateCategory(FillPlanCategorySaveReqVO reqVO);

    /**
     * 删除采集计划分类，有子分类或计划引用时拒绝。
     *
     * @param id 记录编号
     */
    void deleteCategory(Long id);

    /**
     * 获得采集计划分类。
     *
     * @param id 记录编号
     * @return 填报计划分类响应数据
     */
    FillPlanCategoryRespVO getCategory(Long id);

    /**
     * 获得采集计划分类列表，前端构建目录树。
     *
     * @return 分类列表
     */
    List<FillPlanCategoryRespVO> getCategoryList();

    /**
     * 读取分类名称映射。
     *
     * @param categoryIds 分类编号集合
     * @return 处理后的键值映射
     */
    Map<Long, String> getCategoryNameMap(Collection<Long> categoryIds);

    /** 在计划写入事务结束前校验分类引用；调用方须先完成组织/人员远程查询。 */
    void validateCategoryReference(Long categoryId);

    /** 获取指定分类及所有子孙分类，分类不存在时拒绝查询。 */
    List<Long> getCategoryAndDescendantIds(Long categoryId);

}
