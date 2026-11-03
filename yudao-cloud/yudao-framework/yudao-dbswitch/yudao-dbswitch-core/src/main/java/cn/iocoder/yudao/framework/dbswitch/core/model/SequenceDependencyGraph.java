package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.util.*;

/** 单个源资源内声明的目录依赖。序列位置属于非 MVCC 采样。 */
public record SequenceDependencyGraph(ReadSnapshot.Evidence catalogSnapshot,
                                      Set<TableReadPlan.Relation> selectedRelations,
                                      List<Sequence> sequences) {

    public static final int MAX_RELATIONS = 1000, MAX_SEQUENCES = 2000, MAX_REFERENCES = 10000;

    /** OID 仅在此源资源内有效，绝不是集群或恢复代际，也不是持久标识。 */
    public record Identity(long databaseOid, long sequenceOid, String schema, String name) {

        /**
         * 校验构造参数并初始化 {@code Identity}。
         */
        public Identity {
            if (databaseOid < 1 || sequenceOid < 1 || schema == null || schema.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Invalid sequence identity");
            }
        }
    }

    public record Column(TableReadPlan.Relation relation, long relationOid, int attributeNumber, String name) {

        /**
         * 校验构造参数并初始化 {@code Column}。
         */
        public Column {
            Objects.requireNonNull(relation);
            if (relationOid < 1 || attributeNumber < 1 || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Invalid sequence column dependency");
            }
        }
    }

    public enum ReferenceKind {IDENTITY_ALWAYS, IDENTITY_BY_DEFAULT, DEFAULT_EXPRESSION}

    public enum ExpressionEvidence {NATIVE_IDENTITY, CATALOG_EXPRESSION_UNPLANNED}

    public record Reference(Column column, ReferenceKind kind, MetadataValue<String> expression) {

        /**
         * 校验构造参数并初始化 {@code Reference}。
         */
        public Reference {
            Objects.requireNonNull(column);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(expression);
            if (kind == ReferenceKind.DEFAULT_EXPRESSION ? expression.state() != MetadataValue.State.VALUE
                    : expression.state() != MetadataValue.State.ABSENT) {
                throw new IllegalArgumentException("Invalid sequence reference expression");
            }
        }

        /** 捕获的目录表达式是数据，绝不是可执行 SQL 或已验证的重写计划。 */
        public ExpressionEvidence expressionEvidence() {
            return kind == ReferenceKind.DEFAULT_EXPRESSION ? ExpressionEvidence.CATALOG_EXPRESSION_UNPLANNED
                    : ExpressionEvidence.NATIVE_IDENTITY;
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "SequenceReference[kind=" + kind + "]";
        }
    }

    /** 序列固有属性：所有权以及标识生成或默认值语义由引用定义。 */
    public record Definition(String dataType, long start, long increment, long minimum, long maximum,
                             long cache, boolean cycle, MetadataValue<ColumnSequenceMetadata.Position> position) {

        /**
         * 初始化 {@code Definition}。
         */
        public Definition {
            // 复用现有原生序列校验；不另建 DDL 构造器或位置计算器。
            new ColumnSequenceMetadata(ColumnSequenceMetadata.Kind.OWNED_DEFAULT, "validation", "validation",
                    dataType, start, increment, minimum, maximum, cache, cycle, position);
        }

        /**
         * 根据当前输入构建并返回 {@code ColumnSequenceMetadata}。
         */
        public ColumnSequenceMetadata columnMetadata(Identity identity, ColumnSequenceMetadata.Kind kind) {
            return new ColumnSequenceMetadata(kind, identity.schema(), identity.name(), dataType, start, increment,
                    minimum, maximum, cache, cycle, position);
        }

        /**
         * 返回当前对象的文本表示。
         */
        @Override
        public String toString() {
            return "SequenceDefinition[type=" + dataType + ", position=" + position.state() + "]";
        }
    }

    public record Sequence(Identity identity, Definition definition, MetadataValue<Column> owner,
                           List<Reference> references) {

        /**
         * 校验构造参数并初始化 {@code Sequence}。
         */
        public Sequence {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(definition);
            Objects.requireNonNull(owner);
            if (!owner.known()) {
                throw new IllegalArgumentException("Sequence ownership must be observed");
            }
            references = List.copyOf(references);
            if (references.size() > MAX_REFERENCES || references.stream().map(Reference::column).distinct()
                    .count() != references.size()) {
                throw new IllegalArgumentException("Unbounded or duplicate sequence references");
            }
        }

        /**
         * 返回或登记可在当前作用域共享使用的资源。
         */
        public boolean shared() {
            return references.size() > 1;
        }
    }

    /**
     * 校验构造参数并初始化 {@code SequenceDependencyGraph}。
     */
    public SequenceDependencyGraph {
        Objects.requireNonNull(catalogSnapshot);
        selectedRelations = Set.copyOf(selectedRelations);
        sequences = List.copyOf(sequences);
        if (selectedRelations.isEmpty() || selectedRelations.size() > MAX_RELATIONS || sequences.size() > MAX_SEQUENCES
                || sequences.stream().map(Sequence::identity).distinct().count() != sequences.size()
                || sequences.stream().mapToLong(sequence -> sequence.references().size()).sum() > MAX_REFERENCES) {
            throw new IllegalArgumentException("Invalid migration dependency graph size");
        }
    }

    /**
     * 返回当前选择范围中缺失的依赖关系。
     */
    public Set<TableReadPlan.Relation> missingRelations() {
        var missing = new LinkedHashSet<TableReadPlan.Relation>();
        for (var sequence : sequences) {
            if (sequence.owner().value() != null) {
                missing.add(sequence.owner().value().relation());
            }
            sequence.references().forEach(reference -> missing.add(reference.column().relation()));
        }
        missing.removeAll(selectedRelations);
        return Set.copyOf(missing);
    }

    /**
     * 检查所选关系已包含全部已声明的序列依赖。
     */
    public void requireClosedSelection() {
        if (!missingRelations().isEmpty()) {
            throw new IllegalArgumentException("Migration selection omits declared sequence dependencies");
        }
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "SequenceDependencyGraph[relations=" + selectedRelations.size() + ", sequences=" + sequences.size() + "]";
    }
}
