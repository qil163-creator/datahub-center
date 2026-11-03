package cn.iocoder.yudao.module.dataforge.dal.mysql.fill;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanCategoryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FillPlanCategoryMapper extends BaseMapperX<FillPlanCategoryDO> {

    /** 分类结构修改与计划引用检查共用事务锁，防止并发移动成环或删除仍被引用的分类。 */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended('dataforge:fill-plan-category', 0))")
    String lockCategoryTree();

    /**
     * 查询列表有序。
     *
     * @return 列表有序结果集合
     */
    default List<FillPlanCategoryDO> selectListOrdered() {
        return selectList(new LambdaQueryWrapperX<FillPlanCategoryDO>()
                .orderByAsc(FillPlanCategoryDO::getSort).orderByAsc(FillPlanCategoryDO::getId));
    }

    /**
     * 按父级编号与名称查询填报计划分类。
     *
     * @param parentId 父级编号
     * @param name 名称
     * @return 填报计划分类
     */
    default FillPlanCategoryDO selectByParentIdAndName(Long parentId, String name) {
        return selectFirstOne(FillPlanCategoryDO::getParentId, parentId, FillPlanCategoryDO::getName, name);
    }

    /**
     * 按父级编号查询填报计划分类数量。
     *
     * @param parentId 父级编号
     * @return SELECT数量按父级编号数值
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(FillPlanCategoryDO::getParentId, parentId);
    }

}
