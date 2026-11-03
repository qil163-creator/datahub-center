package cn.iocoder.yudao.module.modeling.service.logictable;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.logictable.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelColumnDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.SourceSystemDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelColumnMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.SourceSystemMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import cn.iocoder.yudao.module.modeling.enums.ModelTableTypeEnum;
import cn.iocoder.yudao.module.modeling.service.model.LogicModelService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.LOGIC_MODEL_NOT_EXISTS;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.LOGIC_TABLE_ENABLED;

@Service
@Validated
public class LogicTableServiceImpl implements LogicTableService {

    /** 字段数排序只使用固定子查询，不拼接调用方输入。 */
    private static final String COLUMN_COUNT_SQL = "(SELECT count(*) FROM dh_modeling_logic_model_column c "
            + "WHERE c.model_id = dh_modeling_logic_model.id AND c.deleted = 0)";

    @Resource
    private LogicModelMapper modelMapper;

    @Resource
    private LogicModelColumnMapper columnMapper;

    @Resource
    private SourceSystemMapper sourceSystemMapper;

    @Resource
    private DataDomainMapper dataDomainMapper;

    @Resource
    private SubjectDomainMapper subjectDomainMapper;

    @Resource
    private BusinessEntityMapper businessEntityMapper;

    @Resource
    private LogicModelService logicModelService;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 获得逻辑表分页。
     *
     * @param reqVO 请求参数
     * @return 逻辑表分页结果
     */
    @Override
    public PageResult<LogicTableRespVO> getLogicTablePage(LogicTablePageReqVO reqVO) {
        LambdaQueryWrapperX<LogicModelDO> wrapper = new LambdaQueryWrapperX<LogicModelDO>()
                .eq(LogicModelDO::getTableType, reqVO.getTableType())
                .eqIfPresent(LogicModelDO::getSourceSystemId, reqVO.getSourceSystemId())
                .eqIfPresent(LogicModelDO::getEnabled, reqVO.getEnabled())
                .eqIfPresent(LogicModelDO::getContactUserId, reqVO.getContactUserId())
                .eqIfPresent(LogicModelDO::getDataTimeliness, reqVO.getDataTimeliness())
                .eqIfPresent(LogicModelDO::getBusinessType, reqVO.getBusinessType())
                .eqIfPresent(LogicModelDO::getDataDomainId, reqVO.getDataDomainId())
                .eqIfPresent(LogicModelDO::getSubjectDomainId, reqVO.getSubjectDomainId());
        String keyword = StrUtil.trim(reqVO.getName());
        wrapper.and(StrUtil.isNotEmpty(keyword), inner -> inner.like(LogicModelDO::getName, keyword)
                .or().like(LogicModelDO::getCode, keyword)
                .or().like(LogicModelDO::getTableNameNotation, keyword));
        applySort(wrapper, reqVO);
        PageResult<LogicModelDO> page = modelMapper.selectPage(reqVO, wrapper);
        return new PageResult<>(present(page.getList()), page.getTotal());
    }

    /**
     * 应用排序。
     *
     * @param wrapper 条件包装器
     * @param reqVO 请求参数
     */
    private static void applySort(LambdaQueryWrapperX<LogicModelDO> wrapper, LogicTablePageReqVO reqVO) {
        boolean asc = Boolean.TRUE.equals(reqVO.getAsc());
        String field = StrUtil.blankToDefault(reqVO.getSortField(), "createTime");
        switch (field) {
            case "id" -> wrapper.orderBy(true, asc, LogicModelDO::getId);
            case "updateTime" -> wrapper.orderBy(true, asc, LogicModelDO::getUpdateTime);
            case "columnCount" -> wrapper.last("ORDER BY " + COLUMN_COUNT_SQL + (asc ? " ASC" : " DESC")
                    + ", id DESC");
            default -> wrapper.orderBy(true, asc, LogicModelDO::getCreateTime);
        }
        if (!"columnCount".equals(field) && !"id".equals(field)) {
            wrapper.orderByDesc(LogicModelDO::getId);
        }
    }

    /**
     * 组装逻辑表展示数据。
     *
     * @param models 模型
     * @return 组装逻辑表展示数据结果集合
     */
    private List<LogicTableRespVO> present(List<LogicModelDO> models) {
        if (models.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> columnCounts = countColumns(convertSet(models, LogicModelDO::getId));
        Map<Long, SourceSystemDO> sources = mapByIds(convertSet(models, LogicModelDO::getSourceSystemId),
                sourceSystemMapper::selectByIds, SourceSystemDO::getId);
        Map<Long, DataDomainDO> domains = mapByIds(convertSet(models, LogicModelDO::getDataDomainId),
                dataDomainMapper::selectByIds, DataDomainDO::getId);
        Map<Long, SubjectDomainDO> subjects = mapByIds(convertSet(models, LogicModelDO::getSubjectDomainId),
                subjectDomainMapper::selectByIds, SubjectDomainDO::getId);
        Map<Long, BusinessEntityDO> entities = mapByIds(convertSet(models, LogicModelDO::getEntityId),
                businessEntityMapper::selectByIds, BusinessEntityDO::getId);
        Set<Long> creatorIds = models.stream().map(LogicModelDO::getCreator)
                .filter(StrUtil::isNumeric).map(Long::valueOf).collect(Collectors.toSet());
        creatorIds.addAll(models.stream().map(LogicModelDO::getContactUserId).filter(Objects::nonNull).toList());
        Map<Long, AdminUserRespDTO> users = creatorIds.isEmpty() ? new HashMap<>() : new HashMap<>(
                adminUserApi.getUserMap(creatorIds));
        List<LogicTableRespVO> result = new ArrayList<>(models.size());
        for (LogicModelDO model : models) {
            LogicTableRespVO vo = new LogicTableRespVO();
            vo.setId(model.getId());
            vo.setRevision(model.getRevision());
            vo.setName(model.getName());
            vo.setCode(model.getCode());
            vo.setTableNameNotation(model.getTableNameNotation());
            vo.setTableType(model.getTableType());
            vo.setEnabled(model.getEnabled());
            vo.setDescription(model.getDescription());
            vo.setColumnCount(columnCounts.getOrDefault(model.getId(), 0L));
            vo.setSourceSystemId(model.getSourceSystemId());
            vo.setSourceSystemName(name(sources.get(model.getSourceSystemId()), SourceSystemDO::getName));
            vo.setDbType(model.getDbType());
            vo.setDbName(model.getDbName());
            vo.setDataDomainId(model.getDataDomainId());
            DataDomainDO domain = domains.get(model.getDataDomainId());
            vo.setDataDomainName(name(domain, DataDomainDO::getName));
            vo.setDataDomainIcon(name(domain, DataDomainDO::getIcon));
            vo.setSubjectDomainId(model.getSubjectDomainId());
            vo.setSubjectDomainName(name(subjects.get(model.getSubjectDomainId()), SubjectDomainDO::getName));
            vo.setEntityId(model.getEntityId());
            vo.setEntityName(name(entities.get(model.getEntityId()), BusinessEntityDO::getName));
            vo.setObjType(model.getObjType());
            vo.setBusinessType(model.getBusinessType());
            vo.setDataTimeliness(model.getDataTimeliness());
            vo.setStatisticsStr(model.getStatisticsStr());
            vo.setContactUserId(model.getContactUserId());
            vo.setContactNumber(model.getContactNumber());
            vo.setOwnerName(model.getOwnerName());
            vo.setOwnerPhone(model.getOwnerPhone());
            AdminUserRespDTO owner = users.get(model.getContactUserId());
            if (owner != null) {
                vo.setOwnerName(owner.getNickname());
                vo.setOwnerPhone(owner.getMobile());
            }
            vo.setCreator(model.getCreator());
            AdminUserRespDTO creator = StrUtil.isNumeric(model.getCreator())
                    ? users.get(Long.valueOf(model.getCreator())) : null;
            vo.setCreatorName(creator == null ? null : creator.getNickname());
            vo.setCreateTime(model.getCreateTime());
            vo.setUpdateTime(model.getUpdateTime());
            result.add(vo);
        }
        return result;
    }

    /**
     * 统计逻辑表的列集合。
     *
     * @param modelIds 模型编号集合
     * @return 处理后的键值映射
     */
    private Map<Long, Long> countColumns(Set<Long> modelIds) {
        List<Map<String, Object>> rows = columnMapper.selectMaps(new QueryWrapper<LogicModelColumnDO>()
                .select("model_id", "count(*) AS column_count")
                .in("model_id", modelIds)
                .groupBy("model_id"));
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(((Number) row.get("model_id")).longValue(), ((Number) row.get("column_count")).longValue());
        }
        return counts;
    }

    /**
     * 映射按编号集合。
     *
     * @param <T> 处理数据的类型
     * @param ids 记录编号集合
     * @param loader 加载器
     * @param key 标识键
     * @return 处理后的键值映射
     */
    private static <T> Map<Long, T> mapByIds(Set<Long> ids,
            java.util.function.Function<Collection<Long>, List<T>> loader,
            java.util.function.Function<T, Long> key) {
        ids.remove(null);
        return ids.isEmpty() ? new HashMap<>() : new HashMap<>(convertMap(loader.apply(ids), key));
    }

    /**
     * 读取逻辑表名称。
     *
     * @param <T> 处理数据的类型
     * @param value 待处理的值
     * @param getter 读取方法
     * @return 逻辑表名称文本
     */
    private static <T> String name(T value, java.util.function.Function<T, String> getter) {
        return value == null ? null : getter.apply(value);
    }

    /**
     * 左侧类目树：贴源逻辑表按来源系统，其余按数据板块/主题域。
     *
     * @param tableType 表类型
     * @return 左侧类目树：贴源逻辑表按来源系统，其余按数据板块/主题域结果集合
     */
    @Override
    public List<LogicTableCatNodeRespVO> getCatTree(String tableType) {
        if (ModelTableTypeEnum.SOURCE.getType().equals(tableType)) {
            return sourceSystemMapper.selectEnabledList().stream()
                    .map(source -> new LogicTableCatNodeRespVO("source-" + source.getId(), source.getName(),
                            "SOURCE_SYSTEM", source.getId(), null, null, List.of(), null))
                    .toList();
        }
        // 主题域只展示已沉淀业务实体或逻辑表的节点，与数据板块组成两级类目。
        Set<Long> usedSubjectIds = new HashSet<>(convertSet(businessEntityMapper.selectList(),
                BusinessEntityDO::getSubjectDomainId));
        usedSubjectIds.addAll(convertSet(modelMapper.selectList(new LambdaQueryWrapperX<LogicModelDO>()
                .isNotNull(LogicModelDO::getSubjectDomainId)), LogicModelDO::getSubjectDomainId));
        Map<Long, List<SubjectDomainDO>> subjectsByDomain = subjectDomainMapper.selectList().stream()
                .filter(subject -> usedSubjectIds.contains(subject.getId()))
                .sorted(Comparator.comparing(SubjectDomainDO::getId))
                .collect(Collectors.groupingBy(SubjectDomainDO::getDataDomainId, LinkedHashMap::new,
                        Collectors.toList()));
        return dataDomainMapper.selectList().stream()
                .sorted(Comparator.comparing(DataDomainDO::getId))
                .map(domain -> new LogicTableCatNodeRespVO("domain-" + domain.getId(), domain.getName(), "DATA_DOMAIN",
                        null, domain.getId(), null,
                        subjectsByDomain.getOrDefault(domain.getId(), List.of()).stream()
                                .map(subject -> new LogicTableCatNodeRespVO("subject-" + subject.getId(),
                                        subject.getName(), "SUBJECT_DOMAIN", null, domain.getId(), subject.getId(),
                                        List.of(), null))
                                .toList(), null))
                .toList();
    }

    /**
     * 获得启用的来源系统。
     *
     * @return 来源系统列表
     */
    @Override
    public List<SourceSystemSimpleRespVO> getSourceSystemList() {
        return sourceSystemMapper.selectEnabledList().stream()
                .map(source -> new SourceSystemSimpleRespVO(source.getId(), source.getName()))
                .toList();
    }

    /**
     * 启用或停用逻辑表。
     *
     * @param id 记录编号
     * @param enabled 启用状态
     */
    @Override
    public void updateEnabled(Long id, Integer enabled) {
        LogicModelDO model = validateExists(id);
        logicModelService.updateEnabled(id, enabled, model.getRevision());
    }

    /**
     * 批量删除前检查可删除的逻辑表。
     *
     * @param ids 记录编号集合
     * @return 逻辑表删除检查响应数据
     */
    @Override
    public LogicTableDeleteCheckRespVO checkDelete(Collection<Long> ids) {
        List<LogicModelDO> models = ids.isEmpty() ? List.of() : modelMapper.selectByIds(ids);
        List<Long> deletable = models.stream().filter(model -> !isEnabled(model)).map(LogicModelDO::getId).toList();
        return new LogicTableDeleteCheckRespVO(deletable.size(), models.size() - deletable.size(), deletable);
    }

    /**
     * 删除逻辑表；启用中的逻辑表不可删除。
     *
     * @param ids 记录编号集合
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLogicTables(Collection<Long> ids) {
        for (Long id : new LinkedHashSet<>(ids)) {
            LogicModelDO model = validateExists(id);
            if (isEnabled(model)) {
                throw exception(LOGIC_TABLE_ENABLED);
            }
            logicModelService.deleteModel(id, model.getRevision());
        }
    }

    /**
     * 校验存在性。
     *
     * @param id 记录编号
     * @return 逻辑模型
     */
    private LogicModelDO validateExists(Long id) {
        LogicModelDO model = modelMapper.selectById(id);
        if (model == null) {
            throw exception(LOGIC_MODEL_NOT_EXISTS);
        }
        return model;
    }

    /**
     * 判断是否满足启用状态条件。
     *
     * @param model 模型
     * @return 判断结果
     */
    private static boolean isEnabled(LogicModelDO model) {
        return Integer.valueOf(1).equals(model.getEnabled());
    }

}
