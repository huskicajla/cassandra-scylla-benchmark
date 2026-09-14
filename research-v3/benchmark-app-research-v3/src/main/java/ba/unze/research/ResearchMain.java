package ba.unze.research;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.config.*;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.metadata.NodeState;
import com.fasterxml.jackson.databind.*;
import org.HdrHistogram.Histogram;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ResearchMain {
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    final JsonNode config;
    final Path output;
    final String keyspace, operation;
    final long datasetSize, seed;
    final int concurrency, nodes, rf, writePercent;
    final DefaultConsistencyLevel cl;
    CqlSession session;
    PreparedStatement insert, single, latest, partition, range;
    List<Event> sample;
    long baselineCount;
    Instant maxTime;
    String sampleHash;

    ResearchMain(JsonNode c) {
        config=c; output=Path.of(c.path("output").asText());
        keyspace=c.path("keyspace").asText(); operation=c.path("operation").asText();
        datasetSize=c.path("datasetSize").asLong(); seed=c.path("seed").asLong(20260906);
        concurrency=c.path("concurrency").asInt(); nodes=c.path("nodes").asInt(); rf=c.path("rf").asInt();
        writePercent=c.path("writePercent").asInt(0);
        cl=DefaultConsistencyLevel.valueOf(c.path("cl").asText("LOCAL_QUORUM"));
        if (!keyspace.matches("research_v3_[a-z0-9_]+") || datasetSize<1 || concurrency<1 || nodes<1 || nodes>3 || rf<1 || rf>nodes)
            throw new IllegalArgumentException("Invalid research configuration; legacy keyspaces are forbidden.");
        if (!Set.of("CONCURRENT_WRITE","READ_SINGLE","READ_LATEST","READ_PARTITION_WINDOW","READ_TIME_RANGE","MIXED_WORKLOAD").contains(operation))
            throw new IllegalArgumentException("Unknown operation " + operation);
        if(writePercent<0 || writePercent>100 || c.path("measurementSeconds").asInt()<1 || c.path("warmupSeconds").asInt()<1)
            throw new IllegalArgumentException("Invalid percentages/duration.");
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Usage: java -jar benchmark-research.jar config.json");
        ResearchMain run=new ResearchMain(JSON.readTree(Path.of(args[0]).toFile()));
        Files.createDirectories(run.output);
        if(Files.exists(run.output.resolve("result.json"))) throw new IllegalStateException("Refusing to overwrite an existing result.");
        JSON.writeValue(run.output.resolve("effective-config.json").toFile(),run.config);
        try { run.execute(); }
        catch(Throwable failure) {
            JSON.writeValue(run.output.resolve("aborted.json").toFile(),Map.of("at",Instant.now().toString(),"error",failure.toString()));
            throw failure;
        } finally {
            try { if(run.session!=null) run.session.close(); }
            finally { Files.deleteIfExists(run.output.resolve("phase.txt")); }
        }
    }

    void execute() throws Exception {
        System.setProperty("research.network.prefix",config.path("networkPrefix").asText());
        var loader=DriverConfigLoader.programmaticBuilder()
            .withString(DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER,"datacenter1")
            .withString(DefaultDriverOption.ADDRESS_TRANSLATOR_CLASS,DockerTranslator.class.getName())
            .withString(DefaultDriverOption.RETRY_POLICY_CLASS,NoRetryPolicy.class.getName())
            .withString(DefaultDriverOption.SPECULATIVE_EXECUTION_POLICY_CLASS,"NoSpeculativeExecutionPolicy")
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT,Duration.ofSeconds(30))
            .withDuration(DefaultDriverOption.CONTROL_CONNECTION_TIMEOUT,Duration.ofSeconds(30))
            .withInt(DefaultDriverOption.REQUEST_PAGE_SIZE,100)
            .build();
        session=CqlSession.builder().addContactPoint(new InetSocketAddress("127.0.0.1",19042))
            .withLocalDatacenter("datacenter1").withConfigLoader(loader).build();
        long up=session.getMetadata().getNodes().values().stream().filter(n->n.getState()==NodeState.UP).count();
        if(up!=nodes || session.getMetadata().getNodes().size()!=nodes)
            throw new IllegalStateException("Driver topology mismatch: expected " + nodes + ", UP=" + up);
        createSchema();
        phase("BASELINE_SETUP"); prepareBaseline(); settle();
        phase("WARMUP");
        measure(config.path("warmupSeconds").asInt(60),0,"warmup");
        if(operation.equals("CONCURRENT_WRITE") || operation.equals("MIXED_WORKLOAD")) {
            phase("BASELINE_RESTORE"); prepareBaseline(); settle();
        }
        phase("MEASUREMENT");
        Map<String,Object> result=measure(config.path("measurementSeconds").asInt(180),config.path("minimumOperations").asLong(100000),"measurement");
        result.put("config",config); result.put("baselineRowsAcknowledged",baselineCount);
        result.put("baselineSampleSha256",sampleHash); result.put("readSampleSize",sample.size());
        result.put("initialRows",datasetSize);
        @SuppressWarnings("unchecked") Map<String,Object> writes=(Map<String,Object>)result.get("write");
        result.put("acknowledgedFinalRows",datasetSize + ((Number)writes.get("successful")).longValue());
        result.put("rowCountNote","Initial rows inferred from validated generator input and acknowledged seed operations; final count is a lower bound when writes time out.");
        result.put("latencyDefinition","Closed-loop application RTT including result consumption/validation; excludes worker scheduling and CSV setup. Not open-loop arrival latency.");
        result.put("javaVersion",System.getProperty("java.version"));
        result.put("processors",Runtime.getRuntime().availableProcessors());
        result.put("maxJvmBytes",Runtime.getRuntime().maxMemory());
        Path temporary=output.resolve("result.json.tmp"); JSON.writeValue(temporary.toFile(),result);
        Files.move(temporary,output.resolve("result.json"),StandardCopyOption.REPLACE_EXISTING);
        phase("COMPLETED");
    }

    void phase(String value) throws IOException {
        Files.writeString(output.resolve("phase.txt"),value,StandardCharsets.UTF_8);
        System.out.println(Instant.now()+" "+value);
    }
    void settle() throws InterruptedException { Thread.sleep(config.path("settleSeconds").asInt(30)*1000L); }

    void createSchema() {
        var row=session.execute("SELECT replication FROM system_schema.keyspaces WHERE keyspace_name='"+keyspace+"'").one();
        if(row!=null) {
            var replication=row.getMap("replication",String.class,String.class);
            if(replication==null || !String.valueOf(rf).equals(replication.get("datacenter1")))
                throw new IllegalStateException("Existing research keyspace has different RF; use a separate campaign.");
        }
        session.execute("CREATE KEYSPACE IF NOT EXISTS "+keyspace+" WITH replication={'class':'NetworkTopologyStrategy','datacenter1':"+rf+"}");
        session.execute("CREATE TABLE IF NOT EXISTS "+table()+" (device_id text,event_time timestamp,id uuid,metric_type text,value double,PRIMARY KEY ((device_id),event_time,id))");
        insert=session.prepare("INSERT INTO "+table()+" (device_id,event_time,id,metric_type,value) VALUES (?,?,?,?,?)");
        String select="SELECT device_id,event_time,id,metric_type,value FROM "+table();
        single=session.prepare(select+" WHERE device_id=? AND event_time=? AND id=?");
        latest=session.prepare(select+" WHERE device_id=? ORDER BY event_time DESC LIMIT 1");
        partition=session.prepare(select+" WHERE device_id=? LIMIT 100");
        range=session.prepare(select+" WHERE device_id=? AND event_time>=? AND event_time<=? LIMIT 100");
    }
    String table() { return keyspace+".telemetry_events"; }
    BoundStatement insertion(Event e) { return insert.bind(e.device,e.time,e.id,e.metric,e.value).setConsistencyLevel(cl).setIdempotent(false); }

    void prepareBaseline() throws Exception {
        session.execute("TRUNCATE "+table());
        Path folder=Path.of(config.path("datasetPath").asText());
        List<Path> chunks;
        try(var files=Files.list(folder)) { chunks=files.filter(p->p.getFileName().toString().matches("chunk-\\d+\\.csv")).sorted().toList(); }
        if(chunks.isEmpty()) throw new IllegalStateException("No CSV chunks in "+folder);
        int capacity=(int)Math.min(datasetSize,config.path("sampleSize").asInt(100000));
        if(capacity<1) throw new IllegalArgumentException("Empty read sample");
        sample=new ArrayList<>(capacity);
        SplittableRandom random=new SplittableRandom(seed);
        ExecutorService executor=Executors.newFixedThreadPool(16);
        Semaphore slots=new Semaphore(16);
        AtomicLong successes=new AtomicLong(); AtomicReference<Throwable> failure=new AtomicReference<>();
        long count=0; maxTime=Instant.MIN;
        try {
            outer: for(Path chunk:chunks) {
                try(BufferedReader reader=Files.newBufferedReader(chunk,StandardCharsets.UTF_8)) {
                    String header=reader.readLine();
                    if(header==null || !header.replace("\ufeff","").equals("device_id,event_time,id,metric_type,value"))
                        throw new IllegalArgumentException("Unexpected CSV header: "+chunk);
                    String line;
                    while((line=reader.readLine())!=null) {
                        if(count>=datasetSize) break outer;
                        if(failure.get()!=null) throw new IllegalStateException("Seed failed",failure.get());
                        Event event=Event.parse(line); count++;
                        if(event.time.isAfter(maxTime)) maxTime=event.time;
                        reservoir(sample,event,count,capacity,random);
                        slots.acquire();
                        executor.submit(()->{
                            try { session.executeAsync(insertion(event)).toCompletableFuture().join(); successes.incrementAndGet(); }
                            catch(Throwable t) { failure.compareAndSet(null,t); }
                            finally { slots.release(); }
                        });
                        if(count%1000000==0) System.out.println("Baseline scheduled: "+count+" / "+datasetSize);
                    }
                }
            }
        } finally {
            executor.shutdown();
            if(!executor.awaitTermination(10,TimeUnit.MINUTES)) {executor.shutdownNow();throw new IllegalStateException("Seed did not drain");}
        }
        if(failure.get()!=null || count!=datasetSize || successes.get()!=datasetSize)
            throw new IllegalStateException("Invalid baseline: read="+count+", acknowledged="+successes.get(),failure.get());
        baselineCount=successes.get();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(Event e:sample) digest.update((e.toString()+"\n").getBytes(StandardCharsets.UTF_8));
        sampleHash=HexFormat.of().formatHex(digest.digest());
        for(int i=0;i<Math.min(128,sample.size());i++) validate(executeRead(sample.get(i),"READ_SINGLE"),sample.get(i),"READ_SINGLE");
        JSON.writeValue(output.resolve("baseline.json").toFile(),Map.of("records",baselineCount,"sampleHash",sampleHash,"sampleSize",sample.size(),"maxEventTime",maxTime.toString(),"verifiedSampleReads",Math.min(128,sample.size())));
    }

    static <T> void reservoir(List<T> sample,T item,long seen,int capacity,SplittableRandom random) {
        if(sample.size()<capacity) sample.add(item);
        else { long j=random.nextLong(seen); if(j<capacity) sample.set((int)j,item); }
    }
    static boolean isWrite(long index,int percentage,long seed) {
        return Math.floorMod(Math.floorMod(index,100)*37 + Math.floorMod(seed,100),100)<percentage;
    }
    Event writeEvent(long index) {
        Event template=sample.get((int)Math.floorMod(mix(index ^ seed),sample.size()));
        return new Event(template.device,maxTime.plusMillis(index+1),new UUID(seed,index),template.metric,template.value);
    }
    static long mix(long value) { value=(value^(value>>>30))*0xbf58476d1ce4e5b9L; value=(value^(value>>>27))*0x94d049bb133111ebL; return value^(value>>>31); }

    AsyncResultSet executeRead(Event event,String kind) {
        BoundStatement query=switch(kind) {
            case "READ_SINGLE" -> single.bind(event.device,event.time,event.id);
            case "READ_LATEST" -> latest.bind(event.device);
            case "READ_PARTITION_WINDOW" -> partition.bind(event.device);
            case "READ_TIME_RANGE" -> range.bind(event.device,event.time.minusSeconds(99*60L),event.time);
            default -> throw new IllegalArgumentException(kind);
        };
        return session.executeAsync(query.setConsistencyLevel(cl).setIdempotent(false)).toCompletableFuture().join();
    }
    static long validate(AsyncResultSet result,Event expected,String kind) {
        long count=0;
        do {
            for(Row row:result.currentPage()) {
                count++;
                if(!expected.device.equals(row.getString("device_id"))) throw new DataValidationException("Read returned wrong device");
                if(kind.equals("READ_SINGLE") && (!expected.id.equals(row.getUuid("id")) || !expected.time.equals(row.getInstant("event_time")) || !expected.metric.equals(row.getString("metric_type")) || Double.compare(expected.value,row.getDouble("value"))!=0))
                    throw new DataValidationException("READ_SINGLE returned wrong content");
                Instant time=row.getInstant("event_time");
                if(kind.equals("READ_TIME_RANGE") && (time.isBefore(expected.time.minusSeconds(99*60L)) || time.isAfter(expected.time)))
                    throw new DataValidationException("Read outside requested time window");
            }
            if(!result.hasMorePages()) break;
            result=result.fetchNextPage().toCompletableFuture().join();
        } while(true);
        if(count==0 || count>100 || ((kind.equals("READ_SINGLE") || kind.equals("READ_LATEST")) && count!=1))
            throw new DataValidationException("Unexpected row count "+count+" for "+kind);
        return count;
    }

    Map<String,Object> measure(int seconds,long minOperations,String label) throws Exception {
        Metrics total=new Metrics(),read=new Metrics(),write=new Metrics();
        AtomicLong next=new AtomicLong(),rows=new AtomicLong();
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        AtomicBoolean cancel=new AtomicBoolean();
        ExecutorService pool=Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready=new CountDownLatch(concurrency),start=new CountDownLatch(1);
        long[] beginning=new long[1];
        List<Future<?>> futures=new ArrayList<>();
        long maxOperations=config.path("maxOperations").asLong(20000000);
        for(int worker=0;worker<concurrency;worker++) {
            futures.add(pool.submit(()->{
                ready.countDown();
                try { start.await();
                    while(!cancel.get()) {
                        if(System.nanoTime()-beginning[0]>=seconds*1000000000L && next.get()>=minOperations) break;
                        long index=next.getAndIncrement();
                        if(index>=maxOperations) throw new IllegalStateException("maxOperations reached before protocol completed");
                        boolean writing=operation.equals("CONCURRENT_WRITE") || (operation.equals("MIXED_WORKLOAD") && isWrite(index,writePercent,seed));
                        Metrics split=writing?write:read;
                        Event event=writing?writeEvent(index):sample.get((int)Math.floorMod(mix(index^seed),sample.size()));
                        int inflight=active.incrementAndGet();peak.accumulateAndGet(inflight,Math::max);
                        long t=System.nanoTime();
                        try {
                            if(writing) session.executeAsync(insertion(event)).toCompletableFuture().join();
                            else {String kind=operation.equals("MIXED_WORKLOAD")?"READ_LATEST":operation;rows.addAndGet(validate(executeRead(event,kind),event,kind));}
                            long elapsed=System.nanoTime()-t;split.success(elapsed);total.success(elapsed);
                        } catch(Exception ex) {long elapsed=System.nanoTime()-t;split.failure(elapsed,ex);total.failure(elapsed,ex);}
                        finally {active.decrementAndGet();}
                    }
                } catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new RuntimeException(ex);}
            }));
        }
        ready.await(); beginning[0]=System.nanoTime(); String started=Instant.now().toString();start.countDown();pool.shutdown();
        ScheduledExecutorService progress=Executors.newSingleThreadScheduledExecutor();
        progress.scheduleAtFixedRate(()->{
            try {
                System.out.println(label+" successful="+total.successes+" failed="+total.failures);
            } catch(Exception e) {e.printStackTrace();}
        },10,10,TimeUnit.SECONDS);
        try {
            if(!pool.awaitTermination(config.path("maxRunSeconds").asLong(3600),TimeUnit.SECONDS)) {
                cancel.set(true);pool.shutdownNow();throw new IllegalStateException("Run time limit exceeded; not a complete result");
            }
            for(Future<?> future:futures) future.get();
        } finally {
            cancel.set(true);pool.shutdownNow();progress.shutdownNow();
            if(!pool.awaitTermination(40,TimeUnit.SECONDS)) throw new IllegalStateException("Workers failed to stop");
        }
        double elapsed=(System.nanoTime()-beginning[0])/1e9;
        var result=new LinkedHashMap<String,Object>();
        result.put("startedAt",started);result.put("finishedAt",Instant.now().toString());result.put("elapsedSeconds",elapsed);
        result.put("throughput",total.successes/elapsed);result.put("total",total.snapshot());
        result.put("read",read.snapshot());result.put("write",write.snapshot());result.put("returnedRows",rows.get());
        result.put("rowsPerSecond",rows.get()/elapsed);result.put("peakInFlight",peak.get());
        result.put("status",total.validationFailures>0?"INVALID_DATA":total.failures==0?"COMPLETED":"COMPLETED_WITH_ERRORS");
        result.put("attempted",total.successes+total.failures);
        result.put("actualWriteFraction",(write.successes+write.failures)/(double)Math.max(1,total.successes+total.failures));
        return result;
    }

    record Event(String device,Instant time,UUID id,String metric,double value) {
        static Event parse(String line) {
            String[] p=line.split(",",-1);
            if(p.length!=5) throw new IllegalArgumentException("Expected the project's five-column unquoted CSV format");
            return new Event(p[0],Instant.parse(p[1]),UUID.fromString(p[2]),p[3],Double.parseDouble(p[4]));
        }
    }
    static final class DataValidationException extends IllegalStateException {
        DataValidationException(String message) {super(message);}
    }
    static final class Metrics {
        final Histogram histogram=new Histogram(3),failureHistogram=new Histogram(3);
        volatile long successes,failures,validationFailures;
        final Map<String,Long> errors=new TreeMap<>();
        synchronized void success(long nanos) {histogram.recordValue(Math.max(1,nanos));successes++;}
        synchronized void failure(long nanos,Throwable error) {
            failures++;failureHistogram.recordValue(Math.max(1,nanos));
            Throwable root=error;while(root.getCause()!=null && root.getCause()!=root) root=root.getCause();
            if(root instanceof DataValidationException) validationFailures++;
            errors.merge(root.getClass().getSimpleName()+": "+String.valueOf(root.getMessage()),1L,Long::sum);
        }
        synchronized Map<String,Object> snapshot() {
            var map=new LinkedHashMap<String,Object>();map.put("successful",successes);map.put("failed",failures);
            map.put("validationFailures",validationFailures);
            map.put("meanMs",successes==0?null:histogram.getMean()/1e6);
            for(double p:new double[]{50,95,99}) map.put("p"+(int)p+"Ms",successes==0?null:histogram.getValueAtPercentile(p)/1e6);
            map.put("maxMs",successes==0?null:histogram.getMaxValue()/1e6);
            map.put("failureMeanMs",failures==0?null:failureHistogram.getMean()/1e6);map.put("errors",new TreeMap<>(errors));return map;
        }
    }
}
