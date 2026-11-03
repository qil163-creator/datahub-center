package cn.iocoder.yudao.module.governance.service.category;

import cn.iocoder.yudao.module.governance.api.security.dto.SafetyClassificationRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydata.CategoryDataDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 平台共享定义的只读投影；不读取资产数据，也不授予资产或脱敏执行权限。 */
@Service
public class SafetyClassificationReferenceService {

    @Resource
    private CategoryDataMapper directoryMapper;

    @Resource
    private SafetyCategoryMapper categoryMapper;

    @Resource
    private SafetyLevelMapper levelMapper;

    @Resource
    private SafetyRuleMapper ruleMapper;

    /**
     * 构造安全分类和必要祖先目录的只读树，汇总分类等级、规则与可用状态。
     */
    public List<SafetyClassificationRespDTO> list() {
        List<CategoryDataDO> directories = new ArrayList<>(directoryMapper.selectList());
        List<SafetyCategoryDO> categories = new ArrayList<>(categoryMapper.selectList());
        directories.sort(Comparator.comparing(CategoryDataDO::getSort, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(CategoryDataDO::getId));
        Map<Long, CategoryDataDO> directoryMap = index(directories, CategoryDataDO::getId);
        Set<Long> usedDirectories = new HashSet<>();
        // 只投影分类实际使用的目录及其祖先；集合去重同时防止异常父链循环遍历。
        for (SafetyCategoryDO category : categories) {
            Long directoryId = category.getCategoryId();
            while (directoryId != null && directoryId != 0L && usedDirectories.add(directoryId)) {
                CategoryDataDO directory = directoryMap.get(directoryId);
                directoryId = directory == null ? null : directory.getParentId();
            }
        }
        Map<Long, SafetyLevelDO> levels = index(levelMapper.selectList(), SafetyLevelDO::getId);
        Map<Long, SafetyRuleDO> rules = index(ruleMapper.selectList(), SafetyRuleDO::getId);
        List<SafetyClassificationRespDTO> result = new ArrayList<>();
        for (CategoryDataDO directory : directories) {
            if (!usedDirectories.contains(directory.getId())) {
                continue;
            }
            SafetyClassificationRespDTO node = node("dir:" + directory.getId(),
                    parentKey(directory.getParentId()), directory.getCategoryName(), false);
            node.setStatus(status(directoryEnabled(directory.getId(), directoryMap)));
            result.add(node);
        }
        categories.sort(Comparator.comparing(SafetyCategoryDO::getSort, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(SafetyCategoryDO::getId));
        for (SafetyCategoryDO category : categories) {
            SafetyClassificationRespDTO node = node("category:" + category.getId(),
                    parentKey(category.getCategoryId()), category.getName(), true);
            node.setCategoryId(category.getId());
            SafetyLevelDO level = category.getDataLevelId() == null ? null
                    : levels.get(category.getDataLevelId().longValue());
            // 分类、等级和整条目录父链都启用时才可用，避免仅检查叶子节点而绕过上级停用。
            node.setStatus(status(isEnabled(category.getStatus()) && level != null && isEnabled(level.getStatus())
                    && directoryEnabled(category.getCategoryId(), directoryMap)));
            if (level != null) {
                node.setLevelName(level.getSafetyLevel());
                node.setLevelColor(level.getColor());
            }
            SafetyRuleDO rule = rules.get(category.getRuleId());
            node.setRuleName(rule == null ? null : rule.getRuleName());
            node.setRuleId(rule == null ? null : rule.getId());
            result.add(node);
        }
        return result;
    }

    /**
     * 按分类编号读取共享分类投影，不返回目录节点。
     */
    public SafetyClassificationRespDTO get(Long id) {
        if (id == null) {
            return null;
        }
        return list().stream().filter(node -> Objects.equals(id, node.getCategoryId())).findFirst().orElse(null);
    }

    /**
     * 沿目录父链检查启用状态，遇到缺失目录或环时判定不可用。
     */
    private static boolean directoryEnabled(Long id, Map<Long, CategoryDataDO> directories) {
        HashSet<Long> visited = new HashSet<>();
        while (id != null && id != 0L) {
            CategoryDataDO directory = directories.get(id);
            // 缺失父目录或存在环都无法证明目录有效，按不可用处理而不是提前放行。
            if (!visited.add(id) || directory == null || !isEnabled(directory.getStatus())) {
                return false;
            }
            id = directory.getParentId();
        }
        return true;
    }

    /**
     * 判断安全分类引用是否启用。
     */
    private static boolean isEnabled(Short value) {
        return value != null && value == 0;
    }

    /**
     * 将可用性判断转换为分类接口约定的启用或停用状态。
     */
    private static short status(boolean enabled) {
        return (short) (enabled ? 0 : 1);
    }

    /**
     * 将父目录编号转换为树节点标识，根目录不设置父节点。
     */
    private static String parentKey(Long id) {
        return id == null || id == 0L ? null : "dir:" + id;
    }

    /**
     * 创建分类投影节点，区分可选择分类和目录容器。
     */
    private static SafetyClassificationRespDTO node(String id, String parentId, String name, boolean selectable) {
        SafetyClassificationRespDTO result = new SafetyClassificationRespDTO();
        result.setId(id);
        result.setParentId(parentId);
        result.setName(name);
        result.setSelectable(selectable);
        return result;
    }

    /**
     * 按编号建立保持输入顺序的索引，重复编号保留首条记录。
     */
    private static <T> Map<Long, T> index(List<T> values, Function<T, Long> id) {
        return values.stream().collect(Collectors.toMap(id, Function.identity(), (first, ignored) -> first,
                LinkedHashMap::new));
    }
}
