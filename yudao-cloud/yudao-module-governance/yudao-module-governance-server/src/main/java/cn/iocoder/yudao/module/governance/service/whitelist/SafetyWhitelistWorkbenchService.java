package cn.iocoder.yudao.module.governance.service.whitelist;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataRespVO;
import cn.iocoder.yudao.module.governance.dal.mysql.whitelist.*;
import cn.iocoder.yudao.module.governance.dal.mysql.subject.SafetyWhitelistSubjectMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.*;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

@Service
@Validated
public class SafetyWhitelistWorkbenchService {

    @Resource
    private SafetyWhitelistWorkbenchMapper mapper;

    @Resource
    private SafetyWhitelistMapper nativeMapper;

    @Resource
    private SafetyWhitelistSubjectMapper subjects;

    @Resource
    private SafetyCategoryMapper categories;

    @Resource
    private CategoryDataMapper directories;

    @Resource
    private SafetyWhitelistWorkbenchWriter writer;

    @Resource
    private AdminUserApi users;

    @Resource
    private cn.iocoder.yudao.module.system.api.user.AdminUserOptionApi userOptionsApi;

    /**
     * 分类白名单分页。
     */
    public PageResult<SafetyWhitelistWorkbenchRespVO> page(@Valid SafetyWhitelistWorkbenchPageReqVO req) {
        var rows = mapper.page(req, (long) (req.getPageNo() - 1) * req.getPageSize());
        return new PageResult<>(rows.stream().map(this::project).toList(), mapper.count(req));
    }

    /**
     * 分类白名单详情。
     */
    public SafetyWhitelistWorkbenchRespVO get(Long id) {
        var row = mapper.get(id);
        if (row == null) {
            throw exception(WHITELIST_NOT_EXISTS);
        }
        return project(row);
    }

    /**
     * 配置分类范围白名单并记录本地授权依据。
     */
    public Long create(@Valid SafetyWhitelistWorkbenchSaveReqVO req) {
        validateUsers(req.getUserIds());
        return writer.save(req, true);
    }

    /**
     * 修改分类范围白名单。
     */
    public void update(@Valid SafetyWhitelistWorkbenchSaveReqVO req) {
        validateUsers(req.getUserIds());
        writer.save(req, false);
    }

    /**
     * 变更脱敏白名单工作台的状态。
     */
    public void changeStatus(@Valid SafetyWhitelistWorkbenchStatusReqVO req) {
        writer.changeStatus(req);
    }

    /**
     * 删除停用分类白名单。
     */
    public void delete(@Valid SafetyWhitelistWorkbenchVersionReqVO req) {
        writer.delete(req);
    }

    /**
     * 原子批量删除停用分类白名单。
     */
    public void deleteList(@Valid SafetyWhitelistWorkbenchBatchReqVO req) {
        writer.deleteList(req);
    }

    /**
     * 调用用户 API 校验白名单主体用户列表，存在无效用户时拒绝保存。
     */
    private void validateUsers(List<Long> ids) {
        users.validateUserList(ids).checkError();
    }

    /**
     * 真实本地启用账号分页。
     */
    public PageResult<AdminUserOptionRespDTO> userOptions(@Valid AdminUserOptionPageReqDTO req) {
        return userOptionsApi.getUserOptionPage(req).getCheckedData();
    }

    /**
     * 分类白名单目录及分类候选。
     */
    public SafetyWhitelistWorkbenchOptionsRespVO options() {
        var result = new SafetyWhitelistWorkbenchOptionsRespVO();
        result.setDirectories(BeanUtils.toBean(directories.selectList(), CategoryDataRespVO.class));
        var configured = nativeMapper.selectList(
                        new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO>()
                                .eq(cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO::getSubjectType,
                                        "USER")
                                .eq(cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO::getObjectType,
                                        "CATEGORY")
                                .eq(cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO::getScene,
                                        "QUERY"))
                .stream()
                .map(cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO::getObjectId)
                .collect(java.util.stream.Collectors.toSet());
        var projected = BeanUtils.toBean(categories.selectList(), SafetyWhitelistWorkbenchOptionsRespVO.Category.class);
        for (var item : projected) {
            item.setWhitelistConfigured(configured.contains(item.getId()));
        }
        result.setCategories(projected);
        return result;
    }

    /**
     * 将脱敏白名单工作台记录投影为工作台展示信息。
     */
    private SafetyWhitelistWorkbenchRespVO project(SafetyWhitelistWorkbenchRow row) {
        var result = BeanUtils.toBean(row, SafetyWhitelistWorkbenchRespVO.class);
        result.setCategoryId(row.getObjectId());
        result.setEffectiveCategory("1");
        result.setStartTime(row.getEffectiveTime() == null ? null : row.getEffectiveTime().toLocalDate());
        result.setEndTime(row.getExpireTime() == null ? null : row.getExpireTime().toLocalDate());
        var ids = subjects.selectListByWhitelistId(row.getId()).stream().map(v -> v.getSubjectId()).toList();
        result.setUserIds(ids);
        var all = new HashSet<>(ids);
        for (var actor : Arrays.asList(row.getCreator(), row.getUpdater())) {
            try {
                if (actor != null) {
                    all.add(Long.valueOf(actor));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        var people = all.isEmpty() ? Map.<Long, AdminUserRespDTO>of() : users.getUserMap(all);
        result.setUsers(ids.stream().map(id -> {
            var target = new SafetyWhitelistWorkbenchRespVO.User();
            target.setId(id);
            var user = people.get(id);
            target.setNickname(user == null ? null : user.getNickname());
            return target;
        }).toList());
        result.setCreatorName(
                nonblank(row.getSourceCreatorName()) ? row.getSourceCreatorName() : actor(people, row.getCreator()));
        result.setUpdaterName(
                nonblank(row.getSourceUpdaterName()) ? row.getSourceUpdaterName() : actor(people, row.getUpdater()));
        if (nonblank(row.getSourceCreatorName()) && row.getSourceCreateTime() != null) {
            result.setCreateTime(row.getSourceCreateTime());
        }
        if (nonblank(row.getSourceUpdaterName()) && row.getSourceUpdateTime() != null) {
            result.setUpdateTime(row.getSourceUpdateTime());
        }
        return result;
    }

    /**
     * 校验输入包含有效的非空白内容。
     */
    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 读取脱敏白名单工作台当前操作人的身份信息。
     */
    private static String actor(Map<Long, AdminUserRespDTO> people, String id) {
        try {
            var user = id == null ? null : people.get(Long.valueOf(id));
            return user == null ? null : user.getNickname();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
