package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDomainDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDomainMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

@Service
@Validated
@Slf4j
public class MasterDomainServiceImpl implements MasterDomainService {

    @Resource
    private MasterDomainMapper domainMapper;

    @Resource
    private MasterModelMapper modelMapper;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 创建域。
     */
    @Override
    public Long createDomain(MasterDomainSaveReqVO req) {
        AdminUserRespDTO leader = resolveLeader(req.getLeaderUserId());
        validateUnique(req);
        MasterDomainDO domain = BeanUtils.toBean(req, MasterDomainDO.class);
        domain.setId(null);
        domain.setSort(req.getSort() == null ? 99 : req.getSort());
        domain.setStatus(req.getStatus() == null ? 0 : req.getStatus());
        domain.setLeaderPhone(leader.getMobile());
        try {
            domainMapper.insert(domain);
        } catch (DuplicateKeyException ex) {
            throw duplicateError(ex);
        }
        return domain.getId();
    }

    /**
     * 更新域。
     */
    @Override
    public void updateDomain(MasterDomainSaveReqVO req) {
        // 负责人由提供方校验；RPC 结束后才开启平台事务和取得域行锁。
        AdminUserRespDTO leader = resolveLeader(req.getLeaderUserId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            MasterDomainDO current = requireLocked(req.getId());
            validateUnique(req);
            MasterDomainDO update = BeanUtils.toBean(req, MasterDomainDO.class);
            update.setSort(req.getSort() == null ? current.getSort() : req.getSort());
            update.setIcon(req.getIcon() == null ? current.getIcon() : req.getIcon());
            update.setStatus(req.getStatus() == null ? current.getStatus() : req.getStatus());
            update.setLeaderPhone(leader.getMobile());
            try {
                domainMapper.updateById(update);
                // 页面可清空说明字段；显式写 null，兼容 MyBatis 默认非空更新策略。
                domainMapper.update(null, Wrappers.lambdaUpdate(MasterDomainDO.class)
                        .eq(MasterDomainDO::getId, req.getId())
                        .set(MasterDomainDO::getDescription, req.getDescription())
                        .set(MasterDomainDO::getRemark, req.getRemark())
                        .set(MasterDomainDO::getLeaderPhone, leader.getMobile()));
            } catch (DuplicateKeyException ex) {
                throw duplicateError(ex);
            }
        });
    }

    /**
     * 更新主数据域的状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        requireLocked(id);
        domainMapper.updateById(MasterDomainDO.builder().id(id).status(status).build());
    }

    /**
     * 删除域。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDomain(Long id) {
        deleteLocked(id);
    }

    /**
     * 删除域列表。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDomainList(List<Long> ids) {
        // 固定锁顺序避免两个批次反向等待。
        ids.stream().distinct().sorted().forEach(this::deleteLocked);
    }

    /**
     * 删除已锁定。
     */
    private void deleteLocked(Long id) {
        requireLocked(id);
        if (modelMapper.selectCountByDomainId(id) > 0) {
            throw exception(MASTER_DOMAIN_HAS_MODEL);
        }
        domainMapper.deleteById(id);
    }

    /**
     * 读取域。
     */
    @Override
    public MasterDomainRespVO getDomain(Long id) {
        MasterDomainDO domain = domainMapper.selectById(id);
        if (domain == null) {
            throw exception(MASTER_DOMAIN_NOT_EXISTS);
        }
        MasterDomainRespVO resp = toResp(domain);
        enrichModelCounts(List.of(resp));
        enrichUsers(List.of(resp));
        return resp;
    }

    /**
     * 读取域分页。
     */
    @Override
    public PageResult<MasterDomainRespVO> getDomainPage(MasterDomainPageReqVO req) {
        PageResult<MasterDomainDO> page = domainMapper.selectPage(req);
        List<MasterDomainRespVO> result = page.getList().stream().map(this::toResp).toList();
        enrichModelCounts(result);
        enrichUsers(result);
        return new PageResult<>(result, page.getTotal());
    }

    /**
     * 读取简要列表。
     */
    @Override
    public List<MasterDomainRespVO> getSimpleList() {
        List<MasterDomainRespVO> result = domainMapper.selectSimpleList().stream().map(this::toResp).toList();
        enrichModelCounts(result);
        enrichUsers(result);
        return result;
    }

    /**
     * 将主数据域业务记录转换为接口响应。
     */
    private MasterDomainRespVO toResp(MasterDomainDO domain) {
        MasterDomainRespVO resp = BeanUtils.toBean(domain, MasterDomainRespVO.class);
        resp.setModelCount(0L);
        return resp;
    }

    /**
     * 校验主数据域的已锁定，不满足约束时拒绝当前操作。
     */
    private MasterDomainDO requireLocked(Long id) {
        MasterDomainDO domain = id == null ? null : domainMapper.selectForUpdate(id);
        if (domain == null) {
            throw exception(MASTER_DOMAIN_NOT_EXISTS);
        }
        return domain;
    }

    /**
     * 解析主数据域的负责人。
     */
    private AdminUserRespDTO resolveLeader(Long id) {
        if (id == null) {
            throw exception(MASTER_DOMAIN_LEADER_REQUIRED);
        }
        AdminUserRespDTO user = adminUserApi.validateUser(id);
        if (user == null || !Objects.equals(id, user.getId())) {
            throw exception(MASTER_DOMAIN_LEADER_INVALID);
        }
        return user;
    }

    /**
     * 补充主数据域的模型数量。
     */
    private void enrichModelCounts(List<MasterDomainRespVO> domains) {
        if (domains.isEmpty()) {
            return;
        }
        List<Long> ids = domains.stream().map(MasterDomainRespVO::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        modelMapper.selectMaps(new QueryWrapper<MasterModelDO>()
                        .select("domain_id", "COUNT(*) AS model_count").in("domain_id", ids).groupBy("domain_id"))
                .forEach(row -> counts.put(((Number) row.get("domain_id")).longValue(),
                        ((Number) row.get("model_count")).longValue()));
        domains.forEach(domain -> domain.setModelCount(counts.getOrDefault(domain.getId(), 0L)));
    }

    /** 一个分页请求只查询一次通讯录，历史非数字审计标识原样保留。 */
    private void enrichUsers(List<MasterDomainRespVO> domains) {
        Set<Long> ids = new HashSet<>();
        for (MasterDomainRespVO domain : domains) {
            if (domain.getLeaderUserId() != null) {
                ids.add(domain.getLeaderUserId());
            }
            Long creator = userId(domain.getCreator());
            Long updater = userId(domain.getUpdater());
            if (creator != null) {
                ids.add(creator);
            }
            if (updater != null) {
                ids.add(updater);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            Map<Long, AdminUserRespDTO> users = adminUserApi.getUserMap(ids);
            for (MasterDomainRespVO domain : domains) {
                AdminUserRespDTO leader = domain.getLeaderUserId() == null ? null : users.get(domain.getLeaderUserId());
                if (leader != null) {
                    domain.setLeaderUserName(leader.getNickname());
                    domain.setLeaderPhone(leader.getMobile());
                }
                Long creatorId = userId(domain.getCreator());
                Long updaterId = userId(domain.getUpdater());
                AdminUserRespDTO creator = creatorId == null ? null : users.get(creatorId);
                AdminUserRespDTO updater = updaterId == null ? null : users.get(updaterId);
                domain.setCreatorName(creator == null ? null : creator.getNickname());
                domain.setUpdaterName(updater == null ? null : updater.getNickname());
            }
        } catch (RuntimeException failure) {
            log.warn("[enrichUsers][主数据域用户名称查询失败，异常类型 {}]", failure.getClass().getSimpleName());
        }
    }

    /**
     * 读取并校验当前用户编号。
     */
    private static Long userId(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * 校验主数据域的在指定范围内唯一。
     */
    private void validateUnique(MasterDomainSaveReqVO req) {
        MasterDomainDO sameCode = domainMapper.selectByCode(req.getCode());
        if (sameCode != null && !Objects.equals(req.getId(), sameCode.getId())) {
            throw exception(MASTER_DOMAIN_CODE_DUPLICATE);
        }
        MasterDomainDO sameName = domainMapper.selectByName(req.getName());
        if (sameName != null && !Objects.equals(req.getId(), sameName.getId())) {
            throw exception(MASTER_DOMAIN_NAME_DUPLICATE);
        }
    }

    /**
     * 构造主数据域重复错误对应的异常。
     */
    private RuntimeException duplicateError(DuplicateKeyException error) {
        String detail = error.getMostSpecificCause().getMessage();
        return exception(detail != null && detail.contains("uk_master_domain_name")
                ? MASTER_DOMAIN_NAME_DUPLICATE : MASTER_DOMAIN_CODE_DUPLICATE);
    }
}
