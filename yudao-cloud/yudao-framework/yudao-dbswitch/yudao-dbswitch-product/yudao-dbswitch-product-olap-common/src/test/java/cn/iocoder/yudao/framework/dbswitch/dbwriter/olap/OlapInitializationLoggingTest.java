package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OlapInitializationLoggingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationFallbackNeverLogsConnectionOrDriverSecrets(boolean connectionFailure) throws Exception {
        var datasource = mock(HikariDataSource.class);
        String url = "jdbc:mysql://127.0.0.1" + (connectionFailure ? ":9030" : "")
                + "/test?user=fake&password=fake-secret-marker";
        when(datasource.getJdbcUrl()).thenReturn(url);
        if (connectionFailure) {
            when(datasource.getConnection()).thenThrow(
                    new SQLException("Authorization: fake-secret-marker; jdbc=" + url));
        }
        Logger logger = (Logger) LoggerFactory.getLogger(OlapStreamLoadHelper.class);
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        try {
            assertNull(OlapStreamLoadHelper.tryCreate(datasource, "test", "sample", "Doris"),
                    "Pre-submission initialization failure must preserve the JDBC fallback");
            assertFalse(capture.list.isEmpty(), "Fallback must remain diagnosable");
            for (var event : capture.list) {
                assertFalse(event.getFormattedMessage().contains("fake-secret-marker"));
                assertFalse(event.getFormattedMessage().contains("jdbc:mysql"));
                assertFalse(event.getFormattedMessage().contains("Authorization"));
                assertNull(event.getThrowableProxy(), "A raw driver cause may contain credentials");
            }
            if (!connectionFailure) verify(datasource, never()).getConnection();
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }
}
