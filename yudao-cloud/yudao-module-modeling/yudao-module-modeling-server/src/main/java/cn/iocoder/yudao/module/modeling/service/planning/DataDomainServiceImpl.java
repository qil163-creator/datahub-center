package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.convert.planning.PlanningDomainConvert;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainNamingRuleVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainPageReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 数据分域 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DataDomainServiceImpl implements DataDomainService {

    @Resource
    private cn.iocoder.yudao.module.modeling.service.model.ModelPlanningReferenceGuard referenceGuard;

    @Resource
    private DataDomainMapper dataDomainMapper;

    @Resource
    private SubjectDomainMapper subjectDomainMapper;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 创建数据分域
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long createDataDomain(DataDomainSaveReqVO createReqVO) {
        validateRules(createReqVO.getNamingRules(), false, null);
        validateOwners(createReqVO);
        completeNamingRules(createReqVO, null);
        validateNameUnique(null, createReqVO.getName());
        validateCodeUnique(null, createReqVO.getCode());
        DataDomainDO dataDomain = PlanningDomainConvert.toDataObject(createReqVO);
        if (dataDomain.getStatus() == null) {
            dataDomain.setStatus(CommonStatusEnum.DISABLE.getStatus());
        }
        try {
            dataDomainMapper.insert(dataDomain);
        } catch (DuplicateKeyException e) {
            throw exception(DATA_DOMAIN_CODE_DUPLICATE);
        }
        return dataDomain.getId();
    }

    /**
     * 更新数据分域
     *
     * @param updateReqVO 更新参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDataDomain(DataDomainSaveReqVO updateReqVO) {
        validateRuleTypes(updateReqVO.getNamingRules(), false);
        validateOwners(updateReqVO);
        DataDomainDO current = dataDomainMapper.selectForUpdate(updateReqVO.getId());
        if (current == null) {
            throw exception(DATA_DOMAIN_NOT_EXISTS);
        }
        requireDisabled(current);
        validateRules(updateReqVO.getNamingRules(), false, current);
        completeNamingRules(updateReqVO, current);
        validateNameUnique(updateReqVO.getId(), updateReqVO.getName());
        validateCodeUnique(updateReqVO.getId(), updateReqVO.getCode());
        try {
            dataDomainMapper.updateById(PlanningDomainConvert.toDataObject(updateReqVO));
        } catch (DuplicateKeyException e) {
            throw exception(DATA_DOMAIN_CODE_DUPLICATE);
        }
    }

    /**
     * 更新数据分域状态
     *
     * @param id 记录编号
     * @param status 目标状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDataDomainStatus(Long id, Integer status) {
        if (dataDomainMapper.selectForUpdate(id) == null) {
            throw exception(DATA_DOMAIN_NOT_EXISTS);
        }
        dataDomainMapper.update(null, Wrappers.lambdaUpdate(DataDomainDO.class)
                .set(DataDomainDO::getStatus, status)
                .set(DataDomainDO::getUpdater, String.valueOf(
                        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId()))
                .set(DataDomainDO::getUpdateTime, java.time.LocalDateTime.now())
                .eq(DataDomainDO::getId, id));
    }

    /**
     * 更新板块六类逻辑表命名规则。
     *
     * @param request 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateNamingRules(
            cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainNamingRulesReqVO request) {
        validateRuleTypes(request.getNamingRules(), true);
        var current = dataDomainMapper.selectForUpdate(request.getId());
        if (current == null) {
            throw exception(DATA_DOMAIN_NOT_EXISTS);
        }
        validateRules(request.getNamingRules(), true, current);
        var row = new DataDomainDO();
        row.setId(request.getId());
        row.setNamingRules(
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(request.getNamingRules()));
        // 仅更新规则列，避免 DO 中 ALWAYS 策略的人员字段被清空。
        dataDomainMapper.updateNamingRules(row.getId(), row.getNamingRules(),
                String.valueOf(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId()));
    }

    /**
     * 删除数据分域（被统一模型或分析主题引用时不可删除）
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDataDomain(Long id) {
        requireDeletable(id);
        dataDomainMapper.deleteById(id);
    }

    /**
     * 在同一事务内删除所有选中板块，任一引用冲突则全部回滚。
     *
     * @param ids 记录编号集合
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDataDomains(List<Long> ids) {
        var sorted = ids.stream().distinct().sorted().toList();
        sorted.forEach(this::requireDeletable);
        sorted.forEach(dataDomainMapper::deleteById);
    }

    /**
     * 校验可删除。
     *
     * @param id 记录编号
     */
    private void requireDeletable(Long id) {
        DataDomainDO current = dataDomainMapper.selectForUpdate(id);
        if (current == null) {
            throw exception(DATA_DOMAIN_NOT_EXISTS);
        }
        requireDisabled(current);
        referenceGuard.requireDataDomainUnused(id);
        if (subjectDomainMapper.selectCount(SubjectDomainDO::getDataDomainId, id) > 0) {
            throw exception(DATA_DOMAIN_HAS_SUBJECTS);
        }
    }

    /**
     * 获得数据分域
     *
     * @param id 记录编号
     * @return 数据域
     */
    @Override
    public DataDomainDO getDataDomain(Long id) {
        return validateDataDomainExists(id);
    }

    /**
     * 获得数据分域分页
     *
     * @param pageReqVO 分页查询条件
     * @return 数据分域分页结果
     */
    @Override
    public PageResult<DataDomainDO> getDataDomainPage(DataDomainPageReqVO pageReqVO) {
        return dataDomainMapper.selectPage(pageReqVO);
    }

    /**
     * 获得数据分域全量列表（下拉选择用）
     *
     * @return 数据域精简列表
     */
    @Override
    public List<DataDomainDO> getDataDomainSimpleList() {
        return dataDomainMapper.selectSimpleList();
    }

    /**
     * 校验禁用状态。
     *
     * @param current 当前
     */
    private void requireDisabled(DataDomainDO current) {
        if (CommonStatusEnum.isEnable(current.getStatus())) {
            throw exception(DATA_DOMAIN_ENABLED);
        }
    }

    /**
     * 校验所有者。
     *
     * @param request 请求参数
     */
    private void validateOwners(DataDomainSaveReqVO request) {
        var userIds = new LinkedHashSet<Long>();
        userIds.add(request.getLeaderUserId());
        if (request.getBusinessOwnerIds() != null) {
            userIds.addAll(request.getBusinessOwnerIds());
        }
        if (request.getDataOwnerIds() != null) {
            userIds.addAll(request.getDataOwnerIds());
        }
        adminUserApi.validateUserList(userIds).checkError();
        var leader = adminUserApi.getUser(request.getLeaderUserId()).getCheckedData();
        request.setLeaderPhone(leader == null ? null : leader.getMobile());
    }

    /**
     * 完成命名规则。
     *
     * @param request 请求参数
     * @param current 当前
     */
    private void completeNamingRules(DataDomainSaveReqVO request, DataDomainDO current) {
        if (request.getNamingRules().size() == 6) {
            return;
        }
        // 创建向导配置四类；普通编辑保留详情中单独配置的维度与 ADS 规则。
        var complete = new java.util.LinkedHashMap<String, DataDomainNamingRuleVO>();
        DataDomainNamingRuleVO.detailDefaults().forEach(rule -> complete.put(rule.getTableType(), rule));
        if (current != null) {
            PlanningDomainConvert.toResponse(current).getNamingRules()
                    .forEach(rule -> complete.put(rule.getTableType(), rule));
        }
        request.getNamingRules().forEach(rule -> complete.put(rule.getTableType(), rule));
        request.setNamingRules(new java.util.ArrayList<>(complete.values()));
    }

    /**
     * 校验规则类型。
     *
     * @param rules 规则
     * @param detail 详情
     */
    private void validateRuleTypes(List<DataDomainNamingRuleVO> rules, boolean detail) {
        if (rules == null || rules.stream().anyMatch(java.util.Objects::isNull)
                || !(rules.size() == 6 || !detail && rules.size() == 4)
                || !rules.stream().map(DataDomainNamingRuleVO::getTableType)
                .collect(java.util.stream.Collectors.toSet()).equals(rules.size() == 6
                        ? Set.of("ODS", "DIM", "FACT_PROCESS", "FACT_EVENT", "DWS", "ADS")
                        : Set.of("ODS", "FACT_PROCESS", "FACT_EVENT", "DWS"))) {
            throw exception(DATA_DOMAIN_NAMING_RULE_INVALID);
        }
    }

    /**
     * 校验规则。
     *
     * @param rules 规则
     * @param detail 详情
     * @param current 当前
     */
    private void validateRules(List<DataDomainNamingRuleVO> rules, boolean detail, DataDomainDO current) {
        validateRuleTypes(rules, detail);
        var persisted = new java.util.HashMap<String, DataDomainNamingRuleVO>();
        DataDomainNamingRuleVO.detailDefaults().forEach(rule -> persisted.put(rule.getTableType(), rule));
        if (current != null) {
            PlanningDomainConvert.toResponse(current).getNamingRules()
                    .forEach(rule -> persisted.put(rule.getTableType(), rule));
        }
        for (DataDomainNamingRuleVO rule : rules) {
            String type = rule.getTableType();
            var expected = persisted.get(type);
            boolean fact = "FACT_PROCESS".equals(type) || "FACT_EVENT".equals(type);
            // 只读字段必须保持原值；创建时使用类型默认值，不借编辑接口改写只读配置。
            if (!java.util.Objects.equals(expected.getPrefix(), rule.getPrefix())
                    || (!fact && !java.util.Objects.equals(expected.getIncludeSubjectAbbreviation(),
                    rule.getIncludeSubjectAbbreviation()))
                    || (Set.of("ODS", "ADS").contains(type)
                    && (!sameSuffix(expected.getDaySuffix(), rule.getDaySuffix())
                    || !sameSuffix(expected.getHourSuffix(), rule.getHourSuffix())
                    || !sameSuffix(expected.getMinuteSuffix(), rule.getMinuteSuffix())))) {
                throw exception(DATA_DOMAIN_NAMING_RULE_INVALID);
            }
        }
    }

    /**
     * 判断是否一致后缀条件。
     *
     * @param first 首个
     * @param second 秒数
     * @return 判断结果
     */
    private static boolean sameSuffix(String first, String second) {
        return java.util.Objects.toString(first, "").equals(java.util.Objects.toString(second, ""));
    }

    /**
     * 校验数据域存在性。
     *
     * @param id 记录编号
     * @return 数据域
     */
    private DataDomainDO validateDataDomainExists(Long id) {
        DataDomainDO dataDomain = dataDomainMapper.selectById(id);
        if (dataDomain == null) {
            throw exception(DATA_DOMAIN_NOT_EXISTS);
        }
        return dataDomain;
    }

    /**
     * 校验名称唯一性。
     *
     * @param id 记录编号
     * @param name 名称
     */
    private void validateNameUnique(Long id, String name) {
        DataDomainDO exists = dataDomainMapper.selectByName(name);
        if (exists != null && !exists.getId().equals(id)) {
            throw exception(DATA_DOMAIN_NAME_DUPLICATE);
        }
    }

    /**
     * 校验编码唯一性。
     *
     * @param id 记录编号
     * @param code 编码
     */
    private void validateCodeUnique(Long id, String code) {
        DataDomainDO exists = dataDomainMapper.selectByCode(code);
        if (exists != null && !exists.getId().equals(id)) {
            throw exception(DATA_DOMAIN_CODE_DUPLICATE);
        }
    }

}
