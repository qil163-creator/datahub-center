package cn.iocoder.yudao.module.governance.service.profile;

/**
 * 续租失败：记录已被其他执行者接管或已结束，当前执行必须放弃且不得写入结果。
 *
 * @author Li
 */
public class ProfileLeaseLostException extends RuntimeException {

    /**
     * 以记录编号构造异常。
     */
    public ProfileLeaseLostException(Long runId) {
        super("Profile run lease lost: " + runId);
    }

}
