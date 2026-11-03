package cn.iocoder.yudao.module.modeling.dal.mysql.planning;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory.BizCategoryListReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.BizCategoryDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 业务分类 Mapper
 *
 * @author Li
 */
@Mapper
public interface BizCategoryMapper extends BaseMapperX<BizCategoryDO> {

    /**
     * 按查询条件读取业务分类列表。
     *
     * @param reqVO 请求参数
     * @return 按查询条件读取业务分类列表结果集合
     */
    default List<BizCategoryDO> selectList(BizCategoryListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<BizCategoryDO>()
                .likeIfPresent(BizCategoryDO::getName, reqVO.getName())
                .eqIfPresent(BizCategoryDO::getStatus, reqVO.getStatus())
                .orderByAsc(BizCategoryDO::getId));
    }

    /**
     * 按父级编号查询业务分类数量。
     *
     * @param parentId 父级编号
     * @return SELECT数量按父级编号数值
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(BizCategoryDO::getParentId, parentId);
    }

    /**
     * 按父级编号与名称查询业务分类。
     *
     * @param parentId 父级编号
     * @param name 名称
     * @return 业务分类
     */
    default BizCategoryDO selectByParentIdAndName(Long parentId, String name) {
        // 用 selectFirstOne（内部 selectList）避开 selectOne 的 Cursor 路径，PG 下会报 statement 已关闭
        return selectFirstOne(BizCategoryDO::getParentId, parentId, BizCategoryDO::getName, name);
    }

}
