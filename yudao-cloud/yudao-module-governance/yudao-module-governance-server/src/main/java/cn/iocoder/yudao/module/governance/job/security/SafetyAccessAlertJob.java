package cn.iocoder.yudao.module.governance.job.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.governance.service.security.SafetyAccessAlertService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 异常访问识别任务：建议每 10 分钟调度一次。单平台执行，告警按唯一键去重，可重跑、可多实例并发。
 * <p>
 * 任务参数为空时识别最近一个完整窗口；填 {@code 2026-11-12T09:00/2026-11-12T10:00} 可补跑指定窗口。
 */
@Component
public class SafetyAccessAlertJob {

    @Resource
    private SafetyAccessAlertService alertService;

    /**
     * 执行识别。
     */
    @XxlJob("safetyAccessAlertJob")
    public void execute() {
        String param = XxlJobHelper.getJobParam();
        int created;
        if (StrUtil.isNotBlank(param) && param.contains("/")) {
            String[] window = param.trim().split("/", 2);
            created = alertService.detectWindow(LocalDateTime.parse(window[0].trim()),
                    LocalDateTime.parse(window[1].trim()));
        } else {
            created = alertService.detect(LocalDateTime.now());
        }
        XxlJobHelper.log("异常访问识别完成，新增告警 {} 条", created);
    }
}
