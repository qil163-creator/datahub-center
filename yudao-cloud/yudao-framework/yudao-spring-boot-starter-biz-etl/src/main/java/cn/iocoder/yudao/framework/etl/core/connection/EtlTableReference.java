package cn.iocoder.yudao.framework.etl.core.connection;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

/** 将旧元数据名称和显式 SQL 关系引用映射为相同的物理身份。 */
public record EtlTableReference(String schema, String table, String quoted) {

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    public static EtlTableReference resolve(EtlConnectionInfo connection, String table, String configuredSchema) {
        if (StrUtil.isBlank(table)) {
            throw new IllegalArgumentException("表名不能为空");
        }
        String schema = StrUtil.blankToDefault(configuredSchema, StrUtil.blankToDefault(connection.getSchema(), ""));
        // 现有选择器存储精确的元数据名称。不得按 SQL 标识符规则折叠其大小写。
        // 仅显式的模式/环境绑定使用 SQL 关系引用语义。
        // 点号或引号可能属于从元数据中选取的旧物理表名。
        if (StrUtil.isNotBlank(configuredSchema)) {
            var parsed = RestrictedSelectGuard.parseTableReference(connection.getProductType(), table, schema);
            schema = parsed.getSchemaName();
            table = parsed.getTableName();
        }
        return new EtlTableReference(schema, table, connection.getProductType().quoteSchemaTableName(schema, table));
    }
}
