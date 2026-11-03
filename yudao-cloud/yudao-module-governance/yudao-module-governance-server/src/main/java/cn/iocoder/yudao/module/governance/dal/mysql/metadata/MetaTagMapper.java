package cn.iocoder.yudao.module.governance.dal.mysql.metadata;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTagDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-元数据-标签 Mapper
 *
 * @author Li
 */
@Mapper
public interface MetaTagMapper extends BaseMapperX<MetaTagDO> {

    /** 与绑定、删除共用标签行锁，避免删除检查和新绑定交错。 */
    default MetaTagDO selectByIdForUpdate(Long id) {
        return selectOne(new LambdaQueryWrapperX<MetaTagDO>()
                .eq(MetaTagDO::getId, id).last("FOR UPDATE"));
    }

    /**
     * 按名称查询元数据标签。
     */
    default MetaTagDO selectByName(String name) {
        return selectOne(new LambdaQueryWrapperX<MetaTagDO>()
                .eq(MetaTagDO::getName, name)
                .last("LIMIT 1"));
    }

    /**
     * 查询元数据标签分页。
     */
    default PageResult<MetaTagDO> selectPage(MetaTagPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MetaTagDO>()
                .likeIfPresent(MetaTagDO::getName, reqVO.getName())
                .eqIfPresent(MetaTagDO::getCategory, reqVO.getCategory())
                .orderByDesc(MetaTagDO::getId));
    }

}
