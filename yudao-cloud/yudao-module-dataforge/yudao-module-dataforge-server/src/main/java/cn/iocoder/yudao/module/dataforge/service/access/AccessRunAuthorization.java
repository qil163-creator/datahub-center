package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** ACCESS 对象在平台内全可见；当前操作权限及数据源、表权限分别校验。 */
@Component
public class AccessRunAuthorization {

    @Resource
    private ExecutionAccessService access;

    @Resource
    private ObjectProvider<DataAccessExecutor> executor;

    /**
     * 校验操作用户是否拥有指定的数据同步任务权限。
     *
     * @param task 任务记录
     * @param userId 用户编号
     * @param permission 权限
     */
    public void action(DataAccessDO task, Long userId, String permission) {
        access.requireUserPermission(userId, permission);
    }

    /**
     * 按运行引擎校验任务执行及数据源访问权限，并固定执行用户身份。
     *
     * @param snapshot 快照
     * @param userId 用户编号
     */
    public void execution(DataAccessDO snapshot, Long userId) {
        if (userId == null || userId <= 0) {
            throw cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.OPERATION_PERMISSION_DENIED);
        }
        action(snapshot, userId, "dataforge:access:run");
        // 定时入队没有请求上下文。仅在当前用户有效且具备运行权限后恢复持久执行身份。
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var current = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        current.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                .authenticated(new cn.iocoder.yudao.framework.security.core.LoginUser().setId(userId)
                        .setUserType(cn.iocoder.yudao.framework.common.enums.UserTypeEnum.ADMIN.getValue()),
                        null, java.util.List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(current);
        try {
            // 原生 SeaTunnel 作业使用自身连接配置；其他引擎继续校验平台数据源权限。
            if (!Integer.valueOf(2).equals(snapshot.getEngineType())) {
                if (snapshot.getSourceDatabaseId() != null) {
                    access.requireDatabase(snapshot.getSourceDatabaseId());
                }
                if (snapshot.getTargetDatabaseId() != null) {
                    access.requireDatabase(snapshot.getTargetDatabaseId());
                }
            }
            snapshot.setCreator(String.valueOf(userId));
            if (Integer.valueOf(3).equals(snapshot.getEngineType())) {
                verifyApiConfigurations(snapshot);
            }
            executor.getObject().validateExecutionPermission(snapshot);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    /** 接口配置随任务快照冻结，执行时只校验快照自身，不读取其他接口记录。 */
    private void verifyApiConfigurations(DataAccessDO snapshot) {
        var config = cn.hutool.json.JSONUtil.parseObj(snapshot.getApiAccessJson());
        var mappings = config.getJSONArray("mappings");
        if (mappings == null || mappings.isEmpty()) {
            throw changed();
        }
        for (int i = 0; i < mappings.size(); i++) {
            var mapping = mappings.getJSONObject(i).toBean(
                    cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.ApiAccessMappingVO.class);
            if (mapping.getApiConfig() == null || !mapping.getApiConfig().isRequestConfigurationValid()) {
                throw changed();
            }
            if (config.getBool("changeDataSync", false) && !mapping.hasCompletePrimaryKeyMapping()) {
                throw changed();
            }
        }
    }

    /**
     * 发布变更数据同步运行授权。
     *
     * @return 运行时异常
     */
    private static RuntimeException changed() {
        return cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_RUN_DEFINITION_CHANGED);
    }
}
