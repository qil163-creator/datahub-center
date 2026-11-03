package cn.iocoder.yudao.module.governance.dal.mysql.level;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据分级 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyLevelMapper extends BaseMapperX<SafetyLevelDO> {

    /**
     * 查询安全等级分页。
     */
    default PageResult<SafetyLevelDO> selectPage(SafetyLevelPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyLevelDO>()
                .likeIfPresent(SafetyLevelDO::getSafetyName, reqVO.getSafetyName())
                .eqIfPresent(SafetyLevelDO::getStatus, reqVO.getStatus())
                .orderByAsc(SafetyLevelDO::getSort)
                .orderByAsc(SafetyLevelDO::getId));
    }

    /**
     * 按安全等级查询安全等级。
     */
    default SafetyLevelDO selectBySafetyLevel(String safetyLevel) {
        return selectOne(SafetyLevelDO::getSafetyLevel, safetyLevel);
    }

}