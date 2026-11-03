package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.service.IMetaDataByJdbcService;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.PHYSICAL_MODEL_DATABASE_NOT_EXISTS;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.PHYSICAL_MODEL_TABLE_META_FAILED;

/**
 * 数据建模 → 数据源连接信息解析器
 *
 * 数据源实例由 dataforge 模块统一管理，通过 RPC 现取现用（密码不在本模块落库）
 *
 * @author Li
 */
@Component
public class ModelingDatabaseResolver {

    @Resource
    private DbSwitchRuntime dbSwitchRuntime;

    @Resource
    private DatabaseApi databaseApi;

    /**
     * 解析后的连接信息
     */
    @Data
    public static class ConnectionInfo {

        private Long databaseId;

        private String name;

        private ProductTypeEnum productType;

        private String jdbcUrl;

        private String username;

        private String password;

        private String schema;

        private String configurationRevision;

    }

    /**
     * 按数据源编号解析连接信息（密码已解密）
     */
    public ConnectionInfo resolve(Long databaseId) {
        DatabaseConnectionRespDTO dto = databaseApi.getDatabaseConnection(databaseId).getCheckedData();
        if (dto == null) {
            throw exception(PHYSICAL_MODEL_DATABASE_NOT_EXISTS);
        }
        ConnectionInfo info = new ConnectionInfo();
        info.setDatabaseId(dto.getId());
        info.setConfigurationRevision(dto.getConfigurationRevision());
        info.setName(dto.getName());
        info.setProductType(dbSwitchRuntime.products().resolve(dto.getProductType())
                .orElseThrow(() -> exception(PHYSICAL_MODEL_TABLE_META_FAILED, "数据库产品未注册")));
        info.setJdbcUrl(dto.getJdbcUrl());
        info.setUsername(dto.getUsername());
        info.setPassword(dto.getPassword());
        info.setSchema(StrUtil.blankToDefault(dto.getSchema(), ""));
        return info;
    }

    /** 环境复用同一冻结物理字段类型，仅允许同一实际产品，产品别名由 DB Switch 解析。 */
    public boolean matchesProduct(String declaredProduct, ConnectionInfo connection) {
        return dbSwitchRuntime.products().resolve(declaredProduct)
                .map(product -> product == connection.getProductType()).orElse(false);
    }

    /**
     * 数据源精简列表（下拉用）
     */
    public List<DatabaseConnectionRespDTO> getDatabaseList() {
        List<DatabaseConnectionRespDTO> list = databaseApi.getDatabaseList(null).getCheckedData();
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 数据源下的表名列表（逆向建模选表用）
     */
    public List<String> getTableNameList(Long databaseId) {
        List<String> list = databaseApi.getTableNameList(databaseId).getCheckedData();
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 逆向建模：从数据源表结构导入字段定义
     */
    public List<ModelFieldItem> importFields(Long databaseId, String tableName) {
        ConnectionInfo conn = resolve(databaseId);
        try {
            IMetaDataByJdbcService metaDataService = dbSwitchRuntime.jdbcMetadata(conn.getProductType());
            var metadata = metaDataService.queryTableSchema(
                    conn.getJdbcUrl(), conn.getUsername(), conn.getPassword(),
                    conn.getSchema(), tableName, 30_000);
            List<ColumnDescription> columns = metadata.columns();
            Set<String> primaryKeys = new HashSet<>(metadata.primaryKeys());
            List<ModelFieldItem> fields = new ArrayList<>();
            int sort = 1;
            for (ColumnDescription column : columns) {
                ModelFieldItem item = new ModelFieldItem();
                item.setCnName(StrUtil.blankToDefault(column.getRemarks(), column.getFieldName()));
                item.setFieldName(column.getFieldName());
                item.setDataType(SqlTypeSemantics.metadataTypeName(column));
                String type = SqlTypeSemantics.canonicalName(item.getDataType());
                if ("decimal".equals(type)) {
                    item.setDataPrecision(column.getPrecisionSize() > 0 ? column.getPrecisionSize() : null);
                    item.setDataScale(column.getScaleSize());
                } else if (Set.of("varchar", "char", "nvarchar", "nchar").contains(type)) {
                    item.setDataLength(column.getPrecisionSize() > 0 ? column.getPrecisionSize() : null);
                }
                item.setDefaultValue(column.getDefaultValue());
                item.setSort(sort++);
                item.setComment(StrUtil.nullToEmpty(column.getRemarks()));
                item.setPkFlag(primaryKeys.contains(column.getFieldName()));
                item.setNullableFlag(column.isNullable());
                fields.add(item);
            }
            return fields;
        } catch (Exception ex) {
            throw exception(PHYSICAL_MODEL_TABLE_META_FAILED, "请检查连接、元数据权限及驱动超时能力；本次读取已终止");
        }
    }

}
