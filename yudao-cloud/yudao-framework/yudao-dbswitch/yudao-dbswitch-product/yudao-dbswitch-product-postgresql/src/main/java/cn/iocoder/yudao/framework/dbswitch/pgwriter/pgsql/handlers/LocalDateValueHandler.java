package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter.IValueConverter;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter.LocalDateConverter;

import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDate;

/**
 * LocalDate 处理器,经转换器转为 PostgreSQL 纪元起的天数后按 4 字节写入 COPY 流。
 */
public class LocalDateValueHandler extends BaseValueHandler<LocalDate> {

    private IValueConverter<LocalDate, Integer> dateConverter;

    /**
     * 使用给定参数初始化 {@code LocalDateValueHandler}。
     */
    public LocalDateValueHandler() {
        this(new LocalDateConverter());
    }

    /**
     * 初始化 {@code LocalDateValueHandler}。
     */
    public LocalDateValueHandler(IValueConverter<LocalDate, Integer> dateTimeConverter) {
        this.dateConverter = dateTimeConverter;
    }

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final LocalDate value) throws IOException {
        buffer.writeInt(4);
        buffer.writeInt(dateConverter.convert(value));
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(LocalDate value) {
        return 4;
    }
}
