package cn.iocoder.yudao.module.system.api.user;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.system.api.user.dto.*;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserPageReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.dept.DeptDO;
import cn.iocoder.yudao.module.system.framework.security.core.AdminUserOptionRpcFilter;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

/** 服务身份和操作人权限均核验后，沿用真实账号分页及部门数据范围。 */
@RestController @Validated
public class AdminUserOptionApiImpl implements AdminUserOptionApi {
    @Resource private AdminUserService userService;
    @Resource private DeptService deptService;
    @Resource private HttpServletRequest servletRequest;

    @Override
    @PreAuthorize("@ss.hasPermission('governance:whitelist:query')")
    public CommonResult<PageResult<AdminUserOptionRespDTO>> getUserOptionPage(AdminUserOptionPageReqDTO request) {
        AdminUserOptionRpcFilter.requireVerified(servletRequest);
        var query=new UserPageReqVO();
        query.setPageNo(request.getPageNo());query.setPageSize(request.getPageSize());
        query.setUsername(request.getUsername());query.setMobile(request.getMobile());query.setStatus(0);
        var page=userService.getUserPage(query);
        var rows=page.getList().stream().map(user->{
            var target=BeanUtils.toBean(user,AdminUserOptionRespDTO.class);
            DeptDO dept=user.getDeptId()==null?null:deptService.getDept(user.getDeptId());
            target.setDeptName(dept==null?null:dept.getName());return target;
        }).toList();
        return CommonResult.success(new PageResult<>(rows,page.getTotal()));
    }
}
