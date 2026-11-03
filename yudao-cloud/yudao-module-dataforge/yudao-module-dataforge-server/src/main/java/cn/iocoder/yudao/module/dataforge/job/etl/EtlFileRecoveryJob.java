package cn.iocoder.yudao.module.dataforge.job.etl;

import cn.iocoder.yudao.module.dataforge.service.etl.EtlFileRecoveryService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 全局标识扫描有数量上限；服务读取或修改台账前校验各自的运行记录。 */
@Component
public class EtlFileRecoveryJob {

    @Resource
    private EtlFileRecoveryService service;

    /**
     * 执行ETL 文件恢复任务处理。
     */
    @XxlJob("etlFileRecoveryJob")
    public void execute() {
        try {
            service.recoverPending();
            XxlJobHelper.handleSuccess("ETL 文件意图对账扫描完成，重试和人工处置状态以持久账本为准");
        } catch (RuntimeException unavailable) {
            XxlJobHelper.handleFail("ETL 文件意图对账扫描未完成，已保留持久重试状态");
        }
    }
}
