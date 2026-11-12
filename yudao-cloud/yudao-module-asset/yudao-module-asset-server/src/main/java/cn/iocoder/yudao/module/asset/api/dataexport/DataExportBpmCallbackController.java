package cn.iocoder.yudao.module.asset.api.dataexport;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.service.dataexport.DataExportService;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据导出 - BPM 状态回调（bpm-server 的出站监听器推送）。
 * <p>
 * 位于 /rpc-api 前缀，网关不对外暴露；回调内容不被信任，服务端会向 BPM 回查实际状态后再迁移。
 */
@RestController
@RequestMapping("/rpc-api/asset/data-export")
@Validated
public class DataExportBpmCallbackController {

    @Resource
    private DataExportService exportService;

    /**
     * 处理 BPM 状态事件。
     */
    @PostMapping("/bpm-status")
    public CommonResult<Boolean> handleBpmStatusEvent(@Valid @RequestBody BpmProcessInstanceStatusEvent event) {
        exportService.handleBpmStatus(event);
        return success(true);
    }
}
