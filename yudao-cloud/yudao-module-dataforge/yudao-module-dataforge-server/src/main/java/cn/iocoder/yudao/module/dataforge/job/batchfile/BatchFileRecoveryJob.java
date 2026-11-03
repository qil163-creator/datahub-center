package cn.iocoder.yudao.module.dataforge.job.batchfile;

import cn.iocoder.yudao.module.dataforge.service.batchfile.BatchFileRunService;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 单平台扫描，抢占依靠数据库 CAS；失效运行只转未知，排队运行可重新派发。 */
@Component
public class BatchFileRecoveryJob {

    @Resource
    private BatchFileRunService service;

    /**
     * 执行批次文件恢复任务处理。
     */
    @XxlJob("batchFileRecoveryJob")
    public void execute() {
        service.reconcile();
    }
}

