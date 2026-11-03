package cn.iocoder.yudao.framework.dbswitch.driver;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class MongoNetworkPolicyTest {
    @Test void duplicateCaseEncodedNamesAndPropertiesConvergeToOneFiniteShorterValue() throws Exception {
        var input = new Properties(); input.setProperty("SOCKETTIMEOUTMS", "1250");
        input.setProperty("serverSelectionTimeoutMS", "999999"); input.setProperty("retryWrites", "true");
        var prepared = MongoNetworkPolicy.prepare("jdbc:mongodb://localhost/sample?socketTimeoutMS=0&SocketTimeoutMS=1500&%73ocketTimeoutMS=999999&retryReads=true", input);
        assertEquals("1250", prepared.properties().getProperty("socketTimeoutMS"));
        assertEquals("30000", prepared.properties().getProperty("serverSelectionTimeoutMS"));
        assertEquals("10000", prepared.properties().getProperty("connectTimeoutMS"));
        assertEquals("false", prepared.properties().getProperty("retryWrites"));
        assertEquals(1, prepared.url().split("socketTimeoutMS", -1).length - 1);
        assertEquals("1250", prepared.properties().getProperty("socketTimeoutMS"));
        prepared.properties().setProperty("socketTimeoutMS", "0");
        assertEquals("1250", prepared.properties().getProperty("socketTimeoutMS"));
        assertEquals("1250", input.getProperty("SOCKETTIMEOUTMS"));
        assertFalse(prepared.toString().contains("localhost"));
    }
    @Test void legacyDoubleDecodingAndAlternativeSeparatorsCannotInjectUnboundedOptions() {
        for (String query : new String[]{"appName=ok%26socketTimeoutMS%3D0", "appName=ok%2526socketTimeoutMS%253D0",
                "%2573ocketTimeoutMS=0", "socketTimeoutMS=-1", "socketTimeoutMS=1;socketTimeoutMS=0", "socketTimeoutMS=%00"}) {
            var failure = assertThrows(SQLException.class,
                    () -> MongoNetworkPolicy.prepare("jdbc:mongodb://localhost/sample?" + query, new Properties()));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains(query));
        }
    }
    @Test void infiniteAndLongTimeoutsAreCappedWhileExplicitShorterValuesRemain() throws Exception {
        var prepared = MongoNetworkPolicy.prepare("jdbc:mongodb://localhost/sample?connectTimeoutMS=50&socketTimeoutMS=0&serverSelectionTimeoutMS=90000", new Properties());
        assertEquals("50", prepared.properties().getProperty("connectTimeoutMS"));
        assertEquals("30000", prepared.properties().getProperty("socketTimeoutMS"));
        assertEquals("30000", prepared.properties().getProperty("serverSelectionTimeoutMS"));
    }
}
