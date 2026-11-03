package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.HiveNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;

/** 集成侧仅编排 Hive 存储形态验证，连接隔离、目录识别和原生字段事实全部复用 DB Switch。 */
@Component
public class IntegrationHiveStorageSupport {
    @Resource private DbSwitchRuntime runtime;

    /** 调用方先执行对象授权；普通外部 TEXTFILE 才能映射到同物理目录的原生引擎非事务读写。 */
    public EtlNativeSqlCompiler.Table requireOrdinaryText(EtlConnectionInfo source, EtlNativeSqlCompiler.Table table) {
        if (source == null || source.getProductType() != ProductTypeEnum.HIVE || table == null || table.product() != ProductTypeEnum.HIVE)
            throw new IllegalArgumentException("Hive 组件必须选择真实 Hive 产品连接");
        if (source.getSchema() == null || !source.getSchema().equals(table.schema()))
            throw new IllegalArgumentException("Hive 物理 Schema 与授权连接不一致");
        try (var connection = runtime.openConnection(ProductTypeEnum.HIVE, source.getDriverProfileId(), source.getJdbcUrl(), source.getUsername(), source.getPassword())) {
            var observed = HiveNativeCatalog.read(connection, table.schema(), table.physicalTable(), 30);
            var types = new LinkedHashMap<String, String>();
            for (var field : observed) {
                var evidence = field.getMetadataEvidence();
                if (evidence == null || evidence.nativeType().state() != MetadataValue.State.VALUE || types.put(field.getFieldName(), evidence.nativeType().value()) != null)
                    throw new IllegalArgumentException("Hive 原生字段事实不完整或重复");
            }
            if (!types.keySet().equals(table.columns().keySet())) throw new IllegalArgumentException("Hive 字段在编译期间发生变化，请重新获取元数据");
            return new EtlNativeSqlCompiler.Table(table.path(), Collections.unmodifiableMap(types), ProductTypeEnum.HIVE,
                    table.schema(), table.physicalTable(), false);
        } catch (java.sql.SQLException invalid) {
            throw new IllegalArgumentException("Hive 当前仅支持已验证版本的普通外部 TEXTFILE 表；事务表、分区表、视图和其他存储格式不能映射执行");
        }
    }
}
