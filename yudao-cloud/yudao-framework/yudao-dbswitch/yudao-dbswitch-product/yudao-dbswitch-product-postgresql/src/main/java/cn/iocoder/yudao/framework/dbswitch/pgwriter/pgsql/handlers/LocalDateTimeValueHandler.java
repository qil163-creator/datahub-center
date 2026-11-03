package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter.IValueConverter;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter.LocalDateTimeConverter;

import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * LocalDateTime 处理器,经转换器转为 PostgreSQL 时间戳微秒值后按 8 字节写入 COPY 流。
 */
public class LocalDateTimeValueHandler extends BaseValueHandler<LocalDateTime> {

    private IValueConverter<LocalDateTime, Long> dateTimeConverter;

    /**
     * 使用给定参数初始化 {@code LocalDateTimeValueHandler}。
     */
    public LocalDateTimeValueHandler() {
        this(new LocalDateTimeConverter());
    }

    /**
     * 初始化 {@code LocalDateTimeValueHandler}。
     */
    public LocalDateTimeValueHandler(IValueConverter<LocalDateTime, Long> dateTimeConverter) {
        this.dateTimeConverter = dateTimeConverter;
    }

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final LocalDateTime value)
            throws IOException {
        buffer.writeInt(8);
        buffer.writeLong(dateTimeConverter.convert(value));
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(LocalDateTime value) {
        return 8;
    }
}
