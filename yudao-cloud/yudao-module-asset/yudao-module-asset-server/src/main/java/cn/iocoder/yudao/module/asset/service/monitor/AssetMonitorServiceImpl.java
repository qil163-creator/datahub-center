package cn.iocoder.yudao.module.asset.service.monitor;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO;
import cn.iocoder.yudao.module.asset.dal.mysql.monitor.AssetUsageLogMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

/**
 * 资产使用行为日志 Service 实现。
 */
@Slf4j
@Service
@Validated
public class AssetMonitorServiceImpl implements AssetMonitorService {

    @Resource
    private AssetUsageLogMapper usageLogMapper;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 记录资产使用行为日志（浏览 / 申请 / 使用），失败不影响主流程
     *
     *  @param asset  资产
     *  @param action 行为类型，见 {@link cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO}
     */
    @Override
    public void recordUsageLog(DataAssetDO asset, int action) {
        // 尽力统计：在调用方事务内以保存点隔离，失败只撤销本条日志，不能中止审批等主事务；无事务时单独提交。
        TransactionTemplate savepoint = new TransactionTemplate(transactionManager);
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        try {
            savepoint.executeWithoutResult(status -> usageLogMapper.insert(AssetUsageLogDO.builder()
                    .assetId(asset.getId())
                    .assetName(asset.getAssetName())
                    .providerName(asset.getSourceSystemName())
                    .action(action)
                    .userId(SecurityFrameworkUtils.getLoginUserId())
                    .build()));
        } catch (RuntimeException ex) {
            log.warn("[recordUsageLog][资产({}) 行为({}) 日志记录失败：{}]", asset.getId(), action, ex.getMessage());
        }
    }

}
