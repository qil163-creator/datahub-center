package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class MasterDistributeHttpTest {
    private final MasterDistributeExecutor executor=new MasterDistributeExecutor();
    private MasterDistributeConfigVO.ApiConfig config(String url){var c=new MasterDistributeConfigVO.ApiConfig();c.setUrl(url);return c;}
    private void send(MasterDistributeConfigVO.ApiConfig c){
        var client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        try {MasterDistributeExecutor.sendApiBatch(client,MasterDistributeExecutor.prepareApi(c).request("[{\"id\":1}]".getBytes(StandardCharsets.UTF_8)));}
        finally {client.shutdownNow();}
    }

    private MasterDistributeConfigVO.KeyValue pair(String key,String value){var p=new MasterDistributeConfigVO.KeyValue();p.setKey(key);p.setValue(value);return p;}

    @ParameterizedTest
    @ValueSource(strings={"file:///fixture","http:///missing","http://user:secret@localhost/","http://localhost/path#fragment","http://localhost:0/","http://localhost:65536/","http://local host/"})
    void malformedOrAmbiguousAddressesAreRejected(String url){assertThrows(IllegalArgumentException.class,()->MasterDistributeExecutor.prepareApi(config(url)));}

    @ParameterizedTest
    @ValueSource(strings={"Host","Content-Length","Transfer-Encoding","Connection","Expect","Content-Encoding","bad\r\nname"})
    void transportHeadersCannotBeOverridden(String key){var c=config("https://consumer.invalid/accept");c.setHeaders(List.of(pair(key,"fixture")));assertThrows(IllegalArgumentException.class,()->MasterDistributeExecutor.prepareApi(c));}

    @Test void boundsHeaderInjectionAndNonJsonAreRejectedBeforeSending(){
        var c=config("https://consumer.invalid/accept");
        for(int size:new int[]{0,-1,10001}){c.setFetchSize(size);assertThrows(IllegalArgumentException.class,()->MasterDistributeExecutor.prepareApi(c));}
        c.setFetchSize(100);c.setHeaders(List.of(pair("Authorization","fixture\r\nInjected: true")));assertThrows(IllegalArgumentException.class,()->MasterDistributeExecutor.prepareApi(c));
        c.setHeaders(List.of(pair("Content-Type","text/plain")));assertThrows(IllegalArgumentException.class,()->MasterDistributeExecutor.prepareApi(c));
    }

    @Test void frozenPlanPreservesEncodedQueryAndCaseInsensitiveHeaders(){
        var c=config("https://consumer.invalid/%E6%B5%8B%E8%AF%95?existing=a%2Bb&");c.setParams(List.of(pair("中文 &","a+b 空格")));
        c.setHeaders(List.of(pair("X-Fixture","first"),pair("x-fixture","last"),pair("Content-Type","application/json; charset=UTF-8")));
        var plan=MasterDistributeExecutor.prepareApi(c);c.setUrl("http://other.invalid/");c.getHeaders().get(1).setValue("changed");
        var request=plan.request("[]".getBytes(StandardCharsets.UTF_8));
        assertEquals("existing=a%2Bb&%E4%B8%AD%E6%96%87+%26=a%2Bb+%E7%A9%BA%E6%A0%BC",request.uri().getRawQuery());
        assertEquals("last",request.headers().firstValue("X-Fixture").orElseThrow());assertEquals(List.of("application/json"),request.headers().allValues("Content-Type"));
        assertEquals(100,plan.fetchSize());assertEquals(2,request.bodyPublisher().orElseThrow().contentLength());assertEquals("POST",request.method());
    }

    @Test void sameRequestBodyCannotBeSubscribedTwiceEvenIfTransportRequestsRetry() throws Exception {
        var publisher=MasterDistributeExecutor.prepareApi(config("https://consumer.invalid/accept")).request("[]".getBytes(StandardCharsets.UTF_8)).bodyPublisher().orElseThrow();
        for(int attempt=0;attempt<2;attempt++){
            var completion=new CompletableFuture<Integer>();var bytes=new AtomicInteger();
            publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
                public void onNext(java.nio.ByteBuffer buffer){bytes.addAndGet(buffer.remaining());}
                public void onError(Throwable error){completion.completeExceptionally(error);}
                public void onComplete(){completion.complete(bytes.get());}
            });
            if(attempt==0)assertEquals(2,completion.get(2,TimeUnit.SECONDS));else{assertThrows(ExecutionException.class,()->completion.get(2,TimeUnit.SECONDS));assertEquals(0,bytes.get());}
        }
    }

    @ParameterizedTest
    @ValueSource(ints={200,201,202,204,299,301,302,307,308,400,401,429,500,503})
    void only2xxTransportReceiptsAreAcceptedWithoutRedirectOrRetry(int status) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();var redirected=new AtomicInteger();
        server.createContext("/accept",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().add("Location","/redirected");exchange.sendResponseHeaders(status,-1);exchange.close();});
        server.createContext("/redirected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try {var c=config("http://127.0.0.1:"+server.getAddress().getPort()+"/accept");if(status<300)assertDoesNotThrow(()->send(c));else assertThrows(RuntimeException.class,()->send(c));assertEquals(1,calls.get());assertEquals(0,redirected.get());}
        finally{server.stop(0);}
    }

    @Test void responseBodyTimeoutCancelsRequestAndDoesNotBecomeSuccess() throws Exception {
        slowResponse(false);
    }

    @Test void interruptionCancelsRequestPreservesFlagAndPreventsNextBatch() throws Exception {
        slowResponse(true);
    }

    private void slowResponse(boolean interrupt) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        server.createContext("/accept",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,1024);entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();var interrupted=new AtomicBoolean();
        var finished=new CompletableFuture<Void>();
        var plan=MasterDistributeExecutor.prepareApi(config("http://127.0.0.1:"+server.getAddress().getPort()+"/accept"));
        var request=HttpRequest.newBuilder(plan.request("[]".getBytes(StandardCharsets.UTF_8)),(name,value)->true).timeout(Duration.ofMillis(interrupt?3000:500)).build();
        var worker=new Thread(()->{try{assertThrows(IllegalStateException.class,()->MasterDistributeExecutor.sendApiBatch(client,request));interrupted.set(Thread.currentThread().isInterrupted());
            if(interrupt)assertThrows(IllegalStateException.class,()->MasterDistributeExecutor.sendApiBatch(client,plan.request("[]".getBytes(StandardCharsets.UTF_8))));finished.complete(null);
        }catch(Throwable error){finished.completeExceptionally(error);}},"master-http-cancel-fixture");
        try{worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));if(interrupt)worker.interrupt();finished.get(4,TimeUnit.SECONDS);assertEquals(interrupt,interrupted.get());assertEquals(1,calls.get());}
        finally{release.countDown();worker.interrupt();worker.join(3000);client.shutdownNow();server.stop(0);assertFalse(worker.isAlive());}
    }

    @Test void saveUsesSamePreflightAndDoesNotPersistInvalidConfiguration(){
        var service=new MasterDistributeServiceImpl();var tasks=mock(MasterDistributeMapper.class);var models=mock(MasterModelMapper.class);
        var model=new MasterModelDO();model.setId(20L);model.setStatus(1);when(models.selectById(20L)).thenReturn(model);
        ReflectionTestUtils.setField(service,"modelMapper",models);ReflectionTestUtils.setField(service,"distributeMapper",tasks);
        var req=new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeSaveReqVO();req.setMasterModelId(20L);req.setDistributeType(2);
        var c=new MasterDistributeConfigVO();c.setApi(config("http://fixture-user:fixture-private@localhost/"));req.setConfig(c);
        var ex=assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.createDistribute(req));
        assertFalse(ex.getMessage().contains("fixture-private"));verifyNoInteractions(tasks);
    }

    @Test void lostResponseMustNotRepeatAlreadyReceivedPost() throws Exception {
        try(var server=new ServerSocket(0,2,InetAddress.getLoopbackAddress());var workers=Executors.newSingleThreadExecutor()){
            server.setSoTimeout(1200);var received=new AtomicInteger();
            var listener=workers.submit(()->{
                for(int attempt=0;attempt<2;attempt++){
                    try(var socket=server.accept()){
                        socket.setSoTimeout(3000);var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                        String line=input.readLine();if(line==null)continue;assertTrue(line.startsWith("POST "));int length=0;
                        while((line=input.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
                        if(line==null)continue;
                        assertTrue(length>0);int remaining=length;char[] body=new char[length];
                        // SDK 可能重建连接/发送头，但拒绝再次提供 body 后不能形成第二个完整业务请求。
                        while(remaining>0){int count=input.read(body,length-remaining,remaining);if(count<0)return null;remaining-=count;}received.incrementAndGet();
                        // 接收端已收到完整请求，但在回响应之前断开连接。
                    }catch(SocketTimeoutException done){return null;}
                }
                return null;
            });
            assertThrows(RuntimeException.class,()->send(config("http://127.0.0.1:"+server.getLocalPort()+"/accept")));
            listener.get(6,TimeUnit.SECONDS);assertEquals(1,received.get(),"同一批业务数据不能因丢回执而再次 POST");
        }
    }

    @Test void serverErrorDoesNotExposeResponseBody() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var received=new AtomicInteger();
        server.createContext("/accept",exchange->{received.incrementAndGet();exchange.getRequestBody().readAllBytes();byte[] body="fixture-private-remote-result".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try {
            var failure=assertThrows(RuntimeException.class,()->send(config("http://127.0.0.1:"+server.getAddress().getPort()+"/accept")));
            assertFalse(failure.toString().contains("fixture-private-remote-result"));assertEquals(1,received.get());
        }finally{server.stop(0);}
    }

    @Test void invalidAddressMustFailBeforeEnteringSending() {
        var tasks=mock(MasterDistributeMapper.class);var models=mock(MasterModelMapper.class);var coordinator=mock(MasterDistributeRunCoordinator.class);
        var task=MasterDistributeDO.builder().id(10L).masterModelId(20L).distributeType(2).distributeJson("{\"api\":{\"url\":\"file:///fixture\"}}").build();
        var model=new MasterModelDO();model.setId(20L);model.setStatus(1);
        when(tasks.selectById(10L)).thenReturn(task);when(models.selectById(20L)).thenReturn(model);when(coordinator.begin(any())).thenReturn(true);
        ReflectionTestUtils.setField(executor,"distributeMapper",tasks);ReflectionTestUtils.setField(executor,"modelMapper",models);ReflectionTestUtils.setField(executor,"coordinator",coordinator);
        var run=MasterDistributeLogDO.builder().id(100L).distributeId(10L).dataCount(0L).byteCount("0").build();
        executor.execute(run);verify(coordinator,never()).begin(any());verify(coordinator).finish(eq(run),eq(false),eq(4),anyString());
    }
}
