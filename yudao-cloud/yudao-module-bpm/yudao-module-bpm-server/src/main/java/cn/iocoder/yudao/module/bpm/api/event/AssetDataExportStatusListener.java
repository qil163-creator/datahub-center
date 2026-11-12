package cn.iocoder.yudao.module.bpm.api.event;

import cn.iocoder.yudao.module.bpm.framework.flowable.core.util.BpmHttpRequestUtils;
import jakarta.annotation.Resource;
import org.springframework.web.client.RestTemplate;

/**
 * 数据资产-数据查询结果导出审批结果的监听器
 *
 * 微服务场景下跨进程投递：HTTP 推送状态事件到 asset-server 的回调接口；
 * asset 侧不信任回调内容，向 BPM 回查实际状态后按“当前状态 + 修订号”CAS 迁移，重复与乱序只生效一次。
 *
 * 约定：导出审批流程定义 Key 固定为 {@link #PROCESS_KEY}；审批节点使用默认拒绝策略（流程终止）。
 */
public class AssetDataExportStatusListener extends BpmProcessInstanceStatusEventListener {

    public static final String PROCESS_KEY = "asset_data_export";

    @Resource
    private RestTemplate loadBalancedRestTemplate;

    @Override
    public String getProcessDefinitionKey() {
        return PROCESS_KEY;
    }

    @Override
    public void onEvent(BpmProcessInstanceStatusEvent event) {
        BpmHttpRequestUtils.executeBpmHttpRequest(event,
                "http://asset-server/rpc-api/asset/data-export/bpm-status",
                loadBalancedRestTemplate);
    }

}
