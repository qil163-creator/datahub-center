package cn.iocoder.yudao.module.supply.dal.mysql.apicategory;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.supply.controller.admin.apicategory.vo.ApiCategoryListReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.apicategory.ApiCategoryDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据服务-服务分类 Mapper
 *
 * @author Li
 */
@Mapper
public interface ApiCategoryMapper extends BaseMapperX<ApiCategoryDO> {

    /**
     * 按查询条件读取API分类列表。
     *
     * @param reqVO 请求参数
     * @return 按查询条件读取API分类列表结果集合
     */
    default List<ApiCategoryDO> selectList(ApiCategoryListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<ApiCategoryDO>()
                .likeIfPresent(ApiCategoryDO::getName, reqVO.getName())
                .eqIfPresent(ApiCategoryDO::getParentId, reqVO.getParentId())
                .orderByAsc(ApiCategoryDO::getSort)
                .orderByAsc(ApiCategoryDO::getId));
    }

    /**
     * 按父级编号查询API分类数量。
     *
     * @param parentId 父级编号
     * @return SELECT数量按父级编号数值
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(ApiCategoryDO::getParentId, parentId);
    }

    /**
     * 按父级编号与名称查询API分类。
     *
     * @param parentId 父级编号
     * @param name 名称
     * @return API分类
     */
    default ApiCategoryDO selectByParentIdAndName(Long parentId, String name) {
        return selectOne(new LambdaQueryWrapperX<ApiCategoryDO>()
                .eq(ApiCategoryDO::getParentId, parentId)
                .eq(ApiCategoryDO::getName, name)
                .last("LIMIT 1"));
    }

}
