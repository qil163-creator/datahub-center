package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import static cn.iocoder.yudao.framework.dbswitch.product.doris.DorisNativeSchema.*;

/** 普通新表复制的原生授权边界；不开放任意 DDL/DML 或流载入。 */
public final class DorisAuthorizedCreateNew {
    private DorisAuthorizedCreateNew() { }

    /** 原生完整主键生成 UNIQUE+MOW；只解除默认 DUPLICATE 布局产生的唯一性告警。 */
    public static String createSql(String schema, String table,
            List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> columns,
            List<String> primaryKeys, String comment, java.util.Map<String, String> properties,
            cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalTypeMappings mappings) {
        var names = columns.stream().map(cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription::getFieldName).toList();
        names(names);
        if (!names.containsAll(primaryKeys) || new HashSet<>(primaryKeys).size() != primaryKeys.size()
                || !Set.of(cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions.TABLE_PROPERTY,
                "doris.preferUniqueKey", "olap.preferUniqueKey", "olap.replicationNum").containsAll(properties.keySet())) throw invalid();
        var portable = columns.stream().map(column -> {
            var facts = column.getMetadataEvidence();
            if (!facts.provenance().nativeProduct(ProductTypeEnum.POSTGRESQL)
                    || facts.nativeType().state() != cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.VALUE
                    || facts.defaultExpression().state() != cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT
                    || facts.identity().state() != cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT
                    || facts.sequence().state() != cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT
                    || facts.generatedExpression().state() != cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.State.ABSENT) throw invalid();
            var copy = column.copy();
            copy.setRemarks("");
            return copy;
        }).toList();
        var options = new java.util.HashMap<>(properties);
        options.put("olap.preferUniqueKey", Boolean.toString(!primaryKeys.isEmpty()));
        var mapping = new cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions(mappings,
                column -> !primaryKeys.isEmpty() && primaryKeys.contains(column.column())
                        && column.sourceProduct() == ProductTypeEnum.POSTGRESQL && column.targetProduct() == ProductTypeEnum.DORIS
                        && column.lossCodes().equals(Set.of("KEY_UNIQUENESS_NOT_ENFORCED")));
        var generated = cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.getDDLCreateTableSQL(
                new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl(), portable, primaryKeys,
                schema, table, "", false, options, java.util.Map.of(), mapping);
        if (generated.size() != 1) throw invalid();
        var shape = parse(generated.getFirst());
        if (shape.unique() != !primaryKeys.isEmpty() || !primaryKeys.isEmpty() && !shape.keys().equals(primaryKeys)) throw invalid();
        var nativeProperties = new java.util.TreeMap<>(shape.properties());
        if (shape.unique()) nativeProperties.put("enable_unique_key_merge_on_write", "true");
        var comments = new java.util.HashMap<String, String>();
        columns.forEach(column -> comments.put(column.getFieldName(), Objects.requireNonNullElse(column.getRemarks(), "")));
        var complete = new Table(shape.columns().stream().map(column -> new Column(column.name(), column.type(),
                column.nullable(), column.defaultSql(), comments.get(column.name()), column.aggregation())).toList(),
                shape.model(), shape.keys(), shape.distribution(), shape.buckets(), nativeProperties,
                Objects.requireNonNullElse(comment, ""), shape.partitioning(), shape.indexes());
        String ddl = complete.ddl(schema, table);
        return plan(schema, table, names, ddl, insertSql(schema, table, names)).steps().getFirst().sql();
    }

    public static String insertSql(String schema, String table, List<String> fields) {
        return "INSERT INTO " + relation(schema, table) + " (" + names(fields) + ") VALUES ("
                + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
    }

    public static ManagedSchemaScriptPlan plan(String schema, String table, List<String> fields,
            String createSql, String insertSql) {
        String expectedInsert = insertSql(schema, table, fields);
        if (!expectedInsert.equals(insertSql)) throw invalid();
        var parser = new Parser(createSql);
        parser.word("CREATE");
        parser.word("TABLE");
        if (!Objects.equals(parser.identifier(), schema)) throw invalid();
        parser.require(".");
        if (!Objects.equals(parser.identifier(), table)) throw invalid();
        var shape = parse(createSql);
        if (!Set.of("DUPLICATE", "UNIQUE").contains(shape.model())
                || !new HashSet<>(shape.columns().stream().map(Column::name).toList()).equals(new HashSet<>(fields))
                || !shape.indexes().isEmpty() || shape.partitioning() != null
                || !Set.of("replication_num", "replication_allocation", "enable_unique_key_merge_on_write").containsAll(shape.properties().keySet())
                || shape.unique() && !"true".equals(shape.properties().get("enable_unique_key_merge_on_write"))) {
            throw invalid();
        }
        String replicas = shape.properties().get("replication_num");
        String allocation = shape.properties().get("replication_allocation");
        if (allocation != null) {
            if (replicas != null || !allocation.matches("tag[.]location[.]default: [1-9][0-9]{0,4}")) throw invalid();
            replicas = allocation.substring("tag.location.default: ".length());
        }
        if (replicas == null || !replicas.matches("[1-9][0-9]{0,4}") || Integer.parseInt(replicas) > 32767) {
            throw invalid();
        }
        return new ManagedSchemaScriptPlan(ProductTypeEnum.DORIS, schema, List.of(
                new ManagedSchemaScriptPlan.Step(shape.ddl(schema, table), "DDL", Set.of(table), List.of(), null),
                new ManagedSchemaScriptPlan.Step(expectedInsert, "DML", Set.of(table),
                        IntStream.range(0, fields.size()).boxed().toList(), null)));
    }
}
