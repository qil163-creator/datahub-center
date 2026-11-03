package cn.iocoder.yudao.module.dataforge.service.security;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.data.config.SourceTableSelection;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/** 平台数据源绑定适配；SQL 语法和物理关系解析委托现有数据库 API 与 DB Switch。 */
@Component
public class DatabaseQuerySupport {

    @Resource
    @org.springframework.context.annotation.Lazy
    private DatabaseApi databaseApi;

    public String normalizeSelect(Long databaseId, String sql) {
        if (databaseId == null || databaseId <= 0 || sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("只读查询缺少数据源或 SQL");
        }
        var request = new DatabaseQueryReqDTO();
        request.setDatabaseId(databaseId);
        request.setSql(sql);
        var parsed = databaseApi.parseRestrictedSelect(request).getCheckedData();
        if (parsed == null || parsed.getNormalizedSql() == null || parsed.getNormalizedSql().isBlank()) {
            throw new IllegalStateException("只读 SQL 解析结果不可用");
        }
        return parsed.getNormalizedSql();
    }

    public void requireExactTables(Long databaseId, ProductTypeEnum product, String schema, List<String> names) {
        if (databaseId == null || databaseId <= 0 || product == null || schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("精确表绑定缺少数据源范围");
        }
        for (String name : SourceTableSelection.requireExactNames(names)) {
            String reference = RestrictedSelectGuard.qualifiedTableName(product, schema, name);
            var table = databaseApi.resolveQueryTable(databaseId, reference).getCheckedData();
            if (table == null || !schema.equals(table.getSchemaName()) || !name.equals(table.getTableName())
                    || !reference.equals(table.getTableReference())
                    || !("SELECT * FROM " + reference).equals(table.getNormalizedSql())) {
                throw new IllegalStateException("精确物理表绑定与数据源范围不一致");
            }
        }
    }
}
