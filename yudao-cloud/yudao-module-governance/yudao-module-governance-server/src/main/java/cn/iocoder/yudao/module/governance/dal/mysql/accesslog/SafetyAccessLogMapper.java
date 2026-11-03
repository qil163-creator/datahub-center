package cn.iocoder.yudao.module.governance.dal.mysql.accesslog;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.accesslog.vo.SafetyAccessLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.accesslog.SafetyAccessLogDO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 敏感数据访问审计 Mapper
 *
 * @author Li
 */
@Mapper
public interface SafetyAccessLogMapper extends BaseMapperX<SafetyAccessLogDO> {

    /**
     * 查询安全访问日志分页。
     */
    default PageResult<SafetyAccessLogDO> selectPage(SafetyAccessLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SafetyAccessLogDO>()
                .eqIfPresent(SafetyAccessLogDO::getUserId, reqVO.getUserId())
                .likeIfPresent(SafetyAccessLogDO::getUserName, reqVO.getUserName())
                .eqIfPresent(SafetyAccessLogDO::getAccessType, reqVO.getAccessType())
                .likeIfPresent(SafetyAccessLogDO::getResourceName, reqVO.getResourceName())
                .likeIfPresent(SafetyAccessLogDO::getTableName, reqVO.getTableName())
                .eqIfPresent(SafetyAccessLogDO::getMaskedFlag, reqVO.getMaskedFlag())
                .betweenIfPresent(SafetyAccessLogDO::getAccessTime, reqVO.getAccessTime())
                .orderByDesc(SafetyAccessLogDO::getId));
    }

    /**
     * 查询安全访问日志数量起始时间。
     */
    default Long selectCountSince(LocalDateTime since) {
        return selectCount(new LambdaQueryWrapperX<SafetyAccessLogDO>()
                .ge(SafetyAccessLogDO::getAccessTime, since));
    }

    /**
     * 查询豁免数量起始时间。
     */
    default Long selectExemptCountSince(LocalDateTime since) {
        return selectCount(new LambdaQueryWrapperX<SafetyAccessLogDO>()
                .ge(SafetyAccessLogDO::getAccessTime, since)
                .eq(SafetyAccessLogDO::getMaskedFlag, SafetyAccessLogDO.MASKED_NO));
    }

    /**
     * 按天统计访问趋势（PostgreSQL to_char）
     */
    default List<Map<String, Object>> selectTrendSince(LocalDateTime since) {
        return selectMaps(new QueryWrapper<SafetyAccessLogDO>()
                .select("to_char(access_time, 'YYYY-MM-DD') AS day",
                        "COUNT(*) AS total",
                        "SUM(CASE WHEN masked_flag = 0 THEN 1 ELSE 0 END) AS exempt")
                .ge("access_time", since)
                .groupBy("to_char(access_time, 'YYYY-MM-DD')")
                .orderByAsc("to_char(access_time, 'YYYY-MM-DD')"));
    }

    /**
     * 访问次数 Top 资源
     */
    default List<Map<String, Object>> selectTableAccessStats(LocalDateTime since) {
        return selectMaps(new QueryWrapper<SafetyAccessLogDO>()
                .select("database_id AS databaseId", "table_name AS tableName",
                        "COUNT(*) AS cnt", "MAX(access_time) AS lastAccess")
                .ge("access_time", since)
                .isNotNull("table_name")
                .ne("table_name", "")
                .groupBy("database_id", "table_name"));
    }

}
