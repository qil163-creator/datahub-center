package cn.iocoder.yudao.framework.etl.core.runtime;

import lombok.Getter;

import java.util.Map;

/**
 * 数据通道传输包：单行数据 / 正常结束 / 上游异常
 *
 * @author Li
 */
@Getter
public class DataPacket {

    public enum Type {DATA, END, ERROR}

    private final Type type;

    private final Map<String, Object> row;

    private final String errorMessage;

    private final EtlRowOrigin origin;

    /**
     * 使用给定参数初始化 {@code DataPacket}。
     */
    private DataPacket(Type type, Map<String, Object> row, String errorMessage) {
        this(type, row, errorMessage, null);
    }

    /**
     * 初始化 {@code DataPacket}。
     */
    private DataPacket(Type type, Map<String, Object> row, String errorMessage, EtlRowOrigin origin) {
        this.type = type;
        this.row = row;
        this.errorMessage = errorMessage;
        this.origin = origin;
    }

    /**
     * 返回当前操作承载的数据。
     */
    public static DataPacket data(Map<String, Object> row) {
        return new DataPacket(Type.DATA, row, null);
    }

    /**
     * 返回当前操作承载的数据。
     */
    public static DataPacket data(Map<String, Object> row, EtlRowOrigin origin) {
        return new DataPacket(Type.DATA, row, null, origin);
    }

    /**
     * 返回当前终点。
     */
    public static DataPacket end() {
        return new DataPacket(Type.END, null, null);
    }

    /**
     * 返回错误。
     */
    public static DataPacket error(String message) {
        return new DataPacket(Type.ERROR, null, message);
    }

}
