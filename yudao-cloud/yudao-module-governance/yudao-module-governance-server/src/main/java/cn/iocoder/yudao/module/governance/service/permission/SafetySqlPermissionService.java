package cn.iocoder.yudao.module.governance.service.permission;

import cn.iocoder.yudao.module.governance.dal.mysql.permission.SafetyFieldPermissionMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.permission.SafetyTablePermissionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import static cn.iocoder.yudao.module.governance.service.permission.SafetyPermissionBindingResolver.*;

/** 仅供权限管理页写策略预检检查历史配置绑定，不参与业务查询或执行。 */
@Service
public class SafetySqlPermissionService {

    @Resource
    private SafetyTablePermissionMapper tablePermissionMapper;

    @Resource
    private SafetyFieldPermissionMapper fieldPermissionMapper;

    void requireBoundPolicies(Long databaseId) {
        requireDatabase(databaseId);
        Long tables = tablePermissionMapper.countActiveUnbound(databaseId);
        Long fields = fieldPermissionMapper.countActiveUnbound(databaseId);
        if (tables == null || fields == null || tables != 0 || fields != 0) {
            throw rejected("该数据源存在有效但未核验 schema 的历史策略；请在治理权限页逐组核验绑定");
        }
    }
}
