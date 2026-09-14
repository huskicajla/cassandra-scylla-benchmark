package ba.unze.research;

import org.junit.jupiter.api.Test;
import com.datastax.oss.driver.api.core.cql.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchMainTest {
    final ResearchMain.Event event=new ResearchMain.Event("sensor-00001",Instant.parse("2025-01-01T00:00:00Z"),new UUID(1,2),"temperature",25.5);

    @Test void reservoirIsDeterministicAndNotAnAliasedStride() {
        List<Integer> a=new ArrayList<>(),b=new ArrayList<>();
        var r1=new SplittableRandom(42);var r2=new SplittableRandom(42);
        for(int i=0;i<100000;i++) {
            ResearchMain.reservoir(a,i,i+1,1000,r1);ResearchMain.reservoir(b,i,i+1,1000,r2);
        }
        assertEquals(a,b);assertEquals(1000,a.size());
        assertTrue(a.stream().map(i->i%10000).distinct().count()>800);
        assertTrue(a.stream().anyMatch(i->i>90000));
    }
    @Test void workloadHasExactRatiosInCompleteBlocks() {
        for(int percentage:new int[]{20,50,80}) {
            long count=0;for(int i=0;i<10000;i++)if(ResearchMain.isWrite(i,percentage,42))count++;
            assertEquals(100L*percentage,count);
        }
    }
    @Test void histogramKeepsConcurrentCountsAndFailureLatencySeparate() throws Exception {
        var m=new ResearchMain.Metrics();var pool=Executors.newFixedThreadPool(8);
        for(int i=0;i<8;i++)pool.submit(()->{for(int j=0;j<1000;j++)m.success(2000000);});
        pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));
        m.failure(30000000000L,new IllegalStateException("timeout"));
        assertEquals(8000,m.successes);assertEquals(1,m.failures);
        assertEquals(2.0,(double)m.snapshot().get("meanMs"),0.01);
        assertTrue((double)m.snapshot().get("failureMeanMs")>29000);
        assertNull(new ResearchMain.Metrics().snapshot().get("p99Ms"));
    }
    @Test void rejectsEmptyReadAndWrongPayload() {
        assertThrows(IllegalStateException.class,()->ResearchMain.validate(page(List.of(),null),event,"READ_SINGLE"));
        assertThrows(IllegalStateException.class,()->ResearchMain.validate(page(List.of(row("other")),null),event,"READ_SINGLE"));
        assertEquals(1,ResearchMain.validate(page(List.of(row(event.device())),null),event,"READ_SINGLE"));
    }
    @Test void consumesAllPagesAndEnforcesSingleRow() {
        var last=page(List.of(row(event.device())),null);
        var first=page(List.of(row(event.device())),last);
        assertEquals(2,ResearchMain.validate(first,event,"READ_PARTITION_WINDOW"));
        assertThrows(IllegalStateException.class,()->ResearchMain.validate(first,event,"READ_SINGLE"));
    }
    @Test void parsesActualDatasetFormat() {
        var parsed=ResearchMain.Event.parse("sensor-00001,2025-01-01T00:00:00.000Z,"+event.id()+",temperature,25.5");
        assertEquals(event,parsed);
        assertThrows(IllegalArgumentException.class,()->ResearchMain.Event.parse("broken"));
    }
    @Test void driverPoliciesExist() throws Exception {
        assertEquals(com.datastax.oss.driver.api.core.retry.RetryDecision.RETHROW,new NoRetryPolicy(null,"default").onRequestAborted(null,new RuntimeException(),0));
        Class.forName("com.datastax.oss.driver.internal.core.specex.NoSpeculativeExecutionPolicy");
    }
    Row row(String device) {
        return (Row)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Row.class},(p,m,args)-> switch(m.getName()) {
            case "getString" -> args[0].equals("device_id")?device:event.metric();
            case "getUuid" -> event.id();case "getInstant" -> event.time();case "getDouble" -> event.value();
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    AsyncResultSet page(List<Row> rows,AsyncResultSet next) {
        return (AsyncResultSet)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{AsyncResultSet.class},(p,m,args)-> switch(m.getName()) {
            case "currentPage" -> rows;case "hasMorePages" -> next!=null;
            case "fetchNextPage" -> CompletableFuture.completedFuture(next);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
}
