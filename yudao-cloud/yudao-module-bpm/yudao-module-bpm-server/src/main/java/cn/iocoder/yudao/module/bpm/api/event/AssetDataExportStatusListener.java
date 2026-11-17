package cn.iocoder.yudao.module.bpm.api.event;

import cn.iocoder.yudao.module.asset.api.dataexport.DataExportStatusApi;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 数据资产-数据查询结果导出审批结果的监听器
 *
 * 微服务场景下跨进程投递：HTTP 推送状态事件到 asset-server 的回调接口；
 * asset 侧不信任回调内容，向 BPM 回查实际状态后按“当前状态 + 修订号”CAS 迁移，重复与乱序只生效一次。
 *
 * 约定：导出审批流程定义 Key 固定为 {@link #PROCESS_KEY}；审批节点使用默认拒绝策略（流程终止）。
 */
@Slf4j
public class AssetDataExportStatusListener {

    public static final String PROCESS_KEY = "asset_data_export";

    @Resource
    private DataExportStatusApi statusApi;

    /** 资产会回查 BPM 终态；事务提交前的推送会读取到旧状态。 */
    @TransactionalEventListener(fallbackExecution = true)
    public void onEvent(BpmProcessInstanceStatusEvent event) {
        if (!PROCESS_KEY.equals(event.getProcessDefinitionKey())) {
            return;
        }
        try {
            statusApi.syncStatus(Long.valueOf(event.getBusinessKey()), event.getId()).checkError();
        } catch (RuntimeException failure) {
            log.warn("资产导出状态通知未送达，业务端下次读取时回查，instanceId={}, failure={}",
                    event.getId(), failure.getClass().getSimpleName());
        }
    }

}
