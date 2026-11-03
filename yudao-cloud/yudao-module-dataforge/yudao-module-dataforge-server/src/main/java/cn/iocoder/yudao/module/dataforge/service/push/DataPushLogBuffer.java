package cn.iocoder.yudao.module.dataforge.service.push;

/**
 * 实时日志有界缓冲：超出上限时保留尾部，避免执行记录撑爆库
 *
 * @author Li
 */
public final class DataPushLogBuffer {

    public static final String TRUNCATE_MARK = "...(truncated)\n";

    /**
     * 限制数据推送日志缓冲区工具的实例化，统一通过静态能力使用。
     */
    private DataPushLogBuffer() {
    }

    /**
     * 追加数据推送日志缓冲区内容。
     *
     * @param log 日志
     * @param line 行
     * @param maxChars 最大值字符
     */
    public static void append(StringBuilder log, String line, int maxChars) {
        if (log == null || line == null) {
            return;
        }
        log.append(line).append('\n');
        trim(log, maxChars);
    }

    /**
     * 裁剪数据推送日志缓冲区。
     *
     * @param log 日志
     * @param maxChars 最大值字符
     */
    public static void trim(StringBuilder log, int maxChars) {
        if (log == null || maxChars <= 0 || log.length() <= maxChars) {
            return;
        }
        int keep = maxChars - TRUNCATE_MARK.length();
        if (keep < 1) {
            log.setLength(0);
            log.append(TRUNCATE_MARK);
            return;
        }
        String tail = log.substring(log.length() - keep);
        int newline = tail.indexOf('\n');
        if (newline >= 0 && newline < tail.length() - 1) {
            tail = tail.substring(newline + 1);
        }
        log.setLength(0);
        log.append(TRUNCATE_MARK).append(tail);
    }

    /**
     * 读取数据推送日志缓冲区快照。
     *
     * @param log 日志
     * @param maxChars 最大值字符
     * @return 数据推送日志缓冲区快照文本
     */
    public static String snapshot(StringBuilder log, int maxChars) {
        if (log == null) {
            return "";
        }
        trim(log, maxChars);
        return log.toString();
    }

}
