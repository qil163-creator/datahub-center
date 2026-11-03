package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.convert.planning.PlanningDomainConvert;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.SubjectDomainRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.PlanningDomainStatisticsMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** 规划目录按平台全可见展示，业务实体计数另行校验其查询权限。 */
@Service
public class PlanningDomainPresentationService {

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private PlanningDomainStatisticsMapper statisticsMapper;

    @Resource
    private SecurityFrameworkService securityFrameworkService;

    /**
     * 整理板块。
     *
     * @param rows 待处理的行数据
     * @return 整理板块结果集合
     */
    public List<DataDomainRespVO> presentBoards(List<DataDomainDO> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var result = rows.stream().map(PlanningDomainConvert::toResponse).toList();
        var userIds = new HashSet<Long>();
        rows.forEach(row -> collectUsers(userIds, row.getLeaderUserId(), row.getCreator()));
        var users = userIds.isEmpty() ? Map.<Long, AdminUserRespDTO>of() : adminUserApi.getUserMap(userIds);
        boolean canReadSubjects = securityFrameworkService.hasPermission("modeling:subject-domain:query");
        var subjects = canReadSubjects ? counts(statisticsMapper.selectSubjectCounts()) : Map.<Long, Long>of();
        boolean canReadEntities = securityFrameworkService.hasPermission("modeling:business-entity:query");
        var entityCounts = canReadEntities ? statisticsMapper.selectBoardEntityCounts() : List.<PlanningDomainStatisticsMapper.CountRow>of();
        var entities = counts(entityCounts);
        var activities = entityCounts.stream().collect(Collectors.toMap(PlanningDomainStatisticsMapper.CountRow::getId,
                PlanningDomainStatisticsMapper.CountRow::getActivityCount));
        result.forEach(row -> {
            var leader = user(users, row.getLeaderUserId());
            var creator = user(users, userId(row.getCreator()));
            row.setLeaderName(leader == null ? null : leader.getNickname());
            row.setCreatorName(creator == null ? null : creator.getNickname());
            row.setCreatorPhone(creator == null ? null : creator.getMobile());
            row.setSubjectCount(canReadSubjects ? subjects.getOrDefault(row.getId(), 0L) : null);
            row.setBusinessActivityCount(canReadEntities ? activities.getOrDefault(row.getId(), 0L) : null);
            row.setBusinessObjectCount(canReadEntities ? entities.getOrDefault(row.getId(), 0L) : null);
        });
        return result;
    }

    /**
     * 整理主体。
     *
     * @param rows 待处理的行数据
     * @return 整理主体结果集合
     */
    public List<SubjectDomainRespVO> presentSubjects(List<SubjectDomainDO> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var result = BeanUtils.toBean(rows, SubjectDomainRespVO.class);
        var userIds = new HashSet<Long>();
        rows.forEach(row -> collectUsers(userIds, row.getLeaderUserId(), row.getCreator()));
        var users = userIds.isEmpty() ? Map.<Long, AdminUserRespDTO>of() : adminUserApi.getUserMap(userIds);
        boolean canReadEntities = securityFrameworkService.hasPermission("modeling:business-entity:query");
        var entities = canReadEntities ? counts(statisticsMapper.selectSubjectEntityCounts()) : Map.<Long, Long>of();
        result.forEach(row -> {
            var leader = user(users, row.getLeaderUserId());
            var creator = user(users, userId(row.getCreator()));
            row.setLeaderName(leader == null ? null : leader.getNickname());
            row.setCreatorName(creator == null ? null : creator.getNickname());
            row.setCreatorPhone(creator == null ? null : creator.getMobile());
            row.setBusinessObjectCount(canReadEntities ? entities.getOrDefault(row.getId(), 0L) : null);
        });
        return result;
    }

    /**
     * 收集用户。
     *
     * @param ids 记录编号集合
     * @param leader 主节点
     * @param creator 创建人
     */
    private static void collectUsers(Set<Long> ids, Long leader, String creator) {
        if (leader != null) {
            ids.add(leader);
        }
        Long creatorId = userId(creator);
        if (creatorId != null) {
            ids.add(creatorId);
        }
    }

    /**
     * 获取规划域展示的用户。
     *
     * @param users 用户
     * @param id 记录编号
     * @return 管理端用户响应数据
     */
    private static AdminUserRespDTO user(Map<Long, AdminUserRespDTO> users, Long id) {
        return id == null ? null : users.get(id);
    }

    /**
     * 获取规划域展示的用户编号。
     *
     * @param value 待处理的值
     * @return 用户编号数值
     */
    private static Long userId(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 汇总规划域展示的数量指标。
     *
     * @param rows 待处理的行数据
     * @return 处理后的键值映射
     */
    private static Map<Long, Long> counts(List<PlanningDomainStatisticsMapper.CountRow> rows) {
        return rows.stream().filter(row -> row.getId() != null)
                .collect(Collectors.toMap(PlanningDomainStatisticsMapper.CountRow::getId,
                        PlanningDomainStatisticsMapper.CountRow::getCount));
    }
}
