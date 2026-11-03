package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import jakarta.annotation.Resource;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.PushCategoryDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.PushCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.stream.Collectors;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CATEGORY_INVALID;

/** 推送类目为平台共享目录，写入与任务关联使用相同事务锁。 */
@Service
@RequiredArgsConstructor
public class PushCategoryService {
    private final PushCategoryMapper categories;
    private final DataPushTaskMapper tasks;
    @Resource
    private SecurityFrameworkService security;
    @Resource
    private AdminUserApi users;

    public List<PushCategoryRespVO> list() {
        var rows = categories.selectOrdered();
        Map<Long, Long> children = rows.stream().collect(Collectors.groupingBy(PushCategoryDO::getParentId, Collectors.counting()));
        boolean canReadTasks = security.hasPermission("dataforge:data-push:query");
        Map<Long, Long> counts = canReadTasks ? tasks.selectCategoryTaskCounts().stream()
                .collect(Collectors.toMap(DataPushTaskMapper.CategoryTaskCount::id, DataPushTaskMapper.CategoryTaskCount::taskCount)) : Map.of();
        List<PushCategoryRespVO> result = BeanUtils.toBean(rows, PushCategoryRespVO.class);
        Set<Long> creatorIds = new LinkedHashSet<>();
        for (var row : result) {
            Long id = creatorId(row.getCreator());
            if (id != null) creatorIds.add(id);
        }
        Map<Long, String> creatorNames = new HashMap<>();
        if (!creatorIds.isEmpty()) {
            users.getUserList(new ArrayList<>(creatorIds)).getCheckedData()
                    .forEach(user -> creatorNames.put(user.getId(), user.getNickname()));
        }
        for (var row : result) {
            row.setChildCount(children.getOrDefault(row.getId(), 0L));
            row.setTaskCount(canReadTasks ? counts.getOrDefault(row.getId(), 0L) : null);
            row.setCreatorName(creatorNames.getOrDefault(creatorId(row.getCreator()),
                    row.getCreator() == null || row.getCreator().isBlank() ? "-" : row.getCreator()));
        }
        return result;
    }

    public List<PushCategoryRespVO> tree() {
        return categories.selectOrdered().stream().map(row -> {
            PushCategoryRespVO result = new PushCategoryRespVO();
            result.setId(row.getId());
            result.setParentId(row.getParentId());
            result.setName(row.getName());
            result.setSort(row.getSort());
            result.setStatus(row.getStatus());
            return result;
        }).toList();
    }

    public PushCategoryRespVO get(Long id) {
        return list().stream().filter(row -> Objects.equals(id, row.getId())).findFirst()
                .orElseThrow(() -> invalid("类目不存在"));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(PushCategorySaveReqVO request) {
        if (request.getId() != null) throw invalid("新建时不能指定编号");
        categories.lockTree();
        validateParent(null, request.getParentId());
        validateName(null, request.getParentId(), request.getName().strip());
        PushCategoryDO row = BeanUtils.toBean(request, PushCategoryDO.class);
        row.setName(request.getName().strip());
        categories.insert(row);
        return row.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(PushCategorySaveReqVO request) {
        categories.lockTree();
        require(request.getId());
        validateParent(request.getId(), request.getParentId());
        validateName(request.getId(), request.getParentId(), request.getName().strip());
        PushCategoryDO row = BeanUtils.toBean(request, PushCategoryDO.class);
        row.setName(request.getName().strip());
        categories.updateById(row);
        if (request.getDescription() == null) {
            categories.update(null, Wrappers.lambdaUpdate(PushCategoryDO.class)
                    .eq(PushCategoryDO::getId, request.getId()).set(PushCategoryDO::getDescription, null));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(PushCategoryStatusReqVO request) {
        categories.lockTree();
        require(request.getId());
        PushCategoryDO row = new PushCategoryDO();
        row.setId(request.getId());
        row.setStatus(request.getStatus());
        categories.updateById(row);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw invalid("类目编号无效");
        categories.lockTree();
        var all = categories.selectOrdered();
        Map<Long, PushCategoryDO> byId = all.stream().collect(Collectors.toMap(PushCategoryDO::getId, row -> row));
        Set<Long> pending = new LinkedHashSet<>(ids);
        for (Long id : pending) {
            PushCategoryDO row = byId.get(id);
            if (row == null) throw invalid("类目不存在");
            if (!Objects.equals(row.getStatus(), 1)) throw invalid("启用中的类目不能删除，请先停用");
            if (tasks.selectCount(DataPushTaskDO::getCategoryId, id) > 0) throw invalid("类目已被任务引用");
        }
        for (var row : all) {
            if (pending.contains(row.getParentId()) && !pending.contains(row.getId()))
                throw invalid("类目存在未选中的子类目");
        }
        while (!pending.isEmpty()) {
            var leaves = pending.stream().filter(id -> pending.stream().noneMatch(child -> Objects.equals(byId.get(child).getParentId(), id))).toList();
            if (leaves.isEmpty()) throw invalid("类目层级存在循环");
            for (Long id : leaves) { categories.deleteById(id); pending.remove(id); }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void validateReference(Long id, Long previousId) {
        if (id == null) return;
        categories.lockTree();
        PushCategoryDO row = require(id);
        if (!Objects.equals(id, previousId) && !Objects.equals(row.getStatus(), 0)) throw invalid("类目已停用，不能绑定新任务");
    }

    public List<Long> descendants(Long id) {
        var rows = categories.selectOrdered();
        if (rows.stream().noneMatch(row -> Objects.equals(row.getId(), id))) throw invalid("类目不存在");
        Map<Long, List<PushCategoryDO>> children = rows.stream().collect(Collectors.groupingBy(PushCategoryDO::getParentId));
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> pending = new ArrayDeque<>();
        pending.add(id);
        while (!pending.isEmpty()) {
            Long current = pending.removeFirst();
            if (result.add(current)) children.getOrDefault(current, List.of()).forEach(row -> pending.add(row.getId()));
        }
        return new ArrayList<>(result);
    }

    private PushCategoryDO require(Long id) {
        PushCategoryDO row = id == null ? null : categories.selectById(id);
        if (row == null) throw invalid("类目不存在");
        return row;
    }
    private void validateParent(Long id, Long parentId) {
        Set<Long> seen = new HashSet<>();
        Long current = parentId;
        while (!Objects.equals(current, 0L)) {
            if (current == null || Objects.equals(current, id) || !seen.add(current)) throw invalid("父类目无效或形成循环");
            current = require(current).getParentId();
        }
    }
    private void validateName(Long id, Long parentId, String name) {
        PushCategoryDO row = categories.selectFirstOne(PushCategoryDO::getParentId, parentId, PushCategoryDO::getName, name);
        if (row != null && !Objects.equals(row.getId(), id)) throw invalid("同级目录下已存在同名类目");
    }
    private static Long creatorId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return null;
        try { return Long.valueOf(value); } catch (NumberFormatException ignored) { return null; }
    }
    private static cn.iocoder.yudao.framework.common.exception.ServiceException invalid(String reason) {
        return exception(DATA_PUSH_CATEGORY_INVALID, reason);
    }
}
