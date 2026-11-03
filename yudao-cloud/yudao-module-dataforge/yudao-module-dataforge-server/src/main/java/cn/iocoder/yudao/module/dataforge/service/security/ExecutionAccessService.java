package cn.iocoder.yudao.module.dataforge.service.security;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_UNAVAILABLE;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.OPERATION_PERMISSION_DENIED;

/**
 * 研发执行护栏。
 * <p>
 * 平台只有一个研发工作空间，当前登录用户的操作权限完全由 Controller 的 {@code @PreAuthorize} 负责；
 * 本服务只处理注解无法覆盖的两类校验：
 * <ul>
 *     <li>无登录上下文的后台执行（调度、对账、自动运行）所使用的已持久化执行身份，是否仍在当前平台内有效且具备平台权限；</li>
 *     <li>研发任务、ETL 流程引用的数据源是否存在且处于启用状态。</li>
 * </ul>
 *
 * @author Li
 */
@Service
public class ExecutionAccessService {

    /** 将持久创建人转换为执行身份；缺失或无效值不能成为匿名任务身份。 */
    public static Long parseExecutionUserId(String creator) {
        if (creator == null || creator.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(creator.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** 研发任务运行权限，发布时选定的执行身份必须持有 */
    public static final String PERMISSION_DEV_TASK_RUN = "dataforge:dev-task:run";

    /** 固定发布执行身份的 SQL 写权限，不能借用发起调度的登录用户身份。 */
    public static final String PERMISSION_DEV_TASK_WRITE = "dataforge:dev-task:write";

    /** 完整原生集群程序执行权限，与任务运行权限分别校验，不能使用 OR。 */
    public static final String PERMISSION_DEV_TASK_NATIVE = "dataforge:dev-task:execute-native";

    /** ETL 流程运行权限 */
    public static final String PERMISSION_ETL_RUN = "dataforge:etl:run";

    /** ETL 流程发布权限；发布人作为调度执行主体，持有运行或发布权限之一即可 */
    public static final String PERMISSION_ETL_RELEASE = "dataforge:etl:release";

    @Resource
    private DatabaseMapper databaseMapper;

    @Resource
    private PermissionApi permissionApi;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 复核已持久化的执行身份：用户仍在当前平台内有效，且拥有任一给定平台权限。
     * 缺少身份时直接失败，后台任务不得回退到匿名执行。
     */
    public void requireUserPermission(Long userId, String... permissions) {
        if (userId == null || permissions == null || permissions.length == 0) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        adminUserApi.validateUser(userId);
        if (!Boolean.TRUE.equals(permissionApi.hasAnyPermissions(userId, permissions).getCheckedData())) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
    }

    /**
     * 校验数据源存在且已启用；停用的数据源不能被研发任务、调试或 ETL 流程使用。
     */
    public void requireDatabase(Long databaseId) {
        if (databaseId == null) {
            throw exception(DATABASE_UNAVAILABLE);
        }
        DatabaseDO database = databaseMapper.selectById(databaseId);
        if (database == null || !Objects.equals(database.getStatus(), 0)) {
            throw exception(DATABASE_UNAVAILABLE);
        }
    }

}
