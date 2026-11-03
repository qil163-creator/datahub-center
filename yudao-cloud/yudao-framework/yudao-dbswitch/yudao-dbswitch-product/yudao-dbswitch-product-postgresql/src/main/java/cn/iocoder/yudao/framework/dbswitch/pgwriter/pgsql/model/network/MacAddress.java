package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.model.network;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * MAC 地址模型,持有 6 字节地址内容,用于 COPY 写入 macaddr 列类型。
 */
public class MacAddress {

    private final byte[] addressBytes;

    /**
     * 校验构造参数并初始化 {@code MacAddress}。
     */
    public MacAddress(byte[] addressBytes) {

        if (addressBytes == null) {
            throw new IllegalArgumentException("addressBytes");
        }

        if (addressBytes.length != 6) {
            throw new IllegalArgumentException("addressBytes");
        }

        this.addressBytes = addressBytes;
    }

    /**
     * 返回地址字节数。
     */
    public byte[] getAddressBytes() {
        return addressBytes;
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {

        List<String> bytesAsHexString = IntStream
                .range(0, addressBytes.length)
                .map(idx -> addressBytes[idx])
                .mapToObj(value -> String.format("0x%x", value))
                .collect(Collectors.toList());

        return String.join("-", bytesAsHexString);
    }
}
