package cn.iocoder.yudao.module.governance.dal.mysql.identify;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.identify.vo.SafetyScanRunPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyScanRunDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感数据扫描执行记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyScanRunMapper extends BaseMapperX<SafetyScanRunDO> {

    /**
     * 查询安全扫描运行分页。
     */
    default PageResult<SafetyScanRunDO> selectPage(SafetyScanRunPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyScanRunDO>()
                .eqIfPresent(SafetyScanRunDO::getDatabaseId, reqVO.getDatabaseId())
                .eqIfPresent(SafetyScanRunDO::getRunStatus, reqVO.getRunStatus())
                .orderByDesc(SafetyScanRunDO::getId));
    }

    /**
     * 按数据库编号查询运行中。
     */
    default SafetyScanRunDO selectRunningByDatabaseId(Long databaseId) {
        return selectOne(new LambdaQueryWrapperX<SafetyScanRunDO>()
                .eq(SafetyScanRunDO::getDatabaseId, databaseId)
                .eq(SafetyScanRunDO::getRunStatus, SafetyScanRunDO.RUN_STATUS_RUNNING)
                .last("LIMIT 1"));
    }

}
