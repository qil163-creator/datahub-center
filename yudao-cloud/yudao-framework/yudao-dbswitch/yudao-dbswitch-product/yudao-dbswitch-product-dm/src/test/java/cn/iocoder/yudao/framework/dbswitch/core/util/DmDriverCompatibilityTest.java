package cn.iocoder.yudao.framework.dbswitch.core.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DmDriverCompatibilityTest {
    @ParameterizedTest @ValueSource(strings={"8.1.0.147","- 8.1.0.147 - Production","DM JDBC 8.1.0.147 build 2019-03-27"})
    void preciselyKnownBrokenVersionsAreRejectedWithoutClosingBorrowedConnection(String version)throws Exception {
        var closed=new AtomicInteger();var connection=connection(version,closed,false,false);
        var failure=assertThrows(SQLException.class,()->DmDriverCompatibility.require(connection));
        assertEquals("0A000",failure.getSQLState());assertNull(failure.getCause());assertEquals(0,closed.get());
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"8.1.3.140","- 8.1.5.60 - Production","18.1.0.147","8.1.0.1470","8.1.0.147.1","unknown"})
    void otherVersionsAreNotClaimedKnownBroken(String version)throws Exception {
        var closed=new AtomicInteger();assertDoesNotThrow(()->DmDriverCompatibility.require(connection(version,closed,false,false)));assertEquals(0,closed.get());
    }
    @Test void ownedConnectionClosesOnKnownFailureAndSuppressesSensitiveDriverChain()throws Exception {
        var closed=new AtomicInteger();var source=source(connection("8.1.0.147",closed,false,true));
        var failure=assertThrows(IllegalStateException.class,()->DmDriverCompatibility.require(source));
        assertEquals(1,closed.get());assertSafe(failure);
    }
    @Test void successfulCheckStillRequiresConfirmedOwnedConnectionClose()throws Exception {
        var closed=new AtomicInteger();var failure=assertThrows(IllegalStateException.class,()->DmDriverCompatibility.require(source(connection("8.1.5.60",closed,false,true))));
        assertEquals(1,closed.get());assertSafe(failure);
    }
    @Test void metadataFailureDoesNotLeakTheDriverMessageOrCloseBorrowedConnection()throws Exception {
        var closed=new AtomicInteger();var failure=assertThrows(SQLException.class,()->DmDriverCompatibility.require(connection("8.1.5.60",closed,true,false)));
        assertEquals("08001",failure.getSQLState());assertEquals(0,closed.get());assertSafe(failure);
    }
    @Test void connectionAcquisitionFailureIsSanitized() {
        var source=new AbstractDataSource(){@Override public Connection getConnection()throws SQLException{throw secretFailure();}@Override public Connection getConnection(String u,String p)throws SQLException{return getConnection();}};
        assertSafe(assertThrows(IllegalStateException.class,()->DmDriverCompatibility.require(source)));
    }
    @Test void successfulOwnedCheckClosesExactlyOnce()throws Exception {
        var closed=new AtomicInteger();DmDriverCompatibility.require(source(connection("8.1.3.140",closed,false,false)));assertEquals(1,closed.get());
    }
    private static AbstractDataSource source(Connection connection){return new AbstractDataSource(){@Override public Connection getConnection(){return connection;}@Override public Connection getConnection(String u,String p){return connection;}};}
    private static Connection connection(String version,AtomicInteger closed,boolean metadataFailure,boolean closeFailure) {
        var metadata=(DatabaseMetaData)Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(p,m,a)->{
            if(m.getName().equals("getDriverVersion")){if(metadataFailure)throw secretFailure();return version;}throw new UnsupportedOperationException(m.getName());});
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
            return switch(m.getName()){case "getMetaData"->metadata;case "close"->{closed.incrementAndGet();if(closeFailure)throw secretFailure();yield null;}default->throw new UnsupportedOperationException(m.getName());};});
    }
    private static SQLException secretFailure(){return new SQLException("sensitive-driver-detail");}
    private static void assertSafe(Throwable failure){assertNull(failure.getCause());assertEquals(0,failure.getSuppressed().length);assertFalse(failure.toString().contains("sensitive-driver-detail"));}
}
