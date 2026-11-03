package cn.iocoder.yudao.module.governance.dal.mysql.standardcodeset;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.StandardCodeSetPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 数据标准--标准码 Mapper
 *
 * @author Li
 */
@Mapper
public interface StandardCodeSetMapper extends BaseMapperX<StandardCodeSetDO> {

    /**
     * 按指定条件读取并锁定标准代码集记录，供事务内一致性校验或修改使用。
     */
    @Select("SELECT * FROM dh_governance_standard_code_set "
            + "WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    StandardCodeSetDO selectByIdForUpdate(@Param("id") Integer id);

    /**
     * 按类型查询标准代码集。
     */
    default StandardCodeSetDO selectByType(String type) {
        return selectOne(StandardCodeSetDO::getType, type);
    }

    /**
     * 查询标准代码集分页。
     */
    default PageResult<StandardCodeSetDO> selectPage(StandardCodeSetPageReqVO reqVO) {
        var query = new LambdaQueryWrapperX<StandardCodeSetDO>()
                .eqIfPresent(StandardCodeSetDO::getDocumentId, reqVO.getDocumentId())
                .likeIfPresent(StandardCodeSetDO::getName, reqVO.getName())
                .likeIfPresent(StandardCodeSetDO::getType, reqVO.getType())
                .inIfPresent(StandardCodeSetDO::getCategoryId, reqVO.getCategoryIds())
                .eqIfPresent(StandardCodeSetDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(StandardCodeSetDO::getDeletedTime, reqVO.getDeletedTime());
        boolean asc = "asc".equals(reqVO.getSortOrder());
        if ("id".equals(reqVO.getSortField())) {
            query.orderBy(true, asc, StandardCodeSetDO::getId);
        } else {
            query.orderBy(true, asc, StandardCodeSetDO::getCreateTime).orderByDesc(StandardCodeSetDO::getId);
        }
        return selectPage(reqVO, query);
    }

}
