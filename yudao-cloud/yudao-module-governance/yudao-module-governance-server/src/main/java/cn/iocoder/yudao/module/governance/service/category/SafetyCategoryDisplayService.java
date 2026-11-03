package cn.iocoder.yudao.module.governance.service.category;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.categorydata.vo.CategoryDataRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.level.vo.SafetyLevelRespVO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 面向分类维护页的显示富化；来源名称和时间不会覆写本地审计列。 */
@Service
public class SafetyCategoryDisplayService {

    @Resource
    private CategoryDataMapper directories;

    @Resource
    private SafetyLevelMapper levels;

    @Resource
    private SafetyRuleMapper rules;

    @Resource
    private AdminUserApi users;

    /**
     * 将安全分类关联信息补充到返回结果。
     */
    public void fill(List<SafetyCategoryRespVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var directoryMap = directories.selectList().stream()
                .collect(Collectors.toMap(row -> row.getId(), Function.identity()));
        var levelMap = levels.selectList().stream().collect(Collectors.toMap(row -> row.getId(), Function.identity()));
        var ruleMap = rules.selectList().stream().collect(Collectors.toMap(row -> row.getId(), Function.identity()));
        Set<Long> actors = new HashSet<>();
        for (var row : rows) {
            addActor(actors, row.getCreator());
            addActor(actors, row.getUpdater());
        }
        Map<Long, AdminUserRespDTO> userMap = actors.isEmpty() ? Map.of() : users.getUserMap(actors);
        for (var row : rows) {
            var directory = directoryMap.get(row.getCategoryId());
            row.setCategoryName(directory == null ? null : directory.getCategoryName());
            var level = row.getDataLevelId() == null ? null : levelMap.get(row.getDataLevelId().longValue());
            if (level != null) {
                row.setLevelName(level.getSafetyLevel());
                row.setLevelDisplayName(level.getSafetyName());
                row.setLevelColor(level.getColor());
            }
            var rule = ruleMap.get(row.getRuleId());
            row.setRuleName(rule == null ? null : rule.getRuleName());
            row.setConfigured(rule != null);
            row.setCreatorName(actorName(userMap, row.getCreator()));
            row.setUpdaterName(actorName(userMap, row.getUpdater()));
        }
    }

    /**
     * 查询安全分类可选项，供配置或关联选择使用。
     */
    public SafetyCategoryOptionsRespVO options() {
        var result = new SafetyCategoryOptionsRespVO();
        var dirs = new ArrayList<>(directories.selectList());
        dirs.sort(Comparator.comparing(row -> row.getSort(), Comparator.nullsLast(Integer::compareTo)));
        result.setDirectories(BeanUtils.toBean(dirs, CategoryDataRespVO.class));
        var gradeRows = new ArrayList<>(levels.selectList());
        gradeRows.sort(Comparator.comparing(row -> row.getSort(), Comparator.nullsLast(Integer::compareTo)));
        result.setLevels(BeanUtils.toBean(gradeRows, SafetyLevelRespVO.class));
        List<SafetyCategoryOptionsRespVO.Priority> priorities = new ArrayList<>();
        String[][] values = {{"LOWEST", "最低"}, {"LOW", "低"}, {"MEDIUM", "中等"}, {"HIGH", "高"}, {"HIGHEST", "最高"}};
        for (var value : values) {
            var item = new SafetyCategoryOptionsRespVO.Priority();
            item.setValue(value[0]);
            item.setLabel(value[1]);
            priorities.add(item);
        }
        result.setPriorities(priorities);
        return result;
    }

    /**
     * 读取安全分类来源系统的展示信息。
     */
    public void sourceDisplay(SafetyCategoryRespVO target,
            cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO source) {
        if (StrUtil.isNotBlank(source.getSourceCreatorName())) {
            target.setCreatorName(source.getSourceCreatorName());
            if (source.getSourceCreateTime() != null) {
                target.setCreateTime(source.getSourceCreateTime());
            }
        }
        if (StrUtil.isNotBlank(source.getSourceUpdaterName())) {
            target.setUpdaterName(source.getSourceUpdaterName());
            if (source.getSourceUpdateTime() != null) {
                target.setUpdateTime(source.getSourceUpdateTime());
            }
        }
    }

    /**
     * 添加操作人。
     */
    private static void addActor(Set<Long> ids, String actor) {
        try {
            if (actor != null) {
                ids.add(Long.valueOf(actor));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /**
     * 将操作人编号解析为展示名称。
     */
    private static String actorName(Map<Long, AdminUserRespDTO> names, String actor) {
        try {
            var user = actor == null ? null : names.get(Long.valueOf(actor));
            return user == null ? actor : user.getNickname();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
