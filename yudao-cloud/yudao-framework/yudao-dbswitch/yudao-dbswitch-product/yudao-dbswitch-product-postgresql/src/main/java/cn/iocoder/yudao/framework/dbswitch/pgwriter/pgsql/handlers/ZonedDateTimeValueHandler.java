package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter.IValueConverter;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter.LocalDateTimeConverter;

import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * ZonedDateTime 处理器,先转为 UTC 再按 PostgreSQL 时间戳微秒值写入 COPY 流,对应 timestamptz 类型。
 */
public class ZonedDateTimeValueHandler extends BaseValueHandler<ZonedDateTime> {

    private IValueConverter<ZonedDateTime, Long> dateTimeConverter;

    /**
     * 使用给定参数初始化 {@code ZonedDateTimeValueHandler}。
     */
    public ZonedDateTimeValueHandler() {
        this(new ToUTCStripTimezone());
    }

    /**
     * 初始化 {@code ZonedDateTimeValueHandler}。
     */
    public ZonedDateTimeValueHandler(IValueConverter<ZonedDateTime, Long> dateTimeConverter) {
        this.dateTimeConverter = dateTimeConverter;
    }

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, ZonedDateTime value) throws IOException {
        buffer.writeInt(8);
        buffer.writeLong(dateTimeConverter.convert(value));
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(ZonedDateTime value) {
        return 8;
    }

    private static final class ToUTCStripTimezone implements IValueConverter<ZonedDateTime, Long> {

        private final IValueConverter<LocalDateTime, Long> converter = new LocalDateTimeConverter();

        /**
         * 按目标类型约定转换当前输入值。
         */
        @Override
        public Long convert(final ZonedDateTime value) {
            return converter.convert(value.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        }
    }
}
