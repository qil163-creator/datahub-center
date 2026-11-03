package cn.iocoder.yudao.framework.dbswitch.data.util;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * 迁移数据格式化工具类,将日期、时间等字段值统一格式化为字符串,用于迁移过程中的数据转换与比对。
 *
 * @ClassName DataTransUtils
 * @Author zrx
 * @Date 2024/9/14 15:59
 */
public class DataTransUtils {

    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP_FORMAT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return simpleDateFormat;
    });

    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("HH:mm:ss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return simpleDateFormat;
    });

    private static final DateTimeFormatter TIMESTAMP_FORMAT_NEW = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 返回当前数据的文本表示。
     */
    public static String getDataStr(Object o) {
        if (o == null) {
            return null;
        }
        String s;
        if (o instanceof java.sql.Time) {
            s = TIME_FORMAT.get().format(o);
        } else if (o instanceof java.sql.Timestamp) {
            s = TIMESTAMP_FORMAT.get().format(o);
        } else if (o instanceof java.sql.Date) {
            s = TIMESTAMP_FORMAT.get().format(o);
        } else if (o instanceof LocalDateTime) {
            LocalDateTime t = (LocalDateTime) o;
            s = TIMESTAMP_FORMAT_NEW.format(t);
        } else if (o instanceof LocalDate) {
            LocalDate t = (LocalDate) o;
            s = TIMESTAMP_FORMAT_NEW.format(t);
        } else {
            s = String.valueOf(o);
        }
        return s;
    }
}
