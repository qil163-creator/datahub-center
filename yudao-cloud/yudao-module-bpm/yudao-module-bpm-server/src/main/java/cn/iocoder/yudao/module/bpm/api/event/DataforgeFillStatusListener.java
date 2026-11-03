package cn.iocoder.yudao.module.bpm.api.event;

import cn.iocoder.yudao.module.bpm.framework.flowable.core.util.BpmHttpRequestUtils;
import jakarta.annotation.Resource;
import org.springframework.web.client.RestTemplate;

/**
 * 数据研发-在线填报审批结果的监听器实现类
 *
 * 微服务场景下跨进程投递：HTTP 推送状态事件到 dataforge-server 的回调接口，
 * dataforge 侧以 {processInstanceId}:{status} 为去重键做 Inbox 幂等消费。
 *
 * 约定：在线填报的审批流程定义 Key 固定为 {@link #PROCESS_KEY}；
 * 流程建模时审批节点必须使用默认拒绝策略（流程终止），禁止配置"拒绝退回"节点
 *
 * @author Li
 */
public class DataforgeFillStatusListener extends BpmProcessInstanceStatusEventListener {

    public static final String PROCESS_KEY = "dataforge_fill_approval";

    @Resource
    private RestTemplate loadBalancedRestTemplate;

    @Override
    public String getProcessDefinitionKey() {
        return PROCESS_KEY;
    }

    @Override
    public void onEvent(BpmProcessInstanceStatusEvent event) {
        BpmHttpRequestUtils.executeBpmHttpRequest(event,
                "http://dataforge-server/rpc-api/dataforge/fill/bpm-status",
                loadBalancedRestTemplate);
    }

}
