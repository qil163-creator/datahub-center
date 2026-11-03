package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.framework.dbswitch.dbwriter.olap.OlapStreamLoadHelper.ReceiptState.*;

/** Receipt states: accepted without a receipt, committed but not visible, reused label and reported failure all stay UNKNOWN; only a complete visible receipt acknowledges. */
@Timeout(15)
class OlapStreamLoadReceiptTest {
    static String receipt(String status,int total,int loaded,int filtered){return "{\"TxnId\":7,\"Label\":\"$request\",\"Status\":\""+status+"\",\"NumberTotalRows\":"+total+",\"NumberLoadedRows\":"+loaded+",\"NumberFilteredRows\":"+filtered+",\"NumberUnselectedRows\":0}";}

    @Test void vendorStatusesAreClassifiedWithoutInferringVisibility() {
        assertEquals(VISIBLE,OlapStreamLoadHelper.receiptState(receipt("Success",2,2,0),2));
        assertEquals(COMMITTED_NOT_VISIBLE,OlapStreamLoadHelper.receiptState(receipt("Publish Timeout",2,2,0),2));
        assertEquals(LABEL_ALREADY_USED,OlapStreamLoadHelper.receiptState("{\"Status\":\"Label Already Exists\",\"ExistingJobStatus\":\"FINISHED\"}",2));
        assertEquals(REPORTED_FAILURE,OlapStreamLoadHelper.receiptState(receipt("Fail",2,1,1),2));
        assertEquals(VISIBLE_INCOMPLETE,OlapStreamLoadHelper.receiptState(receipt("Success",2,1,1),2),"Filtered rows are never a complete acknowledgement");
        assertEquals(VISIBLE_INCOMPLETE,OlapStreamLoadHelper.receiptState(receipt("Success",3,3,0),2),"A receipt for another batch size");
        assertEquals(VISIBLE_INCOMPLETE,OlapStreamLoadHelper.receiptState("{\"Status\":\"Success\"}",2));
        for(String text:Arrays.asList(null,""," ","[]","{\"Status\":1}","{\"Status\":\"Success\"} trailing","{\"Status\":\"Publish Failed\"}","{\"Status\":\"Success\",\"Status\":\"Fail\"}"))
            assertEquals(UNRECOGNIZED,OlapStreamLoadHelper.receiptState(text,2),String.valueOf(text));
    }
    @ParameterizedTest @CsvSource({"Publish Timeout,COMMITTED_NOT_VISIBLE","Label Already Exists,LABEL_ALREADY_USED","Fail,REPORTED_FAILURE"})
    void nonVisibleReceiptsAreUnknownAfterExactlyOneRequestAndLogOnlyStateAndLabel(String status,String state)throws Exception {
        var server=OlapStreamLoadBoundaryTest.server();var calls=new AtomicInteger();var labels=new CopyOnWriteArrayList<String>();
        server.createContext("/",exchange->{calls.incrementAndGet();labels.add(exchange.getRequestHeaders().getFirst("label"));exchange.getRequestBody().readAllBytes();OlapStreamLoadBoundaryTest.respond(exchange,receipt(status,1,1,0));});server.start();
        var logger=(Logger)LoggerFactory.getLogger(OlapStreamLoadHelper.class);var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);
        try{var failure=assertThrows(UnconfirmedWriteException.class,()->OlapStreamLoadBoundaryTest.helper(server,"db","t").write(List.of("value"),List.<Object[]>of(new Object[]{"private-payload"})));
            assertNull(failure.getCause());assertEquals(1,calls.get(),"No second request with any label");
            assertEquals(1,labels.size());assertTrue(labels.getFirst().matches("dbswitch_[0-9a-f]{32}"),labels::toString);
            assertTrue(capture.list.stream().anyMatch(e->e.getFormattedMessage().contains(state)&&e.getFormattedMessage().contains(labels.getFirst())),()->capture.list.toString());
            assertTrue(capture.list.stream().noneMatch(e->e.getFormattedMessage().contains("private-payload")||e.getFormattedMessage().contains("private-credential")));
        }finally{logger.detachAppender(capture);capture.stop();server.stop(0);}
    }
    @Test void everyRequestCarriesAFreshLabelThatSurvivesTheVerifiedRedirect()throws Exception {
        var backend=OlapStreamLoadBoundaryTest.server();var frontend=OlapStreamLoadBoundaryTest.server();var front=new CopyOnWriteArrayList<String>();var back=new CopyOnWriteArrayList<String>();
        backend.createContext("/",exchange->{back.add(exchange.getRequestHeaders().getFirst("label"));exchange.getRequestBody().readAllBytes();OlapStreamLoadBoundaryTest.respond(exchange,receipt("Success",1,1,0));});
        frontend.createContext("/",exchange->{front.add(exchange.getRequestHeaders().getFirst("label"));exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+backend.getAddress().getPort()+exchange.getRequestURI());exchange.sendResponseHeaders(307,-1);exchange.close();});
        backend.start();frontend.start();
        try{var helper=OlapStreamLoadBoundaryTest.helper(frontend,"db","t");
            var type=Arrays.stream(OlapStreamLoadHelper.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals("Endpoint")).findFirst().orElseThrow();var constructor=type.getDeclaredConstructor(String.class,int.class);constructor.setAccessible(true);
            ReflectionTestUtils.setField(helper,"backends",Set.of(constructor.newInstance("127.0.0.1",backend.getAddress().getPort())));
            assertEquals(1,helper.write(List.of("value"),List.<Object[]>of(new Object[]{"a"})));assertEquals(1,helper.write(List.of("value"),List.<Object[]>of(new Object[]{"b"})));
            assertEquals(front,back,"One label per logical request across the FE redirect");assertEquals(2,new HashSet<>(back).size(),"Each request has its own label");
        }finally{frontend.stop(0);backend.stop(0);}
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"wrong-label","missing-label","zero-txn","text-txn","overflow-txn"})
    void visibleCountsWithoutTheExactLabelAndTransactionRemainUnknownAndPoisonTheInstance(String fault)throws Exception {
        var server=OlapStreamLoadBoundaryTest.server();var calls=new AtomicInteger();
        server.createContext("/",exchange->{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            String body=receipt("Success",1,1,0);
            body=switch(fault){case "wrong-label"->body.replace("$request","another");case "missing-label"->body.replace("\"Label\":\"$request\",","");case "zero-txn"->body.replace("\"TxnId\":7","\"TxnId\":0");case "text-txn"->body.replace("\"TxnId\":7","\"TxnId\":\"7\"");default->body.replace("\"TxnId\":7","\"TxnId\":9223372036854775808");};
            OlapStreamLoadBoundaryTest.respond(exchange,body);});server.start();
        try {var helper=OlapStreamLoadBoundaryTest.helper(server,"db","t");
            var first=assertThrows(UnconfirmedWriteException.class,()->helper.write(List.of("value"),List.<Object[]>of(new Object[]{"private-payload"})));
            assertEquals(UnconfirmedWriteException.ReceiptObservation.VISIBLE_INCOMPLETE,first.receiptObservation());
            assertNotNull(first.requestLabel());assertNull(first.getCause());
            var second=assertThrows(UnconfirmedWriteException.class,()->helper.write(List.of("value"),List.<Object[]>of(new Object[]{"private-payload"})));
            assertEquals(first.requestLabel(),second.requestLabel());assertEquals(1,calls.get());
        }finally{server.stop(0);}
    }

    @Test void interruptAfterSubmissionAbortsTheClientAndLeavesAReconciliationLabel()throws Exception {
        var server=OlapStreamLoadBoundaryTest.server();var received=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);var calls=new AtomicInteger();
        server.createContext("/",exchange->{try{calls.incrementAndGet();exchange.getRequestBody().readAllBytes();received.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);}
            catch(InterruptedException ignored){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var helper=OlapStreamLoadBoundaryTest.helper(server,"db","t");
        var worker=new java.util.concurrent.atomic.AtomicReference<Thread>();
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
            var result=executor.submit(()->{worker.set(Thread.currentThread());return assertThrows(UnconfirmedWriteException.class,()->helper.write(List.of("value"),List.<Object[]>of(new Object[]{"pending"})));});
            assertTrue(received.await(3,java.util.concurrent.TimeUnit.SECONDS));worker.get().interrupt();
            var failure=result.get(3,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(failure.requestLabel());assertNull(failure.getCause());
            var repeated=assertThrows(UnconfirmedWriteException.class,()->helper.write(List.of("value"),List.<Object[]>of(new Object[]{"pending"})));
            assertEquals(failure.requestLabel(),repeated.requestLabel());assertEquals(1,calls.get());
        }finally{release.countDown();server.stop(0);}
    }
}
