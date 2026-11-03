package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class OlapStreamLoadBoundaryTest {
    static final String SUCCESS="{\"Label\":\"$request\",\"TxnId\":7,\"Status\":\"Success\",\"NumberTotalRows\":1,\"NumberLoadedRows\":1,\"NumberFilteredRows\":0,\"NumberUnselectedRows\":0}";

    @Test void unadvertisedRedirectNeverReceivesCredentialsOrBody() throws Exception {
        var forwarded=new AtomicInteger(); var target=server(); var source=server();
        target.createContext("/",exchange->{forwarded.incrementAndGet();exchange.getRequestBody().readAllBytes();respond(exchange,SUCCESS);});
        source.createContext("/",exchange->{exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+target.getAddress().getPort()+exchange.getRequestURI());exchange.sendResponseHeaders(307,-1);exchange.close();});
        target.start();source.start();
        try {assertThrows(UnconfirmedWriteException.class,()->helper(source,"db","t").write(List.of("value"),List.<Object[]>of(new Object[]{"private-payload"})));assertEquals(0,forwarded.get());}
        finally {source.stop(0);target.stop(0);}
    }

    @Test void everyRowMustMatchDistinctFieldsBeforeFirstRequest() throws Exception {
        var calls=new AtomicInteger();var source=server();source.createContext("/",exchange->{calls.incrementAndGet();respond(exchange,SUCCESS);});source.start();
        try {var helper=helper(source,"db","t");
            assertThrows(IllegalArgumentException.class,()->helper.write(List.of("value"),List.<Object[]>of(new Object[]{"kept","silently-lost"})));
            assertThrows(IllegalArgumentException.class,()->helper.write(List.of("value","value"),List.<Object[]>of(new Object[]{"lost","kept"})));
            assertThrows(IllegalArgumentException.class,()->helper.write(List.of(),List.<Object[]>of(new Object[]{"lost"})));
            assertThrows(IllegalArgumentException.class,()->helper.write(null,List.<Object[]>of(new Object[]{"lost"})));
            assertEquals(0,calls.get());
        }finally{source.stop(0);}
    }

    @Test void databaseAndTableAreIndependentEncodedPathSegments() throws Exception {
        var path=new AtomicReference<String>();var source=server();source.createContext("/",exchange->{path.set(exchange.getRequestURI().getRawPath());exchange.getRequestBody().readAllBytes();respond(exchange,SUCCESS);});source.start();
        try {var helper=helper(source,"中文#db","t%?/");allowBackend(helper,source);assertEquals(1,helper.write(List.of("value"),List.<Object[]>of(new Object[]{"ok"})));assertEquals("/api/%E4%B8%AD%E6%96%87%23db/t%25%3F%2F/_stream_load",path.get());}
        finally{source.stop(0);}
    }

    @Test void oversizedSuccessBodyCannotAcknowledgeUnboundedResponse() throws Exception {
        var source=server();source.createContext("/",exchange->{exchange.getRequestBody().readAllBytes();respond(exchange,SUCCESS.substring(0,SUCCESS.length()-1)+",\"Message\":\""+"x".repeat(70_000)+"\"}");});source.start();
        try {assertThrows(UnconfirmedWriteException.class,()->helper(source,"db","t").write(List.of("value"),List.<Object[]>of(new Object[]{"ok"})));}
        finally{source.stop(0);}
    }

    @Test void allProductsRequireStrictNoFilteringHeaders() throws Exception {
        var strict=new AtomicReference<String>();var filtered=new AtomicReference<String>();var source=server();source.createContext("/",exchange->{strict.set(exchange.getRequestHeaders().getFirst("strict_mode"));filtered.set(exchange.getRequestHeaders().getFirst("max_filter_ratio"));exchange.getRequestBody().readAllBytes();respond(exchange,SUCCESS);});source.start();
        try {assertEquals(1,helper(source,"db","t").write(List.of("value"),List.<Object[]>of(new Object[]{"ok"})));assertEquals("true",strict.get());assertEquals("0",filtered.get());}
        finally{source.stop(0);}
    }

    @ParameterizedTest @ValueSource(ints={307,308})
    void verifiedDorisBackendReceivesOnlyLocalCredentialsAfterEncodedUserInfoIsRemoved(int status)throws Exception{
        var target=server();var source=server();var auth=new AtomicReference<String>();var body=new AtomicReference<String>();var calls=new AtomicInteger();
        String user="user@中文",password="p:a%/+";
        String userinfo=java.net.URLEncoder.encode(user,StandardCharsets.UTF_8)+":"+java.net.URLEncoder.encode(password,StandardCharsets.UTF_8);
        target.createContext("/",exchange->{calls.incrementAndGet();auth.set(exchange.getRequestHeaders().getFirst("Authorization"));body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));respond(exchange,SUCCESS);});
        source.createContext("/",exchange->{exchange.getResponseHeaders().set("Location","http://"+userinfo+"@127.0.0.1:"+target.getAddress().getPort()+exchange.getRequestURI());exchange.sendResponseHeaders(status,-1);exchange.close();});target.start();source.start();
        try{var helper=helper(source,"Doris",user,password);allowBackend(helper,target);
            assertEquals(1,helper.write(List.of("value"),List.<Object[]>of(new Object[]{"kept"})));
            assertEquals(1,calls.get());assertTrue(body.get().contains("kept"));assertEquals("Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8)),auth.get());
            var initial=java.net.URI.create("http://127.0.0.1:"+source.getAddress().getPort()+"/api/db/t/_stream_load");
            var uri=validated(helper,initial,java.net.URI.create("http://"+userinfo+"@127.0.0.1:"+target.getAddress().getPort()+initial.getRawPath()));assertNull(uri.getRawUserInfo());
        }finally{source.stop(0);target.stop(0);}
    }

    @ParameterizedTest @ValueSource(strings={"wrong-user","wrong-password","other-host","other-port","other-table","query","fragment","https","control","invalid-utf8","starrocks-userinfo"})
    void redirectedCredentialsOrTargetTamperingNeverSendsToBackend(String fault)throws Exception{
        var target=server();var source=server();var calls=new AtomicInteger();
        target.createContext("/",exchange->{calls.incrementAndGet();respond(exchange,SUCCESS);});
        String base="http://isolated:private-credential@127.0.0.1:"+target.getAddress().getPort()+"/api/db/t/_stream_load";
        String destination=switch(fault){case "wrong-user"->base.replace("isolated:","other:");case "wrong-password"->base.replace("private-credential","other");case "other-host"->base.replace("127.0.0.1","localhost");case "other-port"->base.replace(":"+target.getAddress().getPort(),":1");case "other-table"->base.replace("/t/","/another/");case "query"->base+"?columns=secret";case "fragment"->base+"#secret";case "https"->base.replace("http:","https:");case "control"->base.replace("isolated:","isolated%0A:");case "invalid-utf8"->base.replace("isolated:","%C0%80:");default->base;};
        source.createContext("/",exchange->{exchange.getResponseHeaders().set("Location",destination);exchange.sendResponseHeaders(307,-1);exchange.close();});target.start();source.start();
        try{var helper=helper(source,fault.equals("starrocks-userinfo")?"StarRocks":"Doris","isolated","private-credential");allowBackend(helper,target);
            var failure=assertThrows(UnconfirmedWriteException.class,()->helper.write(List.of("value"),List.<Object[]>of(new Object[]{"kept"})));assertNull(failure.getCause());assertFalse(failure.toString().contains("private-credential"));assertEquals(0,calls.get());
        }finally{source.stop(0);target.stop(0);}
    }

    @Test void evenAnAllowedBackendCannotRedirectASecondTime()throws Exception{
        var source=server();var target=server();var calls=new AtomicInteger();
        source.createContext("/",exchange->{exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+target.getAddress().getPort()+exchange.getRequestURI());exchange.sendResponseHeaders(307,-1);exchange.close();});
        target.createContext("/",exchange->{calls.incrementAndGet();exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+target.getAddress().getPort()+exchange.getRequestURI());exchange.sendResponseHeaders(307,-1);exchange.close();});source.start();target.start();
        try{var helper=helper(source,"db","t");allowBackend(helper,target);assertThrows(UnconfirmedWriteException.class,()->helper.write(List.of("value"),List.<Object[]>of(new Object[]{"kept"})));assertEquals(1,calls.get());}
        finally{source.stop(0);target.stop(0);}
    }

    @Test void tricklingBodyCannotExtendTheAbsoluteRequestDeadline()throws Exception{
        var source=server();var stopped=new java.util.concurrent.CountDownLatch(1);var writes=new AtomicInteger();
        source.createContext("/",exchange->{try{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);for(int n=0;n<30;n++){exchange.getResponseBody().write(' ');exchange.getResponseBody().flush();writes.incrementAndGet();Thread.sleep(200);}}catch(IOException|InterruptedException ignored){}finally{exchange.close();stopped.countDown();}});source.start();
        try{assertTimeoutPreemptively(java.time.Duration.ofSeconds(4),()->assertThrows(UnconfirmedWriteException.class,()->helper(source,"db","t").write(List.of("value"),List.<Object[]>of(new Object[]{"kept"}))));assertTrue(stopped.await(2,java.util.concurrent.TimeUnit.SECONDS));assertTrue(writes.get()<30);}
        finally{source.stop(0);}
    }

    @Test void duplicateReceiptFieldsAndMalformedUtf8CannotAcknowledge()throws Exception{
        for(byte[] body:List.of((SUCCESS.substring(0,SUCCESS.length()-1)+",\"NumberLoadedRows\":1}").getBytes(StandardCharsets.UTF_8),new byte[]{(byte)0xc0,(byte)0x80})){
            var source=server();source.createContext("/",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}exchange.close();});source.start();
            try{assertThrows(UnconfirmedWriteException.class,()->helper(source,"db","t").write(List.of("value"),List.<Object[]>of(new Object[]{"kept"})));}finally{source.stop(0);}
        }
    }

    @ParameterizedTest @ValueSource(strings={"127.0.0.1","172.31.93.3","8.8.8.8","169.254.169.254","0.0.0.0","224.0.0.1"})
    void nativeDiscoveryPinsOnlyTheConfiguredNetworkBoundaryOnOneBoundedConnection(String backend)throws Exception{
        var source=mock(com.zaxxer.hikari.HikariDataSource.class);var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.Statement.class);
        when(source.getJdbcUrl()).thenReturn("jdbc:mysql://127.0.0.1:3306/db");when(source.getUsername()).thenReturn("isolated");when(source.getPassword()).thenReturn("private-credential");
        when(source.getConnection()).thenReturn(connection);when(connection.createStatement()).thenReturn(statement);
        var frontends=mock(java.sql.ResultSet.class);when(frontends.next()).thenReturn(true,false);when(frontends.getString("Alive")).thenReturn("true");when(frontends.getString("HttpPort")).thenReturn("8030");
        var backends=mock(java.sql.ResultSet.class);when(backends.next()).thenReturn(true,false);when(backends.getString("Alive")).thenReturn("true");when(backends.getString("HttpPort")).thenReturn("8040");when(backends.getString(1)).thenReturn(backend);
        var metadata=mock(java.sql.ResultSetMetaData.class);when(backends.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnLabel(1)).thenReturn("Host");
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(frontends);when(statement.executeQuery("SHOW BACKENDS")).thenReturn(backends);
        var helper=OlapStreamLoadHelper.tryCreate(source,"db","t","Doris",7);
        if(backend.equals("127.0.0.1")||backend.equals("172.31.93.3")) {
            assertNotNull(helper);var pins=(java.util.Map<?,?>)ReflectionTestUtils.getField(helper,"pinnedAddresses");assertNotNull(pins);assertTrue(pins.containsKey(backend));
        }else assertNull(helper,"Cross-domain, link-local, wildcard and multicast endpoints must not receive credentials");
        verify(source,times(1)).getConnection();verify(statement).setQueryTimeout(7);verify(statement).setMaxRows(257);verify(connection).close();
    }

    private static void allowBackend(OlapStreamLoadHelper helper,HttpServer target)throws Exception{
        var type=java.util.Arrays.stream(OlapStreamLoadHelper.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("Endpoint")).findFirst().orElseThrow();var constructor=type.getDeclaredConstructor(String.class,int.class);constructor.setAccessible(true);
        ReflectionTestUtils.setField(helper,"backends",Set.of(constructor.newInstance("127.0.0.1",target.getAddress().getPort())));
    }
    private static java.net.URI validated(OlapStreamLoadHelper helper,java.net.URI initial,java.net.URI target)throws Exception{var method=OlapStreamLoadHelper.class.getDeclaredMethod("validatedRedirect",java.net.URI.class,java.net.URI.class);method.setAccessible(true);return (java.net.URI)method.invoke(helper,initial,target);}
    private static OlapStreamLoadHelper helper(HttpServer server,String product,String user,String password)throws Exception{var constructor=OlapStreamLoadHelper.class.getDeclaredConstructor(String.class,String.class,String.class,String.class,String.class,String.class,String.class,int.class);constructor.setAccessible(true);return constructor.newInstance(product,"db","t","127.0.0.1",Integer.toString(server.getAddress().getPort()),user,password,2);}

    static HttpServer server() throws Exception {return HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}
    static OlapStreamLoadHelper helper(HttpServer server,String database,String table)throws Exception{
        var constructor=OlapStreamLoadHelper.class.getDeclaredConstructor(String.class,String.class,String.class,String.class,String.class,String.class,String.class,int.class);constructor.setAccessible(true);
        return constructor.newInstance("Doris",database,table,"127.0.0.1",Integer.toString(server.getAddress().getPort()),"isolated","private-credential",2);
    }
    static void respond(com.sun.net.httpserver.HttpExchange exchange,String body)throws java.io.IOException {byte[] data=body.replace("$request",exchange.getRequestHeaders().getFirst("label")).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,data.length);try(var stream=exchange.getResponseBody()){stream.write(data);}exchange.close();}
}
