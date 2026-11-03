package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

/**
 * 附加到产品描述符上的可选产品组件。两个方向都是基于观测事实的纯函数，且线程安全：
 * 不使用连接、不查找配置，也不修改输入。
 * <ul>
 * <li>{@link #toLogical}：将带此产品来源信息的原生事实转换为逻辑值域。产品
 * 读取器或 JDBC 编解码器可能改变的事实报告为 LOSS；未知类型和 UNKNOWN 事实报告为 BLOCKER，绝不
 * 进行猜测或退化为字符串。</li>
 * <li>{@link #toNative}：将逻辑值域转换为此产品的原生列类型，报告产品
 * 及其已验证驱动路径无法原样存储的值或语义。</li>
 * </ul>
 * 规划器会为每个产品检查默认值、标识生成及序列、生成表达式和空值约束的一致性；
 * 实现类只会接收到已通过这些检查的列。
 */
public interface LogicalTypeMapper {

    /**
     * 返回当前操作使用的数据库产品。
     */
    ProductTypeEnum product();

    /**
     * 将数据库原生列类型映射为逻辑类型。
     */
    TypeMappingResult<LogicalType> toLogical(ColumnDescription column);

    /**
     * 将逻辑列类型映射为当前产品的原生类型。
     */
    TypeMappingResult<NativeColumnType> toNative(LogicalColumn column);
}
