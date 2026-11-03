package cn.iocoder.yudao.framework.dbswitch.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import cn.iocoder.yudao.framework.dbswitch.common.security.TablePermissionService;

import java.util.List;

/**
 * 数据权限参数,封装项目 ID、角色列表与表权限服务,用于 SQL 查询执行时的表级权限校验。
 *
 * @ClassName DataSecurityParam
 * @Author zrx
 * @Date 2025/8/7 17:30
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DataSecurityParam {

    private Long projectId;

    private List<Long> roleIds;

    private TablePermissionService tablePermissionService;
}
