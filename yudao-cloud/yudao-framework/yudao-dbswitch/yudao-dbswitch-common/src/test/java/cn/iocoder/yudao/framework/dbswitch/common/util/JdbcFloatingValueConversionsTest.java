package cn.iocoder.yudao.framework.dbswitch.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.*;

class JdbcFloatingValueConversionsTest {
    @Test void typedNegativeZeroSurvivesSameTypeAndCrossTypeConversions() {
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(JdbcValueConversions.toFloat(-0.0f)));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(JdbcValueConversions.toFloat(-0.0d)));
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(JdbcValueConversions.toDouble(-0.0f)));
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(JdbcValueConversions.toDouble(-0.0d)));
        assertEquals(Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(JdbcValueConversions.toFloat(0.0f)));
        assertEquals(Double.doubleToRawLongBits(0.0d), Double.doubleToRawLongBits(JdbcValueConversions.toDouble(0.0d)));
    }

    @Test void binaryFloatWideningKeepsItsActualValueInsteadOfItsShortestDecimalRendering() {
        float source = 0.1f;
        assertEquals(Double.doubleToRawLongBits((double) source), Double.doubleToRawLongBits(JdbcValueConversions.toDouble(source)));
        assertNotEquals(Double.doubleToRawLongBits(0.1d), Double.doubleToRawLongBits(JdbcValueConversions.toDouble(source)));
        assertEquals(Float.floatToRawIntBits(Float.MIN_VALUE), Float.floatToRawIntBits(JdbcValueConversions.toFloat(Float.MIN_VALUE)));
        assertEquals(Double.doubleToRawLongBits(Double.MIN_VALUE), Double.doubleToRawLongBits(JdbcValueConversions.toDouble(Double.MIN_VALUE)));
    }

    @Test void nonfiniteValuesAndOverflowRemainRejected() {
        for (Number value : new Number[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toFloat(value));
            assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toDouble(value));
        }
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toFloat(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toFloat(new BigDecimal("1E999")));
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toDouble(new BigDecimal("1E999")));
    }

    @Test void decimalTextAndNullInputsKeepTheirExistingContract() {
        assertEquals(0.25f, JdbcValueConversions.toFloat(new BigDecimal("0.25")));
        assertEquals(0.25d, JdbcValueConversions.toDouble("0.25"));
        assertEquals(1.0d, JdbcValueConversions.toDouble(true));
        assertEquals(0.0f, JdbcValueConversions.toFloat(false));
        assertNull(JdbcValueConversions.toFloat(null)); assertNull(JdbcValueConversions.toDouble(null));
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.toDouble("private-invalid-number"));
    }

    @Test void copyConversionKeepsIeeeSignsAndStillRejectsNonfiniteValues() {
        var single = (Float) JdbcValueConversions.forJdbcType(-0.0f, Types.REAL);
        var wide = (Double) JdbcValueConversions.forJdbcType(-0.0d, Types.DOUBLE);
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(single));
        assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(wide));
        assertThrows(IllegalArgumentException.class, () -> JdbcValueConversions.forJdbcType(Double.NaN, Types.DOUBLE));
    }
}
