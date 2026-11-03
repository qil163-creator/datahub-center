package cn.iocoder.yudao.framework.dbswitch.common.security;

import java.util.List;
import java.util.Map;

/**
 * 数据安全权限能力的最小抽象。
 *
 * dbswitch 只需要这两个方法，不需要依赖完整的业务 Service 层继承链。
 *
 * 迁移自数睿通 net.srt.framework.common.dbswitch.TablePermissionService
 */
public interface TablePermissionService {

    /**
     * 校验受限 SQL 的授权边界后执行数据库操作。
     */
    List<Map<String, Object>> executeSecureSql(String sql, List<Long> roleIds, List<Map<String, Object>> originalResult,
            Long projectId);

    /**
     * 检查非查询语句是否属于当前允许执行的语法范围。
     */
    void validateNonSelectStatement(String sql, List<Long> roleIds, Long projectId);
}
