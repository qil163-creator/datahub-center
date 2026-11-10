package cn.iocoder.yudao.module.governance.service.standardconstraint;

import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StandardConstraintCompilerTest {

    private final StandardConstraintCompiler compiler = new StandardConstraintCompiler();

    @Test
    void shouldCompileSixDeclaredConstraintsInCanonicalOrder() {
        StandardElementDO element = element();
        element.setNullableFlag(0);
        element.setDataLength(64);
        element.setDataPrecision(10);
        element.setDataScale(2);
        element.setCodeSetId(9);
        element.setValueRange("(0, 100]");
        element.setDataFormat("yyyy-MM-dd");

        var result = compiler.compile(element, enabledCodeSet(), List.of(
                item(2, "B", (short) 0),
                item(1, "A", (short) 0)));

        assertThat(result.errors()).isEmpty();
        assertThat(result.constraints()).extracting(StandardConstraintCompiler.CompiledConstraint::constraintType)
                .containsExactly("NON_NULL", "LENGTH", "PRECISION_SCALE", "ENUM", "NUMERIC_RANGE", "DATE_FORMAT");
        assertThat(result.constraints()).allSatisfy(constraint -> {
            assertThat(constraint.canonicalConfig()).isNotBlank();
            assertThat(constraint.constraintHash()).hasSize(64);
            assertThat(constraint.strategyKey()).isNotBlank();
        });
        assertThat(find(result, "ENUM").config().get("validValues")).isEqualTo(List.of("A", "B"));
        assertThat(find(result, "NUMERIC_RANGE").config())
                .containsEntry("includeMin", false).containsEntry("includeMax", true);
        assertThat(find(result, "PRECISION_SCALE").config()).containsEntry("skipInteger", true);
        assertThat(find(result, "DATE_FORMAT").config())
                .containsEntry("pattern", "yyyy-MM-dd").containsKey("regex");
    }

    @Test
    void nonDateDataFormatShouldCompileAsRegex() {
        StandardElementDO element = element();
        element.setDataFormat("^[A-Z]{2}\\d{4}$");

        var result = compiler.compile(element, null, List.of());

        assertThat(result.errors()).isEmpty();
        assertThat(result.constraints()).extracting(StandardConstraintCompiler.CompiledConstraint::constraintType)
                .containsExactly("REGEX");
    }

    @Test
    void nonNullShouldRejectBlankOnlyForCharacterTypesSoNumericZeroRemainsValid() {
        StandardElementDO number = element(); number.setNullableFlag(0); number.setColumnType("DECIMAL");
        StandardElementDO text = element(); text.setNullableFlag(0); text.setColumnType("VARCHAR");

        assertThat(find(compiler.compile(number, null, List.of()), "NON_NULL").config())
                .containsEntry("allowBlank", true);
        assertThat(find(compiler.compile(text, null, List.of()), "NON_NULL").config())
                .containsEntry("allowBlank", false);
    }

    @Test
    void enumShouldIgnoreBlankDisabledAndOutOfWindowValuesAndHashDeterministically() {
        StandardElementDO element = element();
        element.setCodeSetId(9);
        List<StandardCodeItemDO> first = new ArrayList<>(List.of(
                item(5, " B ", (short) 0), item(4, "A", (short) 0),
                item(3, "A", (short) 0), item(2, "", (short) 0),
                item(1, "Z", (short) 1),
                item(6, "C", (short) 1),
                item(7, "D", (short) 1)));
        List<StandardCodeItemDO> reversed = new ArrayList<>(first);
        java.util.Collections.reverse(reversed);

        var left = compiler.compile(element, enabledCodeSet(), first);
        var right = compiler.compile(element, enabledCodeSet(), reversed);

        assertThat(find(left, "ENUM").config().get("validValues")).isEqualTo(List.of("A", "B"));
        assertThat(find(left, "ENUM").constraintHash()).isEqualTo(find(right, "ENUM").constraintHash());
    }

    @Test
    void invalidLengthAndPrecisionScaleShouldReturnStructuredErrors() {
        StandardElementDO element = element();
        element.setDataLength(0);
        element.setDataPrecision(2);
        element.setDataScale(3);

        var result = compiler.compile(element, null, List.of());

        assertThat(result.errors()).extracting(StandardConstraintCompiler.CompileError::code)
                .containsExactly("INVALID_LENGTH", "INVALID_PRECISION_SCALE");
    }

    @Test
    void emptyOrDisabledCodeSetShouldNotBeSilentlyAccepted() {
        StandardElementDO element = element();
        element.setCodeSetId(9);

        assertThat(compiler.compile(element, enabledCodeSet(), List.of()).errors())
                .extracting(StandardConstraintCompiler.CompileError::code).containsExactly("EMPTY_CODE_SET");
        assertThat(compiler.compile(element, StandardCodeSetDO.builder().id(9).status((short) 1).build(),
                List.of(item(1, "A", (short) 0))).errors())
                .extracting(StandardConstraintCompiler.CompileError::code).containsExactly("CODE_SET_DISABLED");
    }

    @Test
    void invalidRangeRegexAndUnsupportedDatePatternShouldReturnErrors() {
        StandardElementDO range = element();
        range.setValueRange("[10, 1]");
        assertThat(compiler.compile(range, null, List.of()).errors())
                .extracting(StandardConstraintCompiler.CompileError::code).contains("INVALID_NUMERIC_RANGE");

        StandardElementDO regex = element();
        regex.setDataFormat("[broken");
        assertThat(compiler.compile(regex, null, List.of()).errors())
                .extracting(StandardConstraintCompiler.CompileError::code).contains("INVALID_REGEX");

        StandardElementDO date = element();
        date.setColumnType("TIMESTAMP");
        date.setDataFormat("yyyy-QQ-dd");
        assertThat(compiler.compile(date, null, List.of()).errors())
                .extracting(StandardConstraintCompiler.CompileError::code).contains("UNSUPPORTED_DATE_FORMAT");
    }

    @Test
    void numericConstraintsShouldRejectIncompatibleColumnTypes() {
        StandardElementDO element = element();
        element.setColumnType("VARCHAR");
        element.setDataPrecision(10);
        element.setDataScale(2);
        element.setValueRange("[0,100]");

        assertThat(compiler.compile(element, null, List.of()).errors())
                .extracting(StandardConstraintCompiler.CompileError::code)
                .containsExactly("TYPE_INCOMPATIBLE", "TYPE_INCOMPATIBLE");
    }

    @Test
    void pointAndIntervalMustNotMatchIntegerSubstringAndFloatPrecisionIsUnsupported() {
        StandardElementDO point = element(); point.setColumnType("POINT"); point.setValueRange("[0,1]");
        StandardElementDO interval = element(); interval.setColumnType("INTERVAL"); interval.setValueRange("[0,1]");
        StandardElementDO floating = element(); floating.setColumnType("DOUBLE PRECISION");
        floating.setDataPrecision(10); floating.setDataScale(2);

        assertThat(compiler.compile(point, null, List.of()).errors())
                .extracting(StandardConstraintCompiler.CompileError::code).contains("TYPE_INCOMPATIBLE");
        assertThat(compiler.compile(interval, null, List.of()).errors())
                .extracting(StandardConstraintCompiler.CompileError::code).contains("TYPE_INCOMPATIBLE");
        assertThat(compiler.compile(floating, null, List.of()).errors())
                .extracting(StandardConstraintCompiler.CompileError::code).contains("TYPE_INCOMPATIBLE");
    }

    @Test
    void incompleteDefinitionShouldFailClosed() {
        StandardElementDO element = element();
        element.setColumnType(" ");
        element.setNullableFlag(null);

        var result = compiler.compile(element, null, List.of());

        assertThat(result.errors()).extracting(StandardConstraintCompiler.CompileError::code)
                .containsExactly("COLUMN_TYPE_MISSING", "NULLABLE_FLAG_MISSING");
        assertThat(result.compilable()).isFalse();
    }

    @Test
    void changingCurrentDefinitionChangesCompiledConstraints() {
        StandardElementDO element = element();
        element.setDataLength(32);
        var before = compiler.compile(element, null, List.of());
        element.setDataLength(64);
        var after = compiler.compile(element, null, List.of());
        assertThat(find(before, StandardConstraintCompiler.LENGTH).constraintHash())
                .isNotEqualTo(find(after, StandardConstraintCompiler.LENGTH).constraintHash());
    }

    private static StandardConstraintCompiler.CompiledConstraint find(
            StandardConstraintCompiler.Compilation result, String type) {
        return result.constraints().stream().filter(item -> type.equals(item.constraintType())).findFirst().orElseThrow();
    }

    private static StandardElementDO element() {
        return StandardElementDO.builder().id(1).code("DE000001").name("金额")
                .engName("amount").columnType("DECIMAL").nullableFlag(1).build();
    }

    private static StandardCodeSetDO enabledCodeSet() {
        return StandardCodeSetDO.builder().id(9).status((short) 0).build();
    }

    private static StandardCodeItemDO item(int id, String value, short status) {
        return StandardCodeItemDO.builder().id(id).codeSetId(9).value(value).status(status)
                .build();
    }
}
