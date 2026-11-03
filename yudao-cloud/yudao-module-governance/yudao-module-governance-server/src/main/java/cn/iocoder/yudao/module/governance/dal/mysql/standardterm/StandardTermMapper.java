package cn.iocoder.yudao.module.governance.dal.mysql.standardterm;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 数据标准--业务术语 Mapper
 *
 * @author Li
 */
@Mapper
public interface StandardTermMapper extends BaseMapperX<StandardTermDO> {

    /**
     * 查询标准术语分页。
     */
    default PageResult<StandardTermDO> selectTermPage(StandardTermPageReqVO reqVO, List<Long> termCategoryIds) {
        return selectPage(reqVO, new LambdaQueryWrapperX<StandardTermDO>()
                .likeIfPresent(StandardTermDO::getName, reqVO.getName())
                .likeIfPresent(StandardTermDO::getEngName, reqVO.getEngName())
                .eqIfPresent(StandardTermDO::getCategoryId, reqVO.getCategoryId())
                .eqIfPresent(StandardTermDO::getOwnerUserId, reqVO.getOwnerUserId())
                .eqIfPresent(StandardTermDO::getStatus, reqVO.getStatus())
                .orderByDesc(StandardTermDO::getId)
                .in(termCategoryIds != null, StandardTermDO::getTermCategoryId, termCategoryIds));
    }

    default Long selectCountByTermCategoryId(Long categoryId) {
        return selectCount(StandardTermDO::getTermCategoryId, categoryId);
    }

    /**
     * 按名称查询标准术语。
     */
    default StandardTermDO selectByName(String name) {
        return selectOne(StandardTermDO::getName, name);
    }

    /**
     * 查询启用列表。
     */
    default List<StandardTermDO> selectEnabledList(String keyword) {
        return selectList(new LambdaQueryWrapperX<StandardTermDO>()
                .eq(StandardTermDO::getStatus, CommonStatusEnum.ENABLE.getStatus())
                .and(StringUtils.hasText(keyword), query -> query
                        .like(StandardTermDO::getName, keyword)
                        .or().like(StandardTermDO::getAlias, keyword))
                .orderByAsc(StandardTermDO::getName, StandardTermDO::getId)
                .last("LIMIT 50"));
    }

}
