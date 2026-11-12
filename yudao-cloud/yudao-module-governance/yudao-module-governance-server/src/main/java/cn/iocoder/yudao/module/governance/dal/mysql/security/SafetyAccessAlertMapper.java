package cn.iocoder.yudao.module.governance.dal.mysql.security;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyAccessAlertPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyAccessAlertDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异常访问告警 Mapper
 */
@Mapper
public interface SafetyAccessAlertMapper extends BaseMapperX<SafetyAccessAlertDO> {

    /**
     * 按用户汇总窗口内的敏感访问审计。系统任务（无用户）与敏感识别采样不计入。
     */
    @Select("SELECT user_id AS userId, MAX(user_name) AS userName, COUNT(*) AS total, "
            + "COUNT(*) FILTER (WHERE masked_flag = 0) AS plaintext, "
            + "COUNT(DISTINCT COALESCE(database_id::text, '') || ':' || COALESCE(table_name, '')) AS tables, "
            + "COUNT(*) FILTER (WHERE EXTRACT(HOUR FROM access_time) >= #{offStart} "
            + "OR EXTRACT(HOUR FROM access_time) < #{offEnd}) AS offHours "
            + "FROM dh_governance_safety_access_log "
            + "WHERE deleted = 0 AND user_id IS NOT NULL AND access_type <> 'SCAN' "
            + "AND access_time >= #{from} AND access_time < #{to} "
            + "GROUP BY user_id")
    List<SafetyAccessUserStat> selectUserStats(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                               @Param("offStart") int offStart, @Param("offEnd") int offEnd);

    /**
     * 分页。
     */
    default PageResult<SafetyAccessAlertDO> selectPage(SafetyAccessAlertPageReqVO req) {
        return selectPage(req, new LambdaQueryWrapperX<SafetyAccessAlertDO>()
                .eqIfPresent(SafetyAccessAlertDO::getStatus, req.getStatus())
                .eqIfPresent(SafetyAccessAlertDO::getRuleCode, req.getRuleCode())
                .eqIfPresent(SafetyAccessAlertDO::getUserId, req.getUserId())
                .orderByDesc(SafetyAccessAlertDO::getWindowStart)
                .orderByDesc(SafetyAccessAlertDO::getId));
    }

    /**
     * 待处理告警按修订号 CAS 处理。
     */
    default int handle(Long id, Integer expectedRevision, String status, Long handlerId, String remark,
                       LocalDateTime time) {
        return update(null, new LambdaUpdateWrapper<SafetyAccessAlertDO>()
                .set(SafetyAccessAlertDO::getStatus, status)
                .set(SafetyAccessAlertDO::getHandlerId, handlerId)
                .set(SafetyAccessAlertDO::getHandleRemark, remark)
                .set(SafetyAccessAlertDO::getHandleTime, time)
                .setSql("revision = revision + 1")
                .eq(SafetyAccessAlertDO::getId, id)
                .eq(SafetyAccessAlertDO::getRevision, expectedRevision)
                .eq(SafetyAccessAlertDO::getStatus, SafetyAccessAlertDO.STATUS_OPEN));
    }
}
