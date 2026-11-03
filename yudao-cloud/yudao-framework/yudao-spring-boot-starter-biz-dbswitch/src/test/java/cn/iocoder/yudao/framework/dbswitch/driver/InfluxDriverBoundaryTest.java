package cn.iocoder.yudao.framework.dbswitch.driver;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

/** A compiled driver fixture exercises the real isolated lease, proxy and physical permit path. */
class InfluxDriverBoundaryTest {
    @TempDir Path directory;
    private static final String ENTRY = "org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver";

    @Test void closeAndAbortRetirePhysicalPermitsAndCannotUnwrapTheInfluxAdapter() throws Exception {
        try (var owner = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3, fixture()))) {
            var binding = owner.find(ProductTypeEnum.INFLUXDB3).orElseThrow();
            for (boolean abort : List.of(false, true)) {
                try (var lease = binding.acquire()) {
                    var connection = lease.connect("jdbc:fixture:influx", new Properties());
                    assertEquals(1, owner.runtimes().connections().snapshot().active());
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> DriverNativeAccess.unwrap(connection, "java.lang.Object"));
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT time")) {
                        assertEquals(Instant.parse("2026-01-01T00:00:00.123456789Z"), rows.getObject(1, Instant.class));
                        assertSame(connection, rows.getStatement().getConnection());
                    }
                    var physicalExit=((DriverNativeAccess.ConnectionAccess)connection).physicalTermination();
                    if (abort) connection.abort(Runnable::run); else connection.close();
                    physicalExit.toCompletableFuture().get(2,java.util.concurrent.TimeUnit.SECONDS);
                    assertTrue(connection.isClosed());
                    assertEquals(0, owner.runtimes().connections().snapshot().active());
                    assertEquals(0, owner.runtimes().connections().snapshot().quarantined());
                }
            }
        }
    }

    @Test void nativeWorkerThatOutlivesTheDeadlineKeepsItsPhysicalPermitUntilItsActualExit()throws Exception {
        var workerEntered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var owner=new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3,fixture()));var lease=owner.find(ProductTypeEnum.INFLUXDB3).orElseThrow().acquire()) {
            var connection=lease.connect("jdbc:fixture:influx",new Properties());
            var exit=((DriverNativeAccess.ConnectionAccess)connection).physicalTermination();
            try {
                assertThrows(SQLTimeoutException.class,()->DriverNativeAccess.invoke(connection,100,()->{
                    workerEntered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}
                    return null;
                }));
                assertTrue(workerEntered.await(1,java.util.concurrent.TimeUnit.SECONDS));assertFalse(exit.toCompletableFuture().isDone());
                assertEquals(0,owner.runtimes().connections().snapshot().active());assertEquals(1,owner.runtimes().connections().snapshot().quarantined());
                assertEquals(1,owner.runtimes().connections().snapshot().workload());
            }finally{release.countDown();exit.toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS);connection.close();}
            assertEquals(0,owner.runtimes().connections().snapshot().active());assertEquals(0,owner.runtimes().connections().snapshot().quarantined());
        }
    }

    @Test void anotherProductUsingTheSameDriverClassKeepsItsOriginalTemporalContract() throws Exception {
        try (var owner = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL, fixture()));
             var lease = owner.find(ProductTypeEnum.POSTGRESQL).orElseThrow().acquire();
             var connection = lease.connect("jdbc:fixture:other", new Properties());
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT time")) {
            assertEquals(Instant.parse("2025-12-31T08:00:00.123456789Z"), ((Timestamp) rows.getObject(1)).toInstant());
        }
    }

    private DriverBundleSpec fixture() throws Exception {
        Path source = directory.resolve("src/org/apache/arrow/driver/jdbc/ArrowFlightJdbcDriver.java");
        Files.createDirectories(source.getParent()); Files.writeString(source, DRIVER_SOURCE);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()));
        Path jar = directory.resolve("driver.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            String entry = ENTRY.replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(entry)); Files.copy(classes.resolve(entry), output); output.closeEntry();
        }
        return new Spec(jar.toString(), VerifiedDriverTestHandle.sha256(jar));
    }
    record Spec(String getPath, String getSha256) implements DriverBundleSpec {
        @Override public String getDriverClassName() { return ENTRY; }
        @Override public String getBundleRoot() { return null; }
        @Override public List<DriverBundleSpec.Artifact> getArtifacts() { return List.of(); }
        @Override public Lifecycle getLifecycle() { return Lifecycle.LEASED; }
    }
    private static final String DRIVER_SOURCE = """
            package org.apache.arrow.driver.jdbc;
            import java.sql.*; import java.util.*; import java.lang.reflect.*; import java.util.logging.Logger;
            public class ArrowFlightJdbcDriver implements Driver {
              public Connection connect(String url,Properties p) {
                boolean[] closed={false};
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(self,m,a)->{
                  switch(m.getName()) {
                    case "close": case "abort": closed[0]=true; return null;
                    case "isClosed": return closed[0];
                    case "createStatement": return statement((Connection)self);
                    case "unwrap": if(((Class)a[0]).isInstance(self))return self; throw new SQLException("unsupported");
                    case "isWrapperFor": return ((Class)a[0]).isInstance(self);
                  }
                  return scalar(m.getReturnType());
                });
              }
              private Statement statement(Connection c) {
                return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(self,m,a)->{
                  if(m.getName().equals("getConnection"))return c;
                  if(m.getName().equals("executeQuery"))return rows((Statement)self);
                  return scalar(m.getReturnType());
                });
              }
              private ResultSet rows(Statement s) {
                var metadata=Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSetMetaData.class},(p,m,a)->{
                  if(m.getName().equals("getColumnType"))return Types.TIMESTAMP;
                  return scalar(m.getReturnType());
                });
                return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(self,m,a)->{
                  switch(m.getName()) {
                    case "getMetaData": return metadata;
                    case "getStatement": return s;
                    case "getString": return "2026-01-01 00:00:00.123456789";
                    case "getObject": case "getTimestamp": return Timestamp.from(java.time.Instant.parse("2025-12-31T08:00:00.123456789Z"));
                  }
                  return scalar(m.getReturnType());
                });
              }
              private static Object scalar(Class t){if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;return null;}
              public boolean acceptsURL(String u){return true;}
              public DriverPropertyInfo[] getPropertyInfo(String u,Properties p){return new DriverPropertyInfo[0];}
              public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;}
              public boolean jdbcCompliant(){return false;} public Logger getParentLogger(){return Logger.getGlobal();}
            }
            """;
}
