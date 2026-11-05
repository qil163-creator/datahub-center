package cn.iocoder.yudao.module.supply.service.app;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.monitor.TracerUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantOpRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.SupplyGrantOpDO;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.SupplyGrantOpMapper;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiContract;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;

import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiGrantScopeRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiRespVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiSaveReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.AppApiUpdateReqVO;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantReadService;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyAppPageReqVO;
import cn.iocoder.yudao.module.supply.controller.admin.app.vo.SupplyAppSaveReqVO;
import cn.iocoder.yudao.module.supply.dal.dataobject.app.SupplyAppDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.appapi.AppApiDO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.app.SupplyAppMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.appapi.AppApiMapper;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.app.vo.SupplyAppCreateResult;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyAppSecretCipher;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.foundation.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.supply.enums.grant.ErrorCodeConstants.*;

/**
 * 数据服务-应用 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
@Slf4j
public class SupplyAppServiceImpl implements SupplyAppService {

    @Resource
    private SupplyAppMapper appMapper;

    @Resource
    private AppApiMapper appApiMapper;

    @Resource
    private DataApiMapper dataApiMapper;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private SupplyGrantOpMapper grantOpMapper;

    @Resource
    private SupplyGrantReadService grantReadService;

    @Resource
    private SupplyEtlGrantPreflight etlGrantPreflight;

    @Resource
    private SupplyEtlGrantAuditService etlGrantAudit;

    @Autowired
    private ObjectProvider<ILogRecordService> operationLogs;

    // ==================== 应用 CRUD ====================

    /**
     * 创建应用（自动生成应用秘钥）
     *
     * @param createReqVO 创建参数
     * @return 供应应用创建结果
     */
    @Override
    public SupplyAppCreateResult createApp(SupplyAppSaveReqVO createReqVO) {
        validateNameUnique(null, createReqVO.getName());
        SupplyAppDO app = BeanUtils.toBean(createReqVO, SupplyAppDO.class);
        app.setAppKey(IdUtil.fastSimpleUUID());
        String plainSecret = generateSecret();
        app.setSecret(SupplyAppSecretCipher.encrypt(plainSecret));
        app.setSignProtocol(SupplyAppDO.SIGN_PROTOCOL_V2);
        if (app.getPublicFlag() == null) {
            app.setPublicFlag(1);
        }
        if (app.getAppStatus() == null) {
            app.setAppStatus(SupplyGrantValidity.APP_ENABLED);
        }
        if (app.getOwningDeptId() == null) {
            app.setOwningDeptId(SecurityFrameworkUtils.getLoginUserDeptId());
        }
        if (app.getOwnerUserId() == null) {
            app.setOwnerUserId(SecurityFrameworkUtils.getLoginUserId());
        }
        app.setCredentialRevision(1);
        app.setRowVersion(0);
        appMapper.insert(app);
        return new SupplyAppCreateResult(app, plainSecret);
    }

    /**
     * 更新应用（秘钥不随保存修改）
     *
     * @param updateReqVO 更新参数
     */
    @Override
    public void updateApp(SupplyAppSaveReqVO updateReqVO) {
        validateAppExists(updateReqVO.getId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getName());
        SupplyAppDO app = BeanUtils.toBean(updateReqVO, SupplyAppDO.class);
        // AppKey、秘钥不随保存修改
        app.setAppKey(null);
        app.setSecret(null);
        app.setSignProtocol(null);
        appMapper.updateById(app);
    }

    /**
     * 重置应用秘钥
     *
     * @param id 记录编号
     * @return 重置应用秘钥文本
     */
    @Override
    public String resetAppSecret(Long id) {
        validateAppExists(id);
        String secret = generateSecret();
        appMapper.update(null, Wrappers.lambdaUpdate(SupplyAppDO.class)
                .set(SupplyAppDO::getSecret, SupplyAppSecretCipher.encrypt(secret))
                .setSql("credential_revision = COALESCE(credential_revision, 0) + 1")
                .eq(SupplyAppDO::getId, id));
        return secret;
    }

    /**
     * 删除应用（级联删除 API 授权）
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteApp(Long id) {
        validateAppExists(id);
        appMapper.deleteById(id);
        appApiMapper.deleteByAppId(id);
    }

    /**
     * 获得应用
     *
     * @param id 记录编号
     * @return 供应应用
     */
    @Override
    public SupplyAppDO getApp(Long id) {
        return validateAppExists(id);
    }

    /**
     * 获得应用分页
     *
     * @param pageReqVO 分页查询条件
     * @return 应用分页结果
     */
    @Override
    public PageResult<SupplyAppDO> getAppPage(SupplyAppPageReqVO pageReqVO) {
        return appMapper.selectPage(pageReqVO);
    }

    /**
     * 生成密钥。
     *
     * @return 生成密钥文本
     */
    private String generateSecret() {
        return IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID().substring(0, 8);
    }

    /**
     * 校验应用存在性。
     *
     * @param id 记录编号
     * @return 供应应用
     */
    private SupplyAppDO validateAppExists(Long id) {
        SupplyAppDO app = appMapper.selectById(id);
        if (app == null) {
            throw exception(SUPPLY_APP_NOT_EXISTS);
        }
        return app;
    }

    /**
     * 校验名称唯一性。
     *
     * @param id 记录编号
     * @param name 名称
     */
    private void validateNameUnique(Long id, String name) {
        SupplyAppDO exists = appMapper.selectByName(name);
        if (exists != null && !exists.getId().equals(id)) {
            throw exception(SUPPLY_APP_NAME_DUPLICATE);
        }
    }

    // ==================== API 授权 ====================

    /**
     * 获得应用的 API 授权列表（含 API 信息与有效状态）
     *
     * @param appId 应用编号
     * @return 应用的 API 授权列表（含 API 信息与有效状态）结果集合
     */
    @Override
    public List<AppApiRespVO> getAppApiList(Long appId) {
        validateAppExists(appId);
        List<AppApiDO> list = appApiMapper.selectListByAppId(appId);
        Map<Long, DataApiDO> apiMap = list.isEmpty() ? Map.of()
                : convertMap(dataApiMapper.selectBatchIds(
                convertSet(list, AppApiDO::getApiId)), DataApiDO::getId);
        List<AppApiRespVO> result = new ArrayList<>();
        for (AppApiDO appApi : list) {
            result.add(toAppApiResponse(appApi, apiMap.get(appApi.getApiId()),
                    grantReadService.describe(appApi.getId())));
        }
        return result;
    }

    /**
     * 按授权编号读取平台内应用授权，供编辑前重新获取当前期限、描述和控制版本。
     *
     * @param id 记录编号
     * @return 应用 API 授权响应数据
     */
    @Override
    public AppApiRespVO getAppApi(Long id) {
        AppApiDO grant = appApiMapper.selectGrantControlById(id);
        if (grant == null) {
            throw exception(SUPPLY_APP_API_NOT_EXISTS);
        }
        if (appMapper.selectGrantControl(grant.getAppId()) == null) {
            throw exception(SUPPLY_APP_NOT_EXISTS);
        }
        DataApiDO api = dataApiMapper.selectGrantControl(grant.getApiId());
        AppApiGrantScopeRespVO effective = grantReadService.describe(id);
        // 编辑详情不能把旧期限与较新的控制版本拼接，否则旧表单可能覆盖已提交的修改。
        if (!java.util.Objects.equals(grant.getAggregateRevision(), effective.getAggregateRevision())) {
            throw exception(GRANT_SCOPE_CHANGED);
        }
        return toAppApiResponse(grant, api, effective);
    }

    /**
     * 转换为应用 API 授权响应。
     *
     * @param grant 授权
     * @param api 本次使用的 API 定义
     * @param effective 生效
     * @return 应用 API 授权响应数据
     */
    private static AppApiRespVO toAppApiResponse(AppApiDO grant, DataApiDO api, AppApiGrantScopeRespVO effective) {
        AppApiRespVO vo = BeanUtils.toBean(grant, AppApiRespVO.class);
        if (api != null) {
            vo.setApiName(api.getName());
            vo.setApiDescription(api.getDescription());
            vo.setApiMethod(api.getMethod());
            vo.setApiPath("/services/" + StrUtil.nullToEmpty(api.getVersion()) + StrUtil.nullToEmpty(api.getPath()));
        }
        // v2 的 grant_status=3 是旧执行器的隔离兼容位；实际状态仍由同一权威读取判定。
        vo.setAuthStatus(listAuthStatus(grant, effective));
        return vo;
    }

    /**
     * 列出认证状态。
     *
     * @param aggregate 聚合
     * @param effective 生效
     * @return 列表认证状态数值
     */
    private static int listAuthStatus(AppApiDO aggregate, AppApiGrantScopeRespVO effective) {
        if (AppApiGrantScopeRespVO.MODE_ACTIVE.equals(effective.getMode())) {
            return SupplyGrantValidity.STATUS_ACTIVE;
        }
        // 只有独立 ADMIN 的持久终态可沿用“已撤销”标签；市场来源必须看账本，不能反推。
        if (AppApiGrantScopeRespVO.MODE_NONE.equals(effective.getMode())
                && SupplyGrantValidity.isIndependentAdminGrant(aggregate)
                && Integer.valueOf(SupplyGrantValidity.STATUS_REVOKED).equals(aggregate.getGrantStatus())) {
            return SupplyGrantValidity.STATUS_REVOKED;
        }
        return SupplyGrantValidity.STATUS_PAUSED;
    }

    /**
     * 管理端创建独立 ADMIN 授权；不接管市场来源或 v2 聚合。
     *
     * @param req 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAppApi(AppApiSaveReqVO req) {
        validateAdminValidity(req);
        var etlProof = etlGrantPreflight.prepareCreate(req.getApiId());
        SupplyAppDO app = appMapper.selectGrantParentForUpdate(req.getAppId());
        if (app == null || Boolean.TRUE.equals(app.getDeleted())) {
            throw exception(SUPPLY_APP_NOT_EXISTS);
        }
        AppApiDO existing = appApiMapper.selectAggregateForUpdate(req.getAppId(), req.getApiId());
        if (existing != null && !SupplyGrantValidity.isIndependentAdminGrant(existing)) {
            throw exception(GRANT_SCOPE_UNCONFIRMED);
        }
        if (existing != null && !Integer.valueOf(SupplyGrantValidity.STATUS_REVOKED)
                .equals(existing.getGrantStatus())) {
            throw exception(SUPPLY_APP_API_DUPLICATE);
        }
        DataApiDO api = dataApiMapper.selectGrantContractForShare(req.getApiId());
        if (api == null) {
            throw exception(DATA_API_NOT_EXISTS);
        }
        if (!Integer.valueOf(1).equals(app.getAppStatus()) || !Integer.valueOf(1).equals(api.getStatus())) {
            throw exception(GRANT_NOT_EFFECTIVE);
        }
        Long executionActor = SupplyEtlGrantPreflight.assertCurrent(etlProof, api, null);
        String sourceType = executionActor == null ? "ADMIN" : SupplyGrantValidity.ADMIN_ETL_BATCH;
        // ADMIN 独立于市场个人来源；客户端 id、来源、主体、合同和版本均不作为授权依据。
        AppApiDO grant = AppApiDO.builder().appId(req.getAppId()).apiId(req.getApiId())
                .foreverFlag(req.getForeverFlag()).startDate(req.getStartDate()).endDate(req.getEndDate())
                .description(req.getDescription()).remark(req.getRemark()).sourceType(sourceType)
                .sourceId((executionActor == null ? "admin" : "admin-etl:user:" + executionActor)
                        + ":app:" + req.getAppId() + ":api:" + req.getApiId())
                .permissionUserId(executionActor).grantStatus(SupplyGrantValidity.STATUS_ACTIVE)
                .grantRevision(existing == null ? 1 : Math.addExact(existing.getGrantRevision(), 1))
                .approvedContractHash(StrUtil.blankToDefault(api.getContractHash(), DataApiContract.contractHash(api)))
                .apiVersion(api.getVersion()).protocolVersion(0)
                .aggregateRevision(existing == null ? 0L : Math.addExact(existing.getAggregateRevision(), 1L))
                .aggregateDisabled(existing != null && Boolean.TRUE.equals(existing.getAggregateDisabled()))
                .migrationStatus("REVIEW_REQUIRED").build();
        SupplyGrantValidity.fillInstantBounds(grant);
        if (executionActor != null) {
            grant.setPurpose("ETL_BATCH:" + api.getEtlBatchId() + ":" + api.getEtlBatchHash());
            if (existing == null) {
                grant.setCreator(String.valueOf(executionActor));
            }
            grant.setUpdater(String.valueOf(executionActor));
        }
        if (existing == null) {
            appApiMapper.insert(grant);
        } else {
            grant.setId(existing.getId());
            // 这是完整重建期限，不是忽略空值的 PATCH；默认 NOT_NULL 更新会遗留旧截止/开始时间。
            int changed = appApiMapper.update(grant, Wrappers.lambdaUpdate(AppApiDO.class)
                    .eq(AppApiDO::getId, existing.getId())
                    .eq(AppApiDO::getProtocolVersion, 0).eq(AppApiDO::getSourceType, existing.getSourceType())
                    .isNull(existing.getPermissionUserId() == null, AppApiDO::getPermissionUserId)
                    .eq(existing.getPermissionUserId() != null, AppApiDO::getPermissionUserId,
                            existing.getPermissionUserId())
                    .eq(AppApiDO::getAggregateRevision, existing.getAggregateRevision())
                    .set(grant.getStartDate() == null, AppApiDO::getStartDate, null)
                    .set(grant.getEndDate() == null, AppApiDO::getEndDate, null)
                    .set(grant.getValidFrom() == null, AppApiDO::getValidFrom, null)
                    .set(grant.getValidTo() == null, AppApiDO::getValidTo, null)
                    .set(grant.getPermissionUserId() == null, AppApiDO::getPermissionUserId, null)
                    .set(grant.getPurpose() == null, AppApiDO::getPurpose, null));
            if (changed != 1) {
                throw exception(GRANT_CONTROL_CONFLICT);
            }
        }
        if (SupplyGrantValidity.isEtlAdminGrant(existing) || SupplyGrantValidity.isEtlAdminGrant(grant)) {
            var persisted = appApiMapper.selectGrantControlById(grant.getId());
            assertPersistedGrant(grant, persisted);
            etlGrantAudit.append(existing == null ? "CREATE" : "REGRANT", existing, persisted,
                    existing == null ? "管理端明确授予指定 ETL 批次访问权限" : "管理端在终态关闭后明确重新授予权限",
                    null, null);
        }
        return grant.getId();
    }

    /**
     * 修改普通独立 ADMIN 的期限和描述；不更换授权身份，整体禁用状态保持原值。
     *
     * @param req 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAppApi(AppApiUpdateReqVO req) {
        Long actor = SecurityFrameworkUtils.getLoginUserId();
        if (actor == null || actor <= 0) {
            throw exception(GRANT_SUBJECT_INVALID);
        }
        validateAdminValidity(req.getForeverFlag(), req.getStartDate(), req.getEndDate());
        AppApiDO grant = lockGrantControl(req.getId());
        // 普通 ADMIN 之外的批准范围属于来源系统或 ETL 授权流程，不能通过编辑表单接管。
        if (!SupplyGrantValidity.isIndependentAdminGrant(grant) || !"ADMIN".equals(grant.getSourceType())) {
            throw exception(GRANT_SCOPE_UNCONFIRMED);
        }
        if (req.getExpectedRevision() == null || req.getExpectedRevision() < 0
                || !java.util.Objects.equals(grant.getAggregateRevision(), req.getExpectedRevision())) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        if (Integer.valueOf(SupplyGrantValidity.STATUS_REVOKED).equals(grant.getGrantStatus())) {
            throw exception(GRANT_NOT_EFFECTIVE);
        }
        SupplyAppDO app = appMapper.selectGrantControl(grant.getAppId());
        DataApiDO api = dataApiMapper.selectGrantContractForShare(grant.getApiId());
        if (app == null || api == null || !Integer.valueOf(1).equals(app.getAppStatus())
                || !Integer.valueOf(1).equals(api.getStatus())) {
            throw exception(GRANT_NOT_EFFECTIVE);
        }
        if (DataApiDO.CONFIG_TYPE_ETL_BATCH.equals(api.getConfigType())
                || !SupplyGrantValidity.matchesCurrentContract(grant, api.getContractHash())) {
            throw exception(GRANT_SCOPE_UNCONFIRMED);
        }
        AppApiDO updated = BeanUtils.toBean(grant, AppApiDO.class);
        updated.setForeverFlag(req.getForeverFlag());
        updated.setStartDate(req.getStartDate());
        updated.setEndDate(req.getEndDate());
        updated.setValidFrom(null);
        updated.setValidTo(null);
        updated.setDescription(req.getDescription());
        updated.setUpdater(String.valueOf(actor));
        SupplyGrantValidity.fillInstantBounds(updated);
        updated.setAggregateRevision(Math.addExact(grant.getAggregateRevision(), 1L));
        updated.setGrantRevision(Math.addExact(grant.getGrantRevision(), 1));
        if (appApiMapper.updateAdminGrantValidity(grant.getId(), req.getExpectedRevision(),
                updated.getForeverFlag(), updated.getStartDate(), updated.getEndDate(), updated.getValidFrom(),
                updated.getValidTo(), updated.getDescription(), actor) != 1) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        // 与控制操作复用落库核对；客户端成功后再读取权威授权状态，不能按本地表单推断有效。
        assertPersistedGrant(updated, appApiMapper.selectGrantControlById(grant.getId()));
        auditAdminUpdate(actor, grant, updated, app.getName(), api.getName());
    }

    /**
     * 记录普通管理员授权修改的前后快照，并在事务提交后发送操作日志。
     *
     * @param actor 操作人编号
     * @param before 变更前状态
     * @param after 变更后状态
     * @param appName 应用名称
     * @param apiName API 名称
     */
    private void auditAdminUpdate(long actor, AppApiDO before, AppApiDO after, String appName, String apiName) {
        ILogRecordService recorder = operationLogs.getObject();
        String traceId = TracerUtils.getTraceId();
        var extra = new LinkedHashMap<String, Object>();
        extra.put("actorId", actor);
        extra.put("grantId", after.getId());
        extra.put("appId", after.getAppId());
        extra.put("apiId", after.getApiId());
        extra.put("traceId", traceId);
        extra.put("before", adminUpdateSnapshot(before));
        extra.put("after", adminUpdateSnapshot(after));
        extra.put("reason", "管理员明确修改普通独立授权的期限和描述");
        extra.put("result", "SUCCESS");
        String oldValidity = Integer.valueOf(1).equals(before.getForeverFlag()) ? "永久有效"
                : before.getStartDate() + " 至 " + before.getEndDate();
        String newValidity = Integer.valueOf(1).equals(after.getForeverFlag()) ? "永久有效"
                : after.getStartDate() + " 至 " + after.getEndDate();
        String content = "修改了应用【" + (appName == null ? "编号" + after.getAppId() : appName)
                + "】对 API【" + (apiName == null ? "编号" + after.getApiId() : apiName) + "】的授权；有效期"
                + (oldValidity.equals(newValidity) ? "【" + newValidity + "】"
                : "从【" + oldValidity + "】调整为【" + newValidity + "】");
        if (!java.util.Objects.equals(before.getDescription(), after.getDescription())) {
            content += "；更新了授权说明";
        }
        LogRecord record = LogRecord.builder().operator(String.valueOf(actor))
                .type("数据服务应用授权").subType("修改普通管理员授权").bizNo(String.valueOf(after.getId()))
                .action(content).extra(JsonUtils.toJsonString(extra)).build();
        // 仅提交成功后调用已有操作日志组件；不持锁执行 RPC，不把失败或回滚记为成功。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 在本地事务成功提交后触发对应的后续处理。
             */
            @Override
            public void afterCommit() {
                try {
                    recorder.record(record);
                } catch (RuntimeException failure) {
                    // 已提交结果不能因尽力投递失败变成可重发的失败响应；保留不含描述原文的告警。
                    log.error("普通授权修改操作日志未投递：actor={} grant={} revision={} trace={} failureType={}",
                            actor, after.getId(), after.getAggregateRevision(), traceId,
                            failure.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * 获取供应应用的管理端更新快照。
     *
     * @param grant 授权
     * @return 处理后的键值映射
     */
    private static Map<String, Object> adminUpdateSnapshot(AppApiDO grant) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("foreverFlag", grant.getForeverFlag());
        snapshot.put("startDate", grant.getStartDate());
        snapshot.put("endDate", grant.getEndDate());
        snapshot.put("validFrom", SupplyGrantValidity.effectiveFrom(grant));
        snapshot.put("validTo", SupplyGrantValidity.effectiveTo(grant));
        snapshot.put("aggregateRevision", grant.getAggregateRevision());
        snapshot.put("grantRevision", grant.getGrantRevision());
        snapshot.put("aggregateDisabled", grant.getAggregateDisabled());
        snapshot.put("grantStatus", grant.getGrantStatus());
        // 自由描述可能包含敏感信息，只保留摘要；空值与空字符串保持可区分。
        snapshot.put("descriptionHash",
                grant.getDescription() == null ? null : DigestUtil.sha256Hex(grant.getDescription()));
        return snapshot;
    }

    /**
     * 整体禁用应用 API 授权；保留来源和历史回执，不逐来源撤销。
     *
     * @param id 记录编号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAppApi(Long id) {
        AppApiDO grant = lockGrantControl(id);
        if (!Boolean.TRUE.equals(grant.getAggregateDisabled())) {
            if (appApiMapper.changeAggregateDisabled(id, true, grant.getAggregateRevision()) != 1) {
                throw new IllegalStateException("Grant control changed outside its transaction");
            }
            auditControl("DISABLE", grant, null, null);
        }
    }

    /**
     * 旧 RPC 写协议已拒绝：缺少冻结字段和来源生命周期，需升级协议。
     *
     * @param reqVO 请求参数
     * @return 授权应用 API 授权数值
     */
    @Override
    public Long grantAppApi(AppApiSaveReqVO reqVO) {
        // 旧 RPC 缺少冻结字段和来源生命周期证据，声称 ADMIN 也不能绕过。
        throw exception(GRANT_LEGACY_PROTOCOL_REQUIRED);
    }

    /**
     * 旧 RPC 撤销已拒绝：应用 + API 不能证明待撤销来源。
     *
     * @param appId 应用编号
     * @param apiId API 编号
     */
    @Override
    public void revokeAppApi(Long appId, Long apiId) {
        throw exception(GRANT_LEGACY_PROTOCOL_REQUIRED);
    }

    /**
     * 旧 RPC 撤销已拒绝；即使带幂等键也不能推断来源生命周期。
     *
     * @param reqVO 请求参数
     */
    @Override
    public void revokeAppApi(AppApiSaveReqVO reqVO) {
        // app/API 不能证明要撤销哪个来源，也不能冒充管理端整体禁用。
        throw exception(GRANT_LEGACY_PROTOCOL_REQUIRED);
    }

    /**
     * 查询授权交付结果
     *
     * @param sourceSystem 来源系统
     * @param operationId 操作编号
     * @return 供应授权操作响应数据
     */
    @Override
    public SupplyGrantOpRespDTO getGrantOperation(String sourceSystem, String operationId) {
        SupplyGrantOpDO op = grantOpMapper.selectSourceOperation(sourceSystem, operationId);
        if (op == null) {
            throw exception(SUPPLY_GRANT_OP_NOT_EXISTS);
        }
        SupplyGrantOpRespDTO dto = new SupplyGrantOpRespDTO();
        dto.setSourceSystem(op.getSourceSystem());
        dto.setOperationId(op.getOperationId());
        dto.setResultStatus(op.getResultStatus());
        dto.setGrantId(op.getGrantId());
        dto.setGrantRevision(op.getGrantRevision());
        dto.setErrorCode(op.getErrorCode());
        dto.setPayloadHash(op.getPayloadHash());
        return dto;
    }

    /**
     * 启用/暂停/撤销应用，不影响已落库的授权范围
     *
     * @param id 记录编号
     * @param appStatus 应用状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAppStatus(Long id, Integer appStatus) {
        // 与来源协调器保持应用父行 -> 授权聚合的锁顺序，覆盖并发首次授权。
        SupplyAppDO app = appMapper.selectGrantParentForUpdate(id);
        if (app == null || Boolean.TRUE.equals(app.getDeleted())) {
            throw exception(SUPPLY_APP_NOT_EXISTS);
        }
        if (java.util.Objects.equals(app.getAppStatus(), appStatus)) {
            return;
        }
        List<AppApiDO> affectedEtl = appApiMapper.selectEtlGrantsForApp(id);
        int updated = appMapper.update(null, Wrappers.lambdaUpdate(SupplyAppDO.class)
                .set(SupplyAppDO::getAppStatus, appStatus)
                .eq(SupplyAppDO::getId, id));
        if (updated != 1) {
            throw new IllegalStateException("Application control changed outside its transaction");
        }
        // 状态和版本必须同次提交；停用后恢复也不能复用停用前的在途查询结果。
        appApiMapper.advanceAppControlRevision(id);
        String action = Integer.valueOf(SupplyGrantValidity.APP_ENABLED).equals(appStatus) ? "APP_ENABLE"
                : Integer.valueOf(SupplyGrantValidity.APP_REVOKED).equals(appStatus) ? "APP_REVOKE" : "APP_DISABLE";
        for (AppApiDO grant : affectedEtl) {
            auditControl(action, grant, app.getAppStatus(), appStatus);
        }
    }

    /**
     * 列出指定用户创建的应用。
     *
     * @param userId 用户编号
     * @return 指定用户创建的应用结果集合
     */
    @Override
    public List<SupplyAppDO> listAppsByCreator(Long userId) {
        return appMapper.selectListByCreator(userId);
    }

    /**
     * 将当前平台的旧协议 UNKNOWN 标为需要人工复核；不猜测历史结果，不处理 v2 操作。
     *
     * @return 对账未知授权运维数值
     */
    @Override
    public int reconcileUnknownGrantOps() {
        int handled = 0;
        for (SupplyGrantOpDO op : grantOpMapper.selectLegacyUnknownDue(100)) {
            // 当前聚合不是历史操作证据；保留 UNKNOWN，停止自动猜测，转人工复核。
            handled += grantOpMapper.markLegacyUnknownForReview(op.getId());
        }
        return handled;
    }

    /**
     * 明确重新启用聚合；版本来自最近一次读取，不恢复已撤销来源。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableAppApi(Long id, Long expectedRevision) {
        var etlProof = etlGrantPreflight.prepareEnable(id);
        AppApiDO grant = lockGrantControl(id);
        if (expectedRevision == null || expectedRevision < 0
                || !java.util.Objects.equals(grant.getAggregateRevision(), expectedRevision)) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        boolean market = Integer.valueOf(2).equals(grant.getProtocolVersion())
                && "CONFIRMED".equals(grant.getMigrationStatus());
        boolean admin = SupplyGrantValidity.isIndependentAdminGrant(grant);
        if (!market && !admin) {
            throw exception(GRANT_SCOPE_UNCONFIRMED);
        }
        // 永久关闭的 ADMIN 是终态：启用不能让它复活，重新授权走新的修订。
        if (admin && Integer.valueOf(SupplyGrantValidity.STATUS_REVOKED).equals(grant.getGrantStatus())) {
            throw exception(GRANT_NOT_EFFECTIVE);
        }
        SupplyAppDO app = appMapper.selectGrantControl(grant.getAppId());
        DataApiDO api = dataApiMapper.selectGrantContractForShare(grant.getApiId());
        if (app == null || api == null || !Integer.valueOf(1).equals(app.getAppStatus())
                || !Integer.valueOf(1).equals(api.getStatus())) {
            throw exception(GRANT_NOT_EFFECTIVE);
        }
        if (SupplyGrantValidity.isEtlAdminGrant(grant) || etlProof != null) {
            SupplyEtlGrantPreflight.assertCurrent(etlProof, api, grant);
        }
        // 只修改聚合开关；来源、期限、批准字段和 grant_status 均保持原值。
        if (Boolean.TRUE.equals(grant.getAggregateDisabled())) {
            if (appApiMapper.changeAggregateDisabled(id, false, expectedRevision) != 1) {
                throw exception(GRANT_CONTROL_CONFLICT);
            }
            auditControl("ENABLE", grant, null, null);
        }
    }

    /**
     * 聚合整体禁用开关。两个方向都要求 expectedRevision 等于最近一次读取的聚合版本，
     *  避免旧页面覆盖更新的决定；不改变逐来源生命周期。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     * @param disabled 禁用状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setGrantControl(Long id, Long expectedRevision, boolean disabled) {
        if (!disabled) {
            enableAppApi(id, expectedRevision);
            return;
        }

        AppApiDO grant = lockGrantControl(id);
        // 禁用方向同样要求版本一致：旧页面不能把已经重新启用的聚合又关掉。
        if (expectedRevision == null || expectedRevision < 0
                || !java.util.Objects.equals(grant.getAggregateRevision(), expectedRevision)) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        if (Boolean.TRUE.equals(grant.getAggregateDisabled())) {
            return; // 幂等：已禁用不再推进聚合版本
        }
        if (appApiMapper.changeAggregateDisabled(id, true, expectedRevision) != 1) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        auditControl("DISABLE", grant, null, null);
    }

    /**
     * 永久关闭管理端 ADMIN 授权（终态，不可通过启用恢复）；只作用于独立 ADMIN 聚合，
     *  市场 v2 聚合必须由来源系统逐来源撤销。关闭后该聚合不再阻止 API 退役。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAdminAppApi(Long id, Long expectedRevision) {
        AppApiDO grant = lockGrantControl(id);
        boolean admin = SupplyGrantValidity.isIndependentAdminGrant(grant);
        // 市场 v2 聚合与历史未确认聚合的来源生命周期属于来源系统，管理端不能整体终止。
        if (!admin) {
            throw exception(GRANT_SCOPE_UNCONFIRMED);
        }
        if (expectedRevision == null || expectedRevision < 0
                || !java.util.Objects.equals(grant.getAggregateRevision(), expectedRevision)) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        if (Integer.valueOf(SupplyGrantValidity.STATUS_REVOKED).equals(grant.getGrantStatus())) {
            return; // 终态幂等，不再推进版本
        }
        if (appApiMapper.closeAdminGrant(id, expectedRevision) != 1) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
        auditControl("REVOKE", grant, null, null);
    }

    /**
     * 复核授权控制及应用状态已按预期落库，再追加授权控制审计。
     *
     * @param action 待执行的操作
     * @param before 变更前状态
     * @param oldAppStatus 历史应用状态
     * @param newAppStatus 新增应用状态
     */
    private void auditControl(String action, AppApiDO before, Integer oldAppStatus, Integer newAppStatus) {
        if (!SupplyGrantValidity.isEtlAdminGrant(before)) {
            return;
        }
        var expected = BeanUtils.toBean(before, AppApiDO.class);
        expected.setAggregateRevision(Math.addExact(before.getAggregateRevision(), 1L));
        expected.setGrantRevision(Math.addExact(before.getGrantRevision(), 1));
        switch (action) {
            case "ENABLE" -> expected.setAggregateDisabled(false);
            case "DISABLE" -> expected.setAggregateDisabled(true);
            case "REVOKE" -> expected.setGrantStatus(SupplyGrantValidity.STATUS_REVOKED);
            default -> { /* 应用状态变化只推进当前授权控制修订，不修改批准范围。 */ }
        }
        var after = appApiMapper.selectGrantControlById(before.getId());
        assertPersistedGrant(expected, after);
        if (newAppStatus != null) {
            var currentApp = appMapper.selectGrantControl(before.getAppId());
            if (currentApp == null || !java.util.Objects.equals(newAppStatus, currentApp.getAppStatus())) {
                throw exception(GRANT_CONTROL_CONFLICT);
            }
        }
        etlGrantAudit.append(action, before, after, "管理端明确执行授权控制：" + action, oldAppStatus, newAppStatus);
    }

    /**
     * 确认已持久化授权。
     *
     * @param expected 预期值
     * @param actual 实际值
     */
    private static void assertPersistedGrant(AppApiDO expected, AppApiDO actual) {
        if (actual == null || !java.util.Objects.equals(expected.getId(), actual.getId())
                || !java.util.Objects.equals(expected.getAppId(), actual.getAppId())
                || !java.util.Objects.equals(expected.getApiId(), actual.getApiId())
                || !java.util.Objects.equals(expected.getProtocolVersion(), actual.getProtocolVersion())
                || !java.util.Objects.equals(expected.getSourceType(), actual.getSourceType())
                || !java.util.Objects.equals(expected.getSourceId(), actual.getSourceId())
                || !java.util.Objects.equals(expected.getPermissionUserId(), actual.getPermissionUserId())
                || !java.util.Objects.equals(expected.getApprovedContractHash(), actual.getApprovedContractHash())
                || !java.util.Objects.equals(expected.getPurpose(), actual.getPurpose())
                || !java.util.Objects.equals(expected.getApiVersion(), actual.getApiVersion())
                || !java.util.Objects.equals(expected.getForeverFlag(), actual.getForeverFlag())
                || !java.util.Objects.equals(expected.getDescription(), actual.getDescription())
                || !java.util.Objects.equals(SupplyGrantValidity.effectiveFrom(expected),
                SupplyGrantValidity.effectiveFrom(actual))
                || !java.util.Objects.equals(SupplyGrantValidity.effectiveTo(expected),
                SupplyGrantValidity.effectiveTo(actual))
                || !java.util.Objects.equals(expected.getAggregateRevision(), actual.getAggregateRevision())
                || !java.util.Objects.equals(expected.getGrantRevision(), actual.getGrantRevision())
                || !java.util.Objects.equals(expected.getGrantStatus(), actual.getGrantStatus())
                || !java.util.Objects.equals(expected.getAggregateDisabled(), actual.getAggregateDisabled())) {
            throw exception(GRANT_CONTROL_CONFLICT);
        }
    }

    /**
     * 校验管理端有效性。
     *
     * @param req 请求参数
     */
    private static void validateAdminValidity(AppApiSaveReqVO req) {
        validateAdminValidity(req.getForeverFlag(), req.getStartDate(), req.getEndDate());
    }

    /**
     * 校验管理端有效性。
     *
     * @param foreverFlag 永久标记
     * @param startDate 开始日期
     * @param endDate 结束日期
     */
    private static void validateAdminValidity(Integer foreverFlag, LocalDate startDate, LocalDate endDate) {
        if (!Integer.valueOf(0).equals(foreverFlag) && !Integer.valueOf(1).equals(foreverFlag)) {
            throw exception(GRANT_VALIDITY_INVALID);
        }
        if (Integer.valueOf(0).equals(foreverFlag)
                && (endDate == null || (startDate != null && startDate.isAfter(endDate)))) {
            throw exception(GRANT_VALIDITY_INVALID);
        }
    }

    /**
     * 锁定授权控制。
     *
     * @param id 记录编号
     * @return 应用 API 授权
     */
    private AppApiDO lockGrantControl(Long id) {
        AppApiDO candidate = appApiMapper.selectGrantControlById(id);
        if (candidate == null) {
            throw exception(SUPPLY_APP_API_NOT_EXISTS);
        }
        SupplyAppDO app = appMapper.selectGrantParentForUpdate(candidate.getAppId());
        if (app == null) {
            throw exception(SUPPLY_APP_NOT_EXISTS);
        }
        AppApiDO current = appApiMapper.selectAggregateForUpdate(candidate.getAppId(), candidate.getApiId());
        if (current == null || !java.util.Objects.equals(id, current.getId())) {
            throw exception(SUPPLY_APP_API_NOT_EXISTS);
        }
        return current;
    }

}
