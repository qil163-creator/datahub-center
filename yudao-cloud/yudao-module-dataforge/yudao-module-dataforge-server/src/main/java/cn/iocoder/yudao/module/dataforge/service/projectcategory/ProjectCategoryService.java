package cn.iocoder.yudao.module.dataforge.service.projectcategory;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir.DevDirSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.CategorySaveReq;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryStatusReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevDirMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.projectcategory.ProjectCategoryMapper;
import cn.iocoder.yudao.module.dataforge.service.dev.DevDirService;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationTaskService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST;
import static cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DIR_HAS_CHILDREN;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DIR_NOT_EXISTS;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DIR_PARENT_ERROR;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_TASK_DEPENDENCY_INVALID;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.INTEGRATION_CONFIG_INVALID;

/** 平台共享类目的管理适配；树约束与任务引用仍由原领域服务维护。 */
@Service
@RequiredArgsConstructor
public class ProjectCategoryService {

    private final DevDirService devDirs;

    private final DevDirMapper devMapper;

    private final IntegrationTaskService integrations;

    private final IntegrationCategoryMapper integrationMapper;

    private final ProjectCategoryMapper snapshots;

    private final AdminUserApi users;

    private final SecurityFrameworkService security;

    /**
     * 查询平台共享类目列表。
     *
     * @param kind 种类
     * @return 平台共享类目列表结果集合
     */
    public List<ProjectCategoryRespVO> list(String kind) {
        authorize(kind, "query");
        var rows = loadRows(kind);
        fillCounts(kind, rows);
        fillCreatorNames(kind, rows);
        return rows;
    }

    /**
     * 读取项目类目当前详情。
     *
     * @param kind 种类
     * @param id 记录编号
     * @return 项目分类响应数据
     */
    public ProjectCategoryRespVO get(String kind, Long id) {
        authorize(kind, "query");
        requireId(id);
        var row = loadRow(kind, id);
        fillCounts(kind, List.of(row));
        fillCreatorNames(kind, List.of(row));
        return row;
    }

    /**
     * 加载行。
     *
     * @param kind 种类
     * @param id 记录编号
     * @return 项目分类响应数据
     */
    private ProjectCategoryRespVO loadRow(String kind, Long id) {
        ProjectCategoryRespVO row;
        if (integration(kind)) {
            var category = integrationMapper.selectById(id);
            if (category == null) {
                throw exception(INTEGRATION_CONFIG_INVALID, "类目不存在");
            }
            row = integrationRow(category);
        } else {
            var directory = devMapper.selectById(id);
            if (directory == null) {
                throw exception(DEV_DIR_NOT_EXISTS);
            }
            row = BeanUtils.toBean(directory, ProjectCategoryRespVO.class);
        }
        return row;
    }

    /**
     * 创建项目类目。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    public Long create(ProjectCategorySaveReqVO request) {
        authorize(request.getKind(), "create");
        if (request.getId() != null) {
            throw exception(BAD_REQUEST);
        }
        if (integration(request.getKind())) {
            return integrations.saveCategory(integrationRequest(request), false);
        }
        return devDirs.createDir(developmentRequest(request));
    }

    /**
     * 修改项目类目。
     *
     * @param request 请求参数
     */
    public void update(ProjectCategorySaveReqVO request) {
        authorize(request.getKind(), "update");
        requireId(request.getId());
        if (integration(request.getKind())) {
            integrations.saveCategory(integrationRequest(request), true);
        } else {
            devDirs.updateDir(developmentRequest(request));
        }
    }

    /**
     * 删除无子级和任务引用的项目类目。
     *
     * @param kind 种类
     * @param id 记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String kind, Long id) {
        authorize(kind, "delete");
        requireId(id);
        lockTree(kind);
        requireDisabled(kind, loadRow(kind, id));
        deleteOwned(kind, id);
    }

    /**
     * 按叶子到父级的顺序整批删除项目类目。
     *
     * @param kind 种类
     * @param ids 记录编号集合
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteList(String kind, List<Long> ids) {
        authorize(kind, "delete");
        if (ids == null || ids.isEmpty() || ids.size() > 200) {
            throw exception(BAD_REQUEST);
        }
        ids.forEach(ProjectCategoryService::requireId);
        lockTree(kind);
        var pending = new LinkedHashSet<>(ids);
        var all = loadRows(kind);
        Map<Long, Long> parents = new LinkedHashMap<>();
        Map<Long, ProjectCategoryRespVO> current = new LinkedHashMap<>();
        all.forEach(row -> parents.put(row.getId(), row.getParentId()));
        all.forEach(row -> current.put(row.getId(), row));
        for (Long id : pending) {
            if (!parents.containsKey(id)) {
                if (integration(kind)) {
                    throw exception(INTEGRATION_CONFIG_INVALID, "类目不存在");
                }
                throw exception(DEV_DIR_NOT_EXISTS);
            }
            requireDisabled(kind, current.get(id));
        }
        for (var row : all) {
            if (pending.contains(row.getParentId()) && !pending.contains(row.getId())) {
                if (integration(kind)) {
                    throw exception(INTEGRATION_CONFIG_INVALID, "类目包含未选中的子级，不能删除");
                }
                throw exception(DEV_DIR_HAS_CHILDREN);
            }
        }
        while (!pending.isEmpty()) {
            var leaves = pending.stream().filter(id -> pending.stream()
                    .noneMatch(child -> Objects.equals(parents.get(child), id))).toList();
            if (leaves.isEmpty()) {
                throw exception(DEV_DIR_PARENT_ERROR);
            }
            for (Long id : leaves) {
                deleteOwned(kind, id);
                pending.remove(id);
            }
        }
    }

    /**
     * 仅修改项目类目启停状态。
     *
     * @param request 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(ProjectCategoryStatusReqVO request) {
        authorize(request.getKind(), "update");
        requireId(request.getId());
        if (request.getStatus() == null || !Set.of(0, 1).contains(request.getStatus())) {
            throw exception(BAD_REQUEST);
        }
        if (!integration(request.getKind())) {
            devDirs.updateDirStatus(request.getId(), request.getStatus());
            return;
        }
        integrationMapper.lockTree();
        if (integrationMapper.selectById(request.getId()) == null) {
            throw exception(INTEGRATION_CONFIG_INVALID, "类目不存在");
        }
        var change = new IntegrationCategoryDO();
        change.setId(request.getId());
        change.setValidFlag(request.getStatus() == 0);
        if (integrationMapper.updateById(change) != 1) {
            throw exception(INTEGRATION_CONFIG_INVALID, "类目已变更，请刷新重试");
        }
    }

    /**
     * 校验项目分类所需的访问权限。
     *
     * @param kind 种类
     * @param action 待执行的操作
     */
    private void authorize(String kind, String action) {
        if (kind == null || !Set.of("integration", "development").contains(kind)) {
            throw exception(BAD_REQUEST);
        }
        String permission = (integration(
                kind) ? "dataforge:integration-category:" : "dataforge:dev-category:") + action;
        if (SecurityFrameworkUtils.getLoginUserId() == null || !security.hasPermission(permission)) {
            throw exception(FORBIDDEN);
        }
    }

    /**
     * 校验编号。
     *
     * @param id 记录编号
     */
    private static void requireId(Long id) {
        if (id == null || id <= 0) {
            throw exception(BAD_REQUEST);
        }
    }

    /**
     * 判断集成条件是否成立。
     *
     * @param kind 种类
     * @return 处理是否成功
     */
    private static boolean integration(String kind) {
        return "integration".equals(kind);
    }

    /**
     * 锁定树。
     *
     * @param kind 种类
     */
    private void lockTree(String kind) {
        if (integration(kind)) {
            integrationMapper.lockTree();
        } else {
            devMapper.lockTree();
        }
    }

    /**
     * 删除持有执行权。
     *
     * @param kind 种类
     * @param id 记录编号
     */
    private void deleteOwned(String kind, Long id) {
        if (integration(kind)) {
            integrations.deleteCategory(id);
        } else {
            devDirs.deleteDir(id);
        }
    }

    /**
     * 校验禁用状态。
     *
     * @param kind 种类
     * @param row 当前行数据
     */
    private static void requireDisabled(String kind, ProjectCategoryRespVO row) {
        if (!Integer.valueOf(1).equals(row.getStatus())) {
            throw exception(integration(kind) ? INTEGRATION_CONFIG_INVALID : DEV_TASK_DEPENDENCY_INVALID,
                    "启用中的类目不能删除，请先停用");
        }
    }

    /**
     * 加载行。
     *
     * @param kind 种类
     * @return 加载行结果集合
     */
    private List<ProjectCategoryRespVO> loadRows(String kind) {
        if (integration(kind)) {
            return integrationMapper.selectList(new LambdaQueryWrapperX<IntegrationCategoryDO>()
                            .orderByAsc(IntegrationCategoryDO::getSort, IntegrationCategoryDO::getId))
                    .stream().map(ProjectCategoryService::integrationRow).toList();
        }
        return devMapper.selectListAll().stream().map(row -> BeanUtils.toBean(row, ProjectCategoryRespVO.class))
                .toList();
    }

    /**
     * 获取项目分类的集成行。
     *
     * @param category 分类
     * @return 项目分类响应数据
     */
    private static ProjectCategoryRespVO integrationRow(IntegrationCategoryDO category) {
        var row = BeanUtils.toBean(category, ProjectCategoryRespVO.class);
        row.setStatus(Boolean.TRUE.equals(category.getValidFlag()) ? 0 : 1);
        return row;
    }

    /**
     * 获取项目分类的集成请求。
     *
     * @param request 请求参数
     * @return 分类保存
     */
    private static CategorySaveReq integrationRequest(ProjectCategorySaveReqVO request) {
        var target = BeanUtils.toBean(request, CategorySaveReq.class);
        target.setName(request.getName().strip());
        target.setValidFlag(request.getStatus() == 0);
        return target;
    }

    /**
     * 获取项目分类的开发请求。
     *
     * @param request 请求参数
     * @return 开发目录保存参数
     */
    private static DevDirSaveReqVO developmentRequest(ProjectCategorySaveReqVO request) {
        var target = BeanUtils.toBean(request, DevDirSaveReqVO.class);
        target.setName(request.getName().strip());
        return target;
    }

    /**
     * 补充创建人名称。
     *
     * @param kind 种类
     * @param rows 待处理的行数据
     */
    private void fillCreatorNames(String kind, List<ProjectCategoryRespVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var ids = rows.stream().map(ProjectCategoryRespVO::getId).toList();
        var sourceNames = integration(kind) ? snapshots.selectIntegrationCreatorNames(
                ids) : snapshots.selectDevelopmentCreatorNames(ids);
        Map<Long, String> names = new LinkedHashMap<>();
        sourceNames.stream().filter(source -> source.creatorName() != null && !source.creatorName().isBlank())
                .forEach(source -> names.put(source.id(), source.creatorName()));
        Set<Long> userIds = new LinkedHashSet<>();
        for (var row : rows) {
            if (!names.containsKey(row.getId())) {
                Long userId = creatorId(row.getCreator());
                if (userId != null) {
                    userIds.add(userId);
                }
            }
        }
        Map<Long, String> usersById = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            users.getUserList(new ArrayList<>(userIds)).getCheckedData()
                    .forEach(user -> usersById.put(user.getId(), user.getNickname()));
        }
        for (var row : rows) {
            String fallback = row.getCreator() == null || row.getCreator().isBlank() ? "-" : row.getCreator();
            row.setCreatorName(
                    names.getOrDefault(row.getId(), usersById.getOrDefault(creatorId(row.getCreator()), fallback)));
        }
    }

    /**
     * 补充数量。
     *
     * @param kind 种类
     * @param rows 待处理的行数据
     */
    private void fillCounts(String kind, List<ProjectCategoryRespVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var ids = rows.stream().map(ProjectCategoryRespVO::getId).toList();
        var counts = integration(kind) ? snapshots.selectIntegrationTaskCounts(
                ids) : snapshots.selectDevelopmentTaskCounts(ids);
        Map<Long, Long> byId = new LinkedHashMap<>();
        counts.forEach(count -> byId.put(count.id(), count.taskCount()));
        rows.forEach(row -> row.setTaskCount(byId.getOrDefault(row.getId(), 0L)));
        var children = integration(kind) ? snapshots.selectIntegrationChildCounts(
                ids) : snapshots.selectDevelopmentChildCounts(ids);
        Map<Long, Long> childrenById = new LinkedHashMap<>();
        children.forEach(count -> childrenById.put(count.id(), count.childCount()));
        rows.forEach(row -> row.setChildCount(childrenById.getOrDefault(row.getId(), 0L)));
    }

    /**
     * 获取项目分类的创建人编号。
     *
     * @param creator 创建人
     * @return 创建人编号数值
     */
    private static Long creatorId(String creator) {
        if (creator == null || !creator.matches("[1-9][0-9]{0,18}")) {
            return null;
        }
        try {
            return Long.valueOf(creator);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
