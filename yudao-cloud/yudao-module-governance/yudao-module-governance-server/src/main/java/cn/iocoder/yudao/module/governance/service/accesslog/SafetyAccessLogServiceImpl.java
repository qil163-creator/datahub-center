package cn.iocoder.yudao.module.governance.service.accesslog;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyAccessLogCreateReqDTO;
import cn.iocoder.yudao.module.governance.controller.admin.accesslog.vo.SafetyAccessLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.accesslog.SafetyAccessLogDO;
import cn.iocoder.yudao.module.governance.dal.mysql.accesslog.SafetyAccessLogMapper;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

/**
 * 敏感数据访问审计 Service 实现类
 *
 * @author Li
 */
@Slf4j
@Service
@Validated
public class SafetyAccessLogServiceImpl implements SafetyAccessLogService {

    @Resource
    private SafetyAccessLogMapper accessLogMapper;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 记录一条访问审计日志（异常自吞，不影响主流程）
     */
    @Override
    public void recordAccessLog(SafetyAccessLogCreateReqDTO reqDTO) {
        try {
            SafetyAccessLogDO accessLog = BeanUtils.toBean(reqDTO, SafetyAccessLogDO.class);
            if (accessLog.getAccessTime() == null) {
                accessLog.setAccessTime(LocalDateTime.now());
            }
            // 上报方只带用户编号时，补全用户名
            if (accessLog.getUserId() != null && StrUtil.isBlank(accessLog.getUserName())) {
                try {
                    AdminUserRespDTO user = adminUserApi.getUser(accessLog.getUserId()).getCheckedData();
                    if (user != null) {
                        accessLog.setUserName(user.getNickname());
                    }
                } catch (Exception ex) {
                    log.warn("[recordAccessLog][补全用户名失败：{}]", ex.getMessage());
                }
            }
            if (accessLog.getMaskedFlag() == null) {
                accessLog.setMaskedFlag(SafetyAccessLogDO.MASKED_YES);
            }
            if (accessLog.getColumnCount() == null) {
                accessLog.setColumnCount(0);
            }
            accessLogMapper.insert(accessLog);
        } catch (Exception ex) {
            log.warn("[recordAccessLog][审计日志写入失败：{}]", ex.getMessage());
        }
    }

    /**
     * 获得访问审计分页。
     */
    @Override
    public PageResult<SafetyAccessLogDO> getAccessLogPage(SafetyAccessLogPageReqVO pageReqVO) {
        return accessLogMapper.selectPage(pageReqVO);
    }

}
