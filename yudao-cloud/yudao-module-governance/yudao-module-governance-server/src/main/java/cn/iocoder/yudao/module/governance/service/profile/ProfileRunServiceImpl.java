package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileRequest;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileSqlGenerator;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileLatestRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.profile.vo.ProfileRunRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileRunMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileValueMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_DATASOURCE_MISSING;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_PRODUCT_UNSUPPORTED;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_RUN_ACTIVE;

/**
 * 数据探查 Service 实现。发起只写一条 PENDING 记录（不在请求事务中访问目标库），
 * 提交后尝试立即异步执行，定时任务 profileRunJob 兜底领取与失联恢复。
 *
 * @author Li
 */
@Slf4j
@Service
public class ProfileRunServiceImpl implements ProfileRunService {

    @Resource
    private ProfileRunMapper runMapper;

    @Resource
    private ProfileColumnMapper columnMapper;

    @Resource
    private ProfileValueMapper valueMapper;

    @Resource
    private ProfileTargetLoader targetLoader;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private ProfileRunExecutor executor;

    @Resource
    private AdminUserApi adminUserApi;

    /**
     * 发起探查；单表并发由部分唯一索引最终保证，预检只为给出友好提示。
     */
    @Override
    public Long createRun(Long metaTableId, Long userId) {
        ProfileTargetLoader.ProfileTarget target = targetLoader.load(metaTableId);
        if (runMapper.selectActive(metaTableId) != null) {
            throw exception(PROFILE_RUN_ACTIVE);
        }
        ProductTypeEnum product = databaseResolver.resolve(target.table().getDatasourceId(),
                PROFILE_DATASOURCE_MISSING).getProductType();
        if (!ProfileSqlGenerator.supports(product)) {
            throw exception(PROFILE_PRODUCT_UNSUPPORTED, String.valueOf(product));
        }
        ProfileRunDO run = new ProfileRunDO();
        run.setMetaTableId(metaTableId);
        run.setDatasourceId(target.table().getDatasourceId());
        run.setSchemaName(target.schemaName(product));
        run.setTableName(target.table().getTableName());
        run.setStatus(ProfileRunDO.STATUS_PENDING);
        run.setSampleLimit(ProfileRequest.DEFAULT_SAMPLE_ROWS);
        run.setColumnCount(target.columns().size());
        run.setExecutorUserId(userId);
        run.setAttemptCount(0);
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException duplicate) {
            // 并发发起命中 uk_dh_governance_profile_run_active；本方法不在事务中，可直接转为业务错误
            throw exception(PROFILE_RUN_ACTIVE);
        }
        executor.dispatch(run.getId());
        return run.getId();
    }

    /**
     * 最新结果与规则建议。
     */
    @Override
    public ProfileLatestRespVO getLatest(Long metaTableId) {
        targetLoader.requireTable(metaTableId);
        ProfileLatestRespVO result = new ProfileLatestRespVO();
        ProfileRunDO latest = runMapper.selectLatest(metaTableId);
        ProfileRunDO success = latest == null ? null : Objects.equals(latest.getStatus(), ProfileRunDO.STATUS_SUCCESS)
                ? latest : runMapper.selectLatestSuccess(metaTableId);
        List<ProfileRunRespVO> runs = fillExecutors(toList(latest, success));
        result.setLatestRun(latest == null ? null : runs.get(0));
        result.setResultRun(success == null ? null : runs.get(runs.size() - 1));
        if (success == null) {
            result.setColumns(List.of());
            result.setSuggestions(List.of());
            return result;
        }
        List<ProfileColumnDO> columns = columnMapper.selectListByRunId(success.getId());
        Map<Long, List<ProfileValueDO>> values = valueMapper.selectListByRunId(success.getId()).stream()
                .collect(Collectors.groupingBy(ProfileValueDO::getProfileColumnId));
        result.setColumns(ProfileConvert.columns(columns, values));
        result.setSuggestions(ProfileRuleRecommender.recommend(
                success.getSampleRows() == null ? 0L : success.getSampleRows(), columns, values));
        return result;
    }

    /**
     * 探查记录分页。
     */
    @Override
    public PageResult<ProfileRunRespVO> getRunPage(ProfileRunPageReqVO reqVO) {
        targetLoader.requireTable(reqVO.getMetaTableId());
        PageResult<ProfileRunDO> page = runMapper.selectPage(reqVO);
        return new PageResult<>(fillExecutors(page.getList()), page.getTotal());
    }

    /**
     * 定时任务入口，委托执行器。
     */
    @Override
    public int processDueRuns() {
        return executor.processDue();
    }

    /**
     * latest 与 success 去重成列表（同一条时只出现一次）。
     */
    private static List<ProfileRunDO> toList(ProfileRunDO latest, ProfileRunDO success) {
        List<ProfileRunDO> list = new ArrayList<>(2);
        if (latest != null) {
            list.add(latest);
        }
        if (success != null && (latest == null || !Objects.equals(latest.getId(), success.getId()))) {
            list.add(success);
        }
        return list;
    }

    /**
     * 转换并填充执行人昵称；用户服务不可用时只返回编号。
     */
    private List<ProfileRunRespVO> fillExecutors(List<ProfileRunDO> runs) {
        List<ProfileRunRespVO> result = runs.stream().map(ProfileConvert::run).toList();
        Set<Long> userIds = new HashSet<>();
        runs.forEach(run -> {
            if (run.getExecutorUserId() != null) {
                userIds.add(run.getExecutorUserId());
            }
        });
        if (userIds.isEmpty()) {
            return result;
        }
        try {
            Map<Long, AdminUserRespDTO> users = adminUserApi.getUserMap(userIds);
            result.forEach(run -> {
                AdminUserRespDTO user = users.get(run.getExecutorUserId());
                run.setExecutorName(user == null ? null : user.getNickname());
            });
        } catch (RuntimeException ex) {
            log.warn("[fillExecutors][执行人昵称读取失败] {}", ex.getClass().getSimpleName());
        }
        return result;
    }

}
