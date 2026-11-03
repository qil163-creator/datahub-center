package cn.iocoder.yudao.framework.dbswitch.data.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 统计信息
 *
 * @author jrl
 */
@Data
@AllArgsConstructor
public class PerfStat {

    private Integer index;

    private Integer total;

    private Integer failure;

    //private Long bytes;
    private Long totalRowCount;

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "Data Source Index: \t" + index + "\n" +
                "Total Tables Count: \t" + total + "\n" +
                "Failure Tables count: \t" + failure + "\n" +
                "Total Row count: \t" + totalRowCount + "\n";
        //"Total Transfer Size: \t" + BytesUnitUtils.bytesSizeToHuman(bytes) + "\n";
    }
}
