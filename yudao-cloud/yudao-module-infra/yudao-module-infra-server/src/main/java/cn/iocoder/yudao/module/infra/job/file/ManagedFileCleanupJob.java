package cn.iocoder.yudao.module.infra.job.file;

import cn.iocoder.yudao.module.infra.service.file.FileService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 有界扫描受管文件永久墓碑；重复调度与多实例删除均幂等。 */
@Component
public class ManagedFileCleanupJob {
    @Resource private FileService fileService;

    @XxlJob("managedFileCleanupJob")
    public void execute() {
        FileService.ManagedCleanupResult result = fileService.cleanupManagedFiles(100, 10);
        String summary = "受管文件清理: attempted=" + result.attempted() + ", succeeded=" + result.succeeded() + ", failed=" + result.failed();
        XxlJobHelper.log(summary);
        if (result.failed() > 0) XxlJobHelper.handleFail(summary);
    }
}
