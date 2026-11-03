package cn.iocoder.yudao.module.dataforge.service.execution;

/**
 * DataForge 运行协议的领域门禁；不替代数据库占位约束、租约或对象授权。
 */
public final class RunProtocol {

    /**
     * 限制运行协议工具的实例化，统一通过静态能力使用。
     */
    private RunProtocol() {
    }

    public enum Stage {
        PREPARE, EXECUTION_STARTED, SUBMIT_SENT
    }

    public enum Effect {
        NONE, COMPLETE, PARTIAL, UNKNOWN
    }

    /**
     * 仅资源已退出、质量门禁收敛且输出安全时允许释放占位。
     * 部分/未知输出必须有显式审核；未知的资源退出状态始终不能放行。
     */
    public static boolean canRelease(boolean terminated, Effect effect,
            boolean qualityPending, boolean reviewed) {
        return terminated && effect != null && !qualityPending
                && (effect == Effect.NONE || effect == Effect.COMPLETE || reviewed);
    }

    /** 失败重试采用有上限的指数退避；正常的远端 RUNNING 轮询不计失败。 */
    public static long retryDelaySeconds(int failures, double jitter) {
        if (failures < 0 || !Double.isFinite(jitter) || jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("failures must be nonnegative and jitter in [0, 1)");
        }
        long base = Math.min(300L, 5L << Math.min(failures, 6));
        return Math.min(300L, base + (long) (base * 0.2 * jitter));
    }
}
