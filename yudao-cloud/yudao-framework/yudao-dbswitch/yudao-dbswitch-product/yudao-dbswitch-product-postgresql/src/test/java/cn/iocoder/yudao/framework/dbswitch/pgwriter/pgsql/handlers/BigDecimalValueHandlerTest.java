package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.handlers;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BigDecimalValueHandlerTest {
    @Test void numericWireValuesPreserveLongBigIntegerScaleAndSignWithoutDoubleRoundTrip() throws Exception {
        for (Number value : List.of(Long.MAX_VALUE, new BigInteger("1234567890123456789012345678901234567890"),
                new BigDecimal("-12345678901234567890.12345678901234567890"), new BigDecimal("1E+8"),
                new BigDecimal("0.00000000001"), new BigDecimal("0.000"))) {
            var output = new ByteArrayOutputStream();
            var handler = new BigDecimalValueHandler<Number>();
            handler.handle(new DataOutputStream(output), value);
            var input = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
            assertEquals(handler.getLength(value), input.readInt());
            int count = input.readUnsignedShort(); int weight = input.readShort();
            int sign = input.readUnsignedShort(); int scale = input.readUnsignedShort();
            BigDecimal decoded = BigDecimal.ZERO;
            for (int i = 0; i < count; i++) {
                int digit = input.readUnsignedShort(); assertTrue(digit < 10000);
                decoded = decoded.add(BigDecimal.valueOf(digit).scaleByPowerOfTen(4 * (weight - i)));
            }
            if (sign == 0x4000) decoded = decoded.negate();
            decoded = decoded.setScale(scale);
            assertEquals(0, new BigDecimal(value.toString()).compareTo(decoded));
            assertEquals(0, input.available());
        }
    }
}
