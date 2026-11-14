package cn.iocoder.yudao.module.dataforge.job.fill;

import cn.iocoder.yudao.module.dataforge.service.fill.FillAttachmentService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 在线填报-附件清理 Job
 *
 * 建议在 XXL-Job 控制台按每小时执行配置。长时间未完成的上传意图按操作键对账删除；
 * 未绑定提交、超过保留期且草稿不再引用的附件删除受管文件。可重入：每条都以状态 CAS 收尾。
 *
 * @author Li
 */
@Component
public class FillAttachmentCleanupJob {

    private static final int BATCH_LIMIT = 200;

    @Resource
    private FillAttachmentService attachmentService;

    /**
     * 执行附件清理。
     */
    @XxlJob("fillAttachmentCleanupJob")
    public void execute() {
        int cleaned = attachmentService.cleanup(BATCH_LIMIT);
        XxlJobHelper.handleSuccess("清理附件 " + cleaned + " 个");
    }

}
