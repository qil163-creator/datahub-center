package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import java.util.List;
import java.util.Optional;

/**
 * 可选的表结构提供者，用于展开 {@code SELECT *} 以及为未写列清单的 INSERT 按位置映射字段。
 * <p>
 * 实现方负责数据来源与授权；分析器只读取有序列名，不缓存跨调用结果。实现抛出的异常会被视为
 * 表结构不可用，结果降级为部分解析，不会中断分析。
 */
@FunctionalInterface
public interface LineageCatalog {

    /**
     * 返回表的有序列名；未知时返回空。
     */
    Optional<List<String>> columns(LineageTable table);

    /**
     * 不提供任何表结构的实现。
     */
    static LineageCatalog none() {
        return table -> Optional.empty();
    }
}
