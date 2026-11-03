package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter.IValueConverter;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.converter.LocalTimeConverter;

import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalTime;

/**
 * LocalTime 处理器,经转换器转为当日起的微秒数后按 8 字节写入 COPY 流。
 */
public class LocalTimeValueHandler extends BaseValueHandler<LocalTime> {

    private IValueConverter<LocalTime, Long> timeConverter;

    /**
     * 使用给定参数初始化 {@code LocalTimeValueHandler}。
     */
    public LocalTimeValueHandler() {
        this(new LocalTimeConverter());
    }

    /**
     * 初始化 {@code LocalTimeValueHandler}。
     */
    public LocalTimeValueHandler(IValueConverter<LocalTime, Long> timeConverter) {
        this.timeConverter = timeConverter;
    }

    /**
     * 将当前类型的值编码并写入二进制输出流。
     */
    @Override
    protected void internalHandle(DataOutputStream buffer, final LocalTime value) throws IOException {
        buffer.writeInt(8);
        buffer.writeLong(timeConverter.convert(value));
    }

    /**
     * 计算当前值按目标编码写入时所占的字节数。
     */
    @Override
    public int getLength(LocalTime value) {
        return 8;
    }
}
