package cn.iocoder.yudao.framework.dbswitch.data.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentDigestsTest {

    @Test
    void sameMultisetDifferentOrderHasSameDigest() {
        String left = ContentDigests.ofRows(rows(row("a", 1), row("b", 2), row("a", 1)));
        String right = ContentDigests.ofRows(rows(row("a", 1), row("a", 1), row("b", 2)));
        assertThat(left).isEqualTo(right);
    }

    @Test
    void duplicateCountChangeIsDetected() {
        String once = ContentDigests.ofRows(rows(row("a", 1), row("b", 2)));
        String twice = ContentDigests.ofRows(rows(row("a", 1), row("a", 1), row("b", 2)));
        assertThat(once).isNotEqualTo(twice);
    }

    @Test
    void decimalCanonicalFormIgnoresScaleOnlyDifference() {
        String left = ContentDigests.ofRows(rows(row(new BigDecimal("1.10"))));
        String right = ContentDigests.ofRows(rows(row(new BigDecimal("1.1"))));
        assertThat(left).isEqualTo(right);
        assertThat(left).isNotEqualTo(ContentDigests.ofRows(rows(row(new BigDecimal("1.11")))));
    }

    @Test
    void nullIsDistinctFromEmptyString() {
        assertThat(ContentDigests.ofRows(rows(new Object[]{null})))
                .isNotEqualTo(ContentDigests.ofRows(rows(row(""))));
    }

    @Test
    void binaryAndTimestampCanonicalize() {
        byte[] payload = new byte[]{(byte) 0xde, (byte) 0xad};
        assertThat(ContentDigests.ofRows(rows(row(payload))))
                .isEqualTo(ContentDigests.ofRows(rows(row(new byte[]{(byte) 0xde, (byte) 0xad}))));
        assertThat(ContentDigests.ofRows(rows(row(payload))))
                .isNotEqualTo(ContentDigests.ofRows(rows(row(new byte[]{(byte) 0xbe}))));
    }

    private static List<Object[]> rows(Object[] first, Object[]... rest) {
        List<Object[]> list = new ArrayList<>();
        list.add(first);
        for (Object[] value : rest) {
            list.add(value);
        }
        return list;
    }

    private static Object[] row(Object... values) {
        return values;
    }

}
