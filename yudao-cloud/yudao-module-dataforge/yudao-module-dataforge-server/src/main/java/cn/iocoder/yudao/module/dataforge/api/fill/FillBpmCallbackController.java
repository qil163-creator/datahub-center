package cn.iocoder.yudao.module.dataforge.api.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.dataforge.service.fill.FillBpmEventService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 在线填报-BPM 状态回调接口（bpm-server 出站监听器 HTTP 推送到这里）
 *
 * 契约：快速接收（校验 + Inbox 落库 + 返回），重逻辑异步；
 * 路径位于 /rpc-api 前缀，由模块安全配置放行内部调用（网关不对外暴露 rpc-api）
 *
 * @author Li
 */
@RestController
@RequestMapping("/rpc-api/dataforge/fill")
@Validated
public class FillBpmCallbackController {

    @Resource
    private FillBpmEventService bpmEventService;

    /**
     * 处理BPM状态事件。
     *
     * @param event 事件
     * @return 填报BPM回调
     */
    @PostMapping("/bpm-status")
    public CommonResult<Boolean> handleBpmStatusEvent(@Valid @RequestBody BpmProcessInstanceStatusEvent event) {
        bpmEventService.handleStatusEvent(event);
        return success(true);
    }

}
