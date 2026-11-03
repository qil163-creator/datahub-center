package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.CaseConvertEnum;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.LOCALE)
class MigrationColumnMappingsTest {
    @Test void allUsesGlobalRulesAndConfiguredSourceKeysRatherThanAnIndividualTableRule() {
        var source = source();
        source.setRegexColumnMapper(List.of(new PatternMapper("^SRC_", "dst_")));
        source.setSourcePrimaryKeys(List.of("SRC_business"));
        var ignored = new MapperConfig();
        ignored.setRegexColumnMapper(List.of(new PatternMapper("SRC_id", "wrong")));
        ignored.setSourcePrimaryKeys(List.of("ghost"));
        source.setConfigMap(Map.of("items", ignored));
        var plan = MigrationColumnMappings.resolve(source, target(), "items",
                List.of(column("SRC_id"), column("SRC_business")), List.of("SRC_id"));
        assertEquals(List.of("dst_id", "dst_business"), names(plan.columns()));
        assertEquals(List.of("dst_business"), plan.primaryKeys());
    }

    @Test void singleSelectsTheSourceTableRuleAndIgnoresGlobalAndOtherTableConfiguration() {
        var source = source(); source.setMapperType(MapperType.SINGLE.name());
        source.setRegexColumnMapper(List.of(new PatternMapper("id", "wrong")));
        source.setSourcePrimaryKeys(List.of("ghost"));
        var selected = new MapperConfig();
        selected.setRegexColumnMapper(List.of(new PatternMapper("id", "item_id"), new PatternMapper("payload", "body")));
        selected.setSourcePrimaryKeys(List.of("id"));
        var other = new MapperConfig(); other.setSourcePrimaryKeys(List.of("ghost"));
        source.setConfigMap(Map.of("items", selected, "other", other));
        var plan = MigrationColumnMappings.resolve(source, target(), "items",
                List.of(column("id"), column("payload")), List.of("payload"));
        assertEquals(List.of("item_id", "body"), names(plan.columns()));
        assertEquals(List.of("item_id"), plan.primaryKeys());
    }

    @Test void singleWithoutATableConfigurationKeepsItsObservedKeysAndDoesNotFallBackToGlobalRules() {
        var source = source(); source.setMapperType(MapperType.SINGLE.name());
        source.setRegexColumnMapper(List.of(new PatternMapper("id", "wrong")));
        source.setSourcePrimaryKeys(List.of("ghost"));
        source.setConfigMap(Map.of("other", new MapperConfig()));
        var plan = MigrationColumnMappings.resolve(source, target(), "items", List.of(column("id")), List.of("id"));
        assertEquals(List.of("id"), names(plan.columns())); assertEquals(List.of("id"), plan.primaryKeys());
    }

    @Test void nullOrEmptyConfiguredKeysUseTheObservedCompositeKeyOrder() {
        for (List<String> configured : java.util.Arrays.<List<String>>asList(null, List.of())) {
            var source = source(); source.setSourcePrimaryKeys(configured);
            source.setRegexColumnMapper(List.of(new PatternMapper("^SRC_", "target_")));
            var plan = MigrationColumnMappings.resolve(source, target(), "items",
                    List.of(column("SRC_id"), column("SRC_kind")), List.of("SRC_kind", "SRC_id"));
            assertEquals(List.of("target_id", "target_kind"), names(plan.columns()));
            assertEquals(List.of("target_kind", "target_id"), plan.primaryKeys());
        }
    }

    @ParameterizedTest @NullAndEmptySource
    void excludedNonKeyColumnsKeepTheirTransferPositionsAndDisappearFromDdlColumns(String replacement) {
        var source = source(); source.setRegexColumnMapper(List.of(new PatternMapper("obsolete", replacement)));
        var plan = MigrationColumnMappings.resolve(source, target(), "items",
                List.of(column("id"), column("obsolete"), column("payload")), List.of("id"));
        assertEquals(3, plan.columns().size());
        assertEquals("", plan.columns().get(1).getFieldName());
        assertEquals("", plan.columns().get(1).getLabelName());
        assertEquals(List.of("id", "payload"), names(plan.retainedColumns()));
        assertEquals(List.of("id"), plan.primaryKeys());
    }

    @Test void excludingAnObservedPrimaryKeyIsRejectedEvenWhenOtherColumnsRemain() {
        var source = source(); source.setRegexColumnMapper(List.of(new PatternMapper("id", "")));
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source, target(), "items",
                List.of(column("id"), column("payload")), List.of("id")));
    }

    @Test void configuredKeysCannotBorrowAnotherRealColumnsMappedName() {
        var source = source(); source.setSourcePrimaryKeys(List.of("ghost"));
        source.setRegexColumnMapper(List.of(new PatternMapper("ghost", "id")));
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source, target(), "items",
                List.of(column("id")), List.of("id")));
    }

    @Test void observedKeysAlsoHaveToBelongToTheActualSourceColumnsBeforeRenaming() {
        var source = source(); source.setRegexColumnMapper(List.of(new PatternMapper("ghost", "id")));
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source, target(), "items",
                List.of(column("id")), List.of("ghost")));
    }

    @Test void overlappingTargetNamesAndDuplicateCompositeKeysAreRejected() {
        var source = source(); source.setRegexColumnMapper(List.of(new PatternMapper("old_id", "id")));
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source, target(), "items",
                List.of(column("old_id"), column("id")), List.of("id")));
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source(), target(), "items",
                List.of(column("id")), List.of("id", "id")));
    }

    @Test void caseConversionMustNotHideAColumnCollision() {
        var target = target(); target.setLowercase(true);
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source(), target, "items",
                List.of(column("id"), column("ID")), List.of("id")));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "NONE|user_Name,createdAt|user_Name", "UPPER|USER_NAME,CREATEDAT|USER_NAME",
            "LOWER|user_name,createdat|user_name", "SNAKE|user_name,created_at|user_name",
            "CAMEL|userName,createdAt|userName"})
    void columnModeRunsAfterRegexMappingForColumnsAndKeysIndependentlyOfTheTableMode(
            CaseConvertEnum mode, String expected, String key) {
        var source = source(); source.setRegexColumnMapper(List.of(new PatternMapper("^src_", "")));
        var target = target(); target.setColumnNameCase(mode);
        target.setTableNameCase(CaseConvertEnum.UPPER); target.setLowercase(true);
        var plan = MigrationColumnMappings.resolve(source, target, "items",
                List.of(column("src_user_Name"), column("src_createdAt")), List.of("src_user_Name"));
        assertEquals(List.of(expected.split(",")), names(plan.columns()));
        assertEquals(List.of(expected.split(",")), plan.columns().stream().map(ColumnDescription::getLabelName).toList());
        assertEquals(List.of(key), plan.primaryKeys());
        assertEquals(mode, target.getColumnNameCase()); assertTrue(target.getLowercase());
    }

    @Test void explicitColumnModeOverridesTheLegacyFlagsWhileTableNamesKeepThem() {
        var target = target(); target.setLowercase(true); target.setColumnNameCase(CaseConvertEnum.NONE);
        var plan = MigrationColumnMappings.resolve(source(), target, "items", List.of(column("MiXed")), List.of("MiXed"));
        assertEquals(List.of("MiXed"), names(plan.columns())); assertEquals(List.of("MiXed"), plan.primaryKeys());
        assertEquals("mixed", MigrationTableNames.resolve(source(), target, "MiXed"));
    }

    @ParameterizedTest @CsvSource({"SNAKE,userName,user_name", "CAMEL,user_name,userName", "UPPER,id,ID", "LOWER,Id,id"})
    void explicitModesMustNotHideAColumnCollision(CaseConvertEnum mode, String first, String second) {
        var target = target(); target.setColumnNameCase(mode);
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source(), target, "items",
                List.of(column(first), column(second)), List.of(first)));
    }

    @Test void aConversionThatEmptiesARetainedColumnIsRejectedInsteadOfExcludingIt() {
        var target = target(); target.setColumnNameCase(CaseConvertEnum.CAMEL);
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source(), target, "items",
                List.of(column("id"), column("_")), List.of("id")));
        var renamed = source(); renamed.setRegexColumnMapper(List.of(new PatternMapper("^payload$", "__")));
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(renamed, target, "items",
                List.of(column("id"), column("payload")), List.of("id")));
        var excluded = source(); excluded.setRegexColumnMapper(List.of(new PatternMapper("obsolete", "")));
        var plan = MigrationColumnMappings.resolve(excluded, target, "items", List.of(column("i_d"), column("obsolete")), List.of("i_d"));
        assertEquals(List.of("iD", ""), names(plan.columns())); assertEquals(List.of("iD"), plan.primaryKeys());
    }

    @ParameterizedTest @EnumSource(CaseConvertEnum.class)
    void hiveColumnsAndKeysStayLowercaseWhateverColumnModeWasChosen(CaseConvertEnum mode) {
        var target = target(); target.setTargetProductType(ProductTypeEnum.HIVE); target.setColumnNameCase(mode);
        var plan = MigrationColumnMappings.resolve(source(), target, "items", List.of(column("MiXED_ID")), List.of("MiXED_ID"));
        assertEquals(List.of("mixed_id"), names(plan.columns())); assertEquals(List.of("mixed_id"), plan.primaryKeys());
    }

    @Test void noColumnsOrExcludingAllColumnsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source(), target(), "items", List.of(), List.of()));
        var source = source(); source.setRegexColumnMapper(List.of(new PatternMapper(".*", "")));
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source, target(), "items",
                List.of(column("id"), column("payload")), List.of()));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"all", "unexpected"})
    void malformedModesCannotSilentlyIgnoreTheConfiguredMapping(String mode) {
        var source = source(); source.setMapperType(mode); source.setRegexColumnMapper(List.of(new PatternMapper("id", "renamed")));
        assertThrows(IllegalArgumentException.class, () -> MigrationColumnMappings.resolve(source, target(), "items", List.of(column("id")), List.of("id")));
    }

    @ParameterizedTest @CsvSource({"true,false,ID,id", "false,true,id,ID", "true,true,id,ID"})
    void nameAndKeyCaseConversionIsStableUnderATurkishProcessLocale(boolean lower, boolean upper, String original, String expected) {
        var previous = Locale.getDefault(); var display = Locale.getDefault(Locale.Category.DISPLAY); var format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var target = target(); target.setLowercase(lower); target.setUppercase(upper);
            var plan = MigrationColumnMappings.resolve(source(), target, "items", List.of(column(original)), List.of(original));
            assertEquals(List.of(expected), names(plan.columns())); assertEquals(List.of(expected), plan.primaryKeys());
            assertEquals(expected, plan.columns().getFirst().getLabelName());
        } finally {
            Locale.setDefault(previous); Locale.setDefault(Locale.Category.DISPLAY, display); Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,true"})
    void hiveIdentifiersAndKeysRemainLowercaseWhenUppercaseWasRequested(boolean lower, boolean upper) {
        var target = target(); target.setTargetProductType(ProductTypeEnum.HIVE); target.setLowercase(lower); target.setUppercase(upper);
        var plan = MigrationColumnMappings.resolve(source(), target, "items", List.of(column("MiXED_ID")), List.of("MiXED_ID"));
        assertEquals(List.of("mixed_id"), names(plan.columns())); assertEquals(List.of("mixed_id"), plan.primaryKeys());
    }

    @ParameterizedTest @EnumSource(MetadataValue.State.class)
    void omittingDefaultsClearsTheLegacyValueAndOnlyChangesKnownNativeFacts(MetadataValue.State state) {
        MetadataValue<String> observed = switch (state) {
            case VALUE -> MetadataValue.value("42"); case ABSENT -> MetadataValue.absent();
            case UNKNOWN -> MetadataValue.unknown(); case UNSUPPORTED -> MetadataValue.unsupported();
        };
        var sourceColumn = column("amount"); sourceColumn.setDefaultValue("42");
        var evidence = evidence(observed, MetadataValue.unknown()); sourceColumn.setMetadataEvidence(evidence);
        var mapped = MigrationColumnMappings.resolve(source(), target(), "items", List.of(sourceColumn), List.of()).columns().getFirst();
        assertNull(mapped.getDefaultValue());
        assertEquals(observed.known() ? MetadataValue.absent() : observed, mapped.getMetadataEvidence().defaultExpression());
        assertOtherFacts(evidence, mapped.getMetadataEvidence());
        assertEquals("42", sourceColumn.getDefaultValue()); assertSame(evidence, sourceColumn.getMetadataEvidence());
    }

    @Test void syncingDefaultsPreservesTheExactLegacyExpressionAndNativeObservation() {
        var sourceColumn = column("created_at"); String expression = "clock_timestamp()";
        sourceColumn.setDefaultValue(expression); var evidence = evidence(MetadataValue.value(expression), MetadataValue.unknown());
        sourceColumn.setMetadataEvidence(evidence); var target = target(); target.setSyncDefaultVal(true);
        var mapped = MigrationColumnMappings.resolve(source(), target, "items", List.of(sourceColumn), List.of()).columns().getFirst();
        assertNotSame(sourceColumn, mapped); assertEquals(expression, mapped.getDefaultValue());
        assertEquals(expression, mapped.getMetadataEvidence().defaultExpression().value()); assertSame(evidence, mapped.getMetadataEvidence());
    }

    static Stream<Arguments> ownedDefaults() {
        return Stream.of(ColumnSequenceMetadata.Kind.values()).flatMap(kind -> Stream.of(Arguments.of(kind, false), Arguments.of(kind, true)));
    }

    @ParameterizedTest @MethodSource("ownedDefaults")
    void onlyObservedOwnedDefaultsAreRetainedByExplicitIdentityPreservation(ColumnSequenceMetadata.Kind kind, boolean preserve) {
        var sequence = new ColumnSequenceMetadata(kind, "source_scope", "id_seq", "bigint", 1, 1, 1,
                Long.MAX_VALUE, 1, false, MetadataValue.value(new ColumnSequenceMetadata.Position(11, true)));
        boolean owned = kind == ColumnSequenceMetadata.Kind.OWNED_DEFAULT;
        String expression = owned ? "nextval('source_scope.id_seq'::regclass)" : null;
        var column = column("id"); column.setDefaultValue(expression);
        var facts = evidence(owned ? MetadataValue.value(expression) : MetadataValue.absent(), MetadataValue.value(sequence));
        column.setMetadataEvidence(facts); var target = target(); target.setPreserveIdentityValues(preserve); target.setExclusiveTargetOwnership(preserve);
        var mapped = MigrationColumnMappings.resolve(source(), target, "items", List.of(column), List.of("id")).columns().getFirst();
        assertEquals(owned && preserve ? expression : null, mapped.getDefaultValue());
        assertEquals(owned && preserve ? MetadataValue.value(expression) : MetadataValue.absent(), mapped.getMetadataEvidence().defaultExpression());
        assertOtherFacts(facts, mapped.getMetadataEvidence()); assertEquals(expression, column.getDefaultValue());
    }

    @Test void aLegacyAutoIncrementFlagAndNextvalTextDoNotInventAnOwnedSequence() {
        var column = column("id"); column.setAutoIncrement(true); column.setDefaultValue("nextval('source_scope.id_seq'::regclass)");
        column.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.unknown()));
        var target = target(); target.setPreserveIdentityValues(true); target.setExclusiveTargetOwnership(true);
        var mapped = MigrationColumnMappings.resolve(source(), target, "items", List.of(column), List.of("id")).columns().getFirst();
        assertNull(mapped.getDefaultValue()); assertEquals(MetadataValue.unknown(), mapped.getMetadataEvidence().defaultExpression());
        assertEquals(MetadataValue.unknown(), mapped.getMetadataEvidence().sequence());
        assertNotNull(column.getDefaultValue()); assertTrue(column.isAutoIncrement());
    }

    @Test void derivingAndEditingAPlanCannotModifyTheSourceMetadataOrMappingConfiguration() {
        var input = column("ID"); input.setLabelName("Original Label"); input.setDefaultValue("42"); input.setIndexes(new ArrayList<>(List.of("source_index")));
        var facts = evidence(MetadataValue.value("42"), MetadataValue.unknown()); input.setMetadataEvidence(facts);
        var rule = new PatternMapper("ID", "Target_ID"); var rules = new ArrayList<>(List.of(rule)); var keys = new ArrayList<>(List.of("ID"));
        var source = source(); source.setRegexColumnMapper(rules); source.setSourcePrimaryKeys(keys);
        var target = target(); target.setLowercase(true);
        var columns = new ArrayList<>(List.of(input)); var observed = new ArrayList<>(List.of("ID"));
        var plan = MigrationColumnMappings.resolve(source, target, "items", columns, observed);
        assertEquals(List.of("target_id"), names(plan.columns())); assertEquals(List.of("target_id"), plan.primaryKeys());
        var output = plan.columns().getFirst(); assertNotSame(input, output);
        output.setFieldName("changed"); output.getIndexes().add("another_index");
        assertEquals("ID", input.getFieldName()); assertEquals("Original Label", input.getLabelName()); assertEquals("42", input.getDefaultValue());
        assertSame(facts, input.getMetadataEvidence()); assertEquals(List.of("source_index"), input.getIndexes());
        assertSame(rules, source.getRegexColumnMapper()); assertSame(keys, source.getSourcePrimaryKeys());
        assertEquals("ID", rule.getFromPattern()); assertEquals("Target_ID", rule.getToValue());
        assertEquals(List.of("ID"), keys); assertEquals(List.of("ID"), observed); assertSame(input, columns.getFirst());
        assertTrue(target.getLowercase()); assertFalse(target.getUppercase()); assertFalse(target.getSyncDefaultVal());
        assertThrows(UnsupportedOperationException.class, () -> plan.columns().add(column("extra")));
        assertThrows(UnsupportedOperationException.class, () -> plan.primaryKeys().add("extra"));
        assertThrows(UnsupportedOperationException.class, () -> plan.retainedColumns().clear());
    }

    private static SourceDataSourceProperties source() { return new SourceDataSourceProperties(); }
    private static TargetDataSourceProperties target() {
        var target = new TargetDataSourceProperties(); target.setTargetProductType(ProductTypeEnum.POSTGRESQL); return target;
    }
    private static ColumnDescription column(String name) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setLabelName(name);
        column.setDbType(ProductTypeEnum.POSTGRESQL); column.setFieldType(Types.INTEGER); column.setFieldTypeName("int4"); return column;
    }
    private static List<String> names(List<ColumnDescription> columns) { return columns.stream().map(ColumnDescription::getFieldName).toList(); }
    private static ColumnMetadataEvidence evidence(MetadataValue<String> expression, MetadataValue<ColumnSequenceMetadata> sequence) {
        return new ColumnMetadataEvidence(new MetadataProvenance(ProductTypeEnum.POSTGRESQL, "PostgreSQL", "16.15", "PG_CATALOG_V1", Instant.EPOCH),
                MetadataValue.value("integer"), expression, MetadataValue.value(false), MetadataValue.value(32), MetadataValue.value(0),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.unknown(), MetadataValue.unsupported(), MetadataValue.absent(), sequence);
    }
    private static void assertOtherFacts(ColumnMetadataEvidence source, ColumnMetadataEvidence target) {
        assertEquals(source.provenance(), target.provenance()); assertEquals(source.nativeType(), target.nativeType());
        assertEquals(source.nullable(), target.nullable()); assertEquals(source.numericPrecision(), target.numericPrecision());
        assertEquals(source.numericScale(), target.numericScale()); assertEquals(source.characterLength(), target.characterLength());
        assertEquals(source.identity(), target.identity()); assertEquals(source.generatedExpression(), target.generatedExpression());
        assertEquals(source.charset(), target.charset()); assertEquals(source.collation(), target.collation()); assertEquals(source.sequence(), target.sequence());
    }
}
