package com.platform.energy.classification;

import com.platform.framework.exception.BusinessException;
import org.springframework.dao.QueryTimeoutException;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 请求私有的月源日窗：只扫描一次历史前缀，完整事实按真实相交日落入有界临时文件。
 * 不裁剪跨日事实、不分摊数量；各日仍交给原分类算法判定冲突与总分表去重。
 * 调用者拥有整个生命周期，必须在读日任务退出后关闭，不能由取消 Future 的线程抢先删除。
 */
public final class MonthlyReadingWindow implements AutoCloseable {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static final Limits DEFAULT_LIMITS=new Limits(1000,400_000,256L*1024*1024,16*1024,20_000);
    record Limits(int pageSize,int maximumRows,long maximumBytes,int maximumRowBytes,int maximumRetained,long maximumRetainedBytes) {
        Limits(int pageSize,int maximumRows,long maximumBytes,int maximumRowBytes,int maximumRetained) {
            this(pageSize,maximumRows,maximumBytes,maximumRowBytes,maximumRetained,16L*1024*1024);
        }
    }
    private final String building;
    private final YearMonth month;
    private final Supplier<Duration> remaining;
    private final Limits limits;
    private final Instant scanStart,scanEnd;
    private final boolean singleDay;
    private final Path directory;
    private final Path[] days;
    private final Instant[] dayStarts;
    private final byte[][] hashes;
    private final AtomicBoolean closed=new AtomicBoolean();
    private long writtenBytes;
    private long metadataRows,identifierBytes,identifierAllocationBytes;
    private int candidateIds,payloadBatches,payloadRows,spooledRows;
    record PreparationMetrics(long metadataRows,int candidateIds,long identifierBytes,long identifierAllocationBytes,
                              int payloadBatches,int payloadRows,int spooledRows) {}
    PreparationMetrics metrics(){return new PreparationMetrics(metadataRows,candidateIds,identifierBytes,identifierAllocationBytes,payloadBatches,payloadRows,spooledRows);}
    private record RankedReading(int ordinal,ClassificationRepository.StoredReading reading) {}

    private MonthlyReadingWindow(String building,YearMonth month,Supplier<Duration> remaining,Limits limits,Path parent,Instant scanStart,Instant scanEnd,boolean singleDay) throws IOException {
        this.building=Objects.requireNonNull(building);this.month=Objects.requireNonNull(month);
        this.remaining=Objects.requireNonNull(remaining);this.limits=limits;
        this.scanStart=scanStart;this.scanEnd=scanEnd;this.singleDay=singleDay;
        var attributes=FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                ?new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))}
                :new java.nio.file.attribute.FileAttribute<?>[0];
        directory=parent==null?Files.createTempDirectory("daily-source-",attributes):Files.createTempDirectory(parent,"daily-source-",attributes);
        days=new Path[month.lengthOfMonth()];
        hashes=new byte[days.length][];
        dayStarts=new Instant[days.length+1];
        for(int i=0;i<days.length;i++){days[i]=directory.resolve("day-"+(i+1));dayStarts[i]=start(month.atDay(i+1));}
        dayStarts[days.length]=start(month.plusMonths(1).atDay(1));
    }

    public static MonthlyReadingWindow prepare(ClassificationRepository repository,String building,YearMonth month,Supplier<Duration> remaining) {
        return prepare(repository,building,month,remaining,DEFAULT_LIMITS,null);
    }

    public static MonthlyReadingWindow prepare(ClassificationRepository repository,String building,YearMonth month,
            LocalDate completedBefore,Supplier<Duration> remaining) {
        Instant end=start(completedBefore),monthEnd=start(month.plusMonths(1).atDay(1));
        if(end.isAfter(monthEnd))end=monthEnd;
        if(!end.isAfter(start(month.atDay(1))))throw integrity();
        return prepare(repository,building,month,remaining,DEFAULT_LIMITS,null,end);
    }

    /** 独立日快照复用同一有界扫描，只读取与当天相交的完整事实；跨日事实仍由分类规则拒绝。 */
    public static MonthlyReadingWindow prepareDay(ClassificationRepository repository,String building,LocalDate date,Supplier<Duration> remaining) {
        return prepare(repository,building,YearMonth.from(date),remaining,DEFAULT_LIMITS,null,start(date),start(date.plusDays(1)),true);
    }

    static MonthlyReadingWindow prepare(ClassificationRepository repository,String building,YearMonth month,
            Supplier<Duration> remaining,Limits limits,Path parent) {
        return prepare(repository,building,month,remaining,limits,parent,start(month.plusMonths(1).atDay(1)));
    }

    private static MonthlyReadingWindow prepare(ClassificationRepository repository,String building,YearMonth month,
            Supplier<Duration> remaining,Limits limits,Path parent,Instant scanEnd) {
        return prepare(repository,building,month,remaining,limits,parent,start(month.atDay(1)),scanEnd,false);
    }

    private static MonthlyReadingWindow prepare(ClassificationRepository repository,String building,YearMonth month,
            Supplier<Duration> remaining,Limits limits,Path parent,Instant scanStart,Instant scanEnd,boolean singleDay) {
        MonthlyReadingWindow window=null;
        try {
            window=new MonthlyReadingWindow(building,month,remaining,limits,parent,scanStart,scanEnd,singleDay);
            repository.populateMonthlyWindow(window);
            return window;
        } catch(IOException failure) {
            var error=new UncheckedIOException("Daily source preparation failed",failure);
            if(window!=null)window.closeAfterFailure(error);
            throw error;
        } catch(RuntimeException | Error failure) {
            if(window!=null)window.closeAfterFailure(failure);
            throw failure;
        }
    }

    void scan(ClassificationRepository repository) throws IOException {
        var outputs=new ArrayList<DataOutputStream>();
        var digests=new ArrayList<MessageDigest>();
        long scanStarted=System.nanoTime(),metadataCallNanos=0,payloadCallNanos=0,encodeNanos=0,spoolWriteNanos=0;
        boolean scanCompleted=false;
        String phase="initialize";
        try {
            for(Path day:days) {
                var digest=digest();digests.add(digest);
                if(Files.getFileStore(directory).supportsFileAttributeView("posix"))
                    Files.createFile(day,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                else Files.createFile(day);
                outputs.add(new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(day,StandardOpenOption.WRITE),digest))));
            }
            Instant from=scanStart,to=scanEnd,cursor=null;
            String cursorId=null;
            var identifiers=new BoundedFactIds(limits.maximumRows(),16*1024*1024,this::checkRemaining);
            identifierAllocationBytes=identifiers.allocatedPrimitiveBytes();
            while(true) {
                checkRemaining();
                phase="metadataCall";
                long callStarted=System.nanoTime();
                List<ClassificationRepository.WindowCandidate> page;
                try {page=repository.overlappingWindowCandidatesPage(building,from,to,cursor,cursorId,limits.pageSize(),remaining);}
                finally {metadataCallNanos+=System.nanoTime()-callStarted;}
                phase="metadataValidation";
                if(page.size()>limits.pageSize())throw integrity();
                metadataRows=Math.addExact(metadataRows,page.size());
                for(var row:page) {
                    checkRemaining();
                    if(row.startInclusive()==null||row.endExclusive()==null||!row.startInclusive().isBefore(to))throw integrity();
                    // Keep the overlap guard as defense in depth; SQL omits historical non-overlap before pagination.
                    if(row.endExclusive().isAfter(from)) {
                        if(!row.endExclusive().isAfter(row.startInclusive())
                                ||!row.startInclusive().equals(Instant.ofEpochMilli(row.startInclusive().toEpochMilli()))
                                ||!row.endExclusive().equals(Instant.ofEpochMilli(row.endExclusive().toEpochMilli())))throw integrity();
                        identifiers.add(row.id());candidateIds=identifiers.size();identifierBytes=identifiers.encodedBytesUsed();
                    }
                }
                if(page.size()<limits.pageSize())break;
                var last=page.getLast();
                if(cursor!=null&&(last.startInclusive().isBefore(cursor)
                        ||(last.startInclusive().equals(cursor)&&Objects.equals(last.id(),cursorId))))throw integrity();
                cursor=last.startInclusive();cursorId=last.id();
            }
            phase="identifierSort";
            identifiers.sort();
            // The complete bounded ID set is sorted once; empty filtered payload batches still advance this ordinal.
            for(int offset=0;offset<identifiers.size();offset+=limits.pageSize()) {
                phase="payloadIds";
                checkRemaining();int stop=Math.min(identifiers.size(),offset+limits.pageSize());
                var ids=new ArrayList<String>(stop-offset);var ordinals=new HashMap<String,Integer>();
                for(int i=offset;i<stop;i++) {
                    String id=identifiers.idAtSorted(i);ids.add(id);ordinals.put(id,identifiers.ordinalAtSorted(i));
                }
                payloadBatches++;
                phase="payloadCall";
                long callStarted=System.nanoTime();
                List<ClassificationRepository.WindowReading> page;
                // 调用计时包含 SQL 准备、执行、传输和 JDBC 映射，不能解释为 MySQL 执行时间。
                try {page=repository.currentApprovedWindowByIds(building,from,to,ids,remaining);}
                finally {payloadCallNanos+=System.nanoTime()-callStarted;}
                phase="payloadValidation";
                if(page.size()>ids.size())throw integrity();payloadRows=Math.addExact(payloadRows,page.size());
                for(var value:page) {
                    checkRemaining();var row=value.reading();var end=value.endExclusive();
                    Integer ordinal=ordinals.remove(row.id());
                    if(ordinal==null||row.startInclusive()==null||end==null||!end.isAfter(row.startInclusive())
                            ||!row.startInclusive().equals(Instant.ofEpochMilli(row.startInclusive().toEpochMilli()))
                            ||!end.equals(Instant.ofEpochMilli(end.toEpochMilli()))
                            ||!row.startInclusive().isBefore(to)||!end.isAfter(from)||!"APPROVED".equals(row.batchStatus()))throw integrity();
                    phase="encode";
                    long encodeStarted=System.nanoTime();
                    byte[] encoded;
                    try {encoded=encode(row);}
                    finally {encodeNanos+=System.nanoTime()-encodeStarted;}
                    phase="spoolWrite";
                    long writeStarted=System.nanoTime();
                    try {
                    for(int day=0;day<days.length;day++) {
                        Instant dayFrom=dayStarts[day],dayTo=dayStarts[day+1];
                        if(dayFrom.isBefore(scanEnd)&&dayTo.isAfter(scanStart)&&row.startInclusive().isBefore(dayTo)&&end.isAfter(dayFrom)) {
                            writtenBytes+=2L*Integer.BYTES+encoded.length;
                            if(writtenBytes>limits.maximumBytes())throw capacity();
                            outputs.get(day).writeInt(encoded.length);outputs.get(day).writeInt(ordinal);outputs.get(day).write(encoded);
                        }
                    }
                    } finally {spoolWriteNanos+=System.nanoTime()-writeStarted;}
                    spooledRows++;
                    phase="payloadValidation";
                }
            }
            phase="finalBudget";
            checkRemaining();
            scanCompleted=true;
        } finally {
            IOException failure=null;
            long closeStarted=System.nanoTime();
            for(var output:outputs)try{output.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
            long spoolCloseNanos=System.nanoTime()-closeStarted;
            // 缓冲写入包含路由、容量检查和触发的 flush；最终 flush 单列，不代表磁盘持久化时间。
            org.slf4j.LoggerFactory.getLogger(MonthlyReadingWindow.class).info(
                    "Daily source scan building={} month={} metadataRows={} candidateIds={} idBytes={} idAllocationBytes={} payloadBatches={} payloadRows={} spooledRows={} spoolBytes={} scanCompleted={} closeCompleted={} lastScanPhase={} metadataCallNanos={} payloadCallNanos={} encodeNanos={} spoolRouteWriteNanos={} spoolCloseNanos={} scanThroughCloseNanos={}",
                    building,month,metadataRows,candidateIds,identifierBytes,identifierAllocationBytes,payloadBatches,payloadRows,spooledRows,writtenBytes,
                    scanCompleted,failure==null,phase,metadataCallNanos,payloadCallNanos,encodeNanos,
                    spoolWriteNanos,spoolCloseNanos,System.nanoTime()-scanStarted);
            if(failure!=null)throw failure;
        }
        for(int i=0;i<days.length;i++)hashes[i]=digests.get(i).digest();
    }

    public List<ClassificationRepository.StoredReading> readDay(String requestedBuilding,Instant from,Instant to,Set<String> sealedFacts) {
        checkRemaining();
        LocalDate date=from.atZone(ZONE).toLocalDate();
        if(!building.equals(requestedBuilding)||!YearMonth.from(date).equals(month)
                ||!start(date).equals(from)||!start(date.plusDays(1)).equals(to)
                ||(singleDay&&(from.isBefore(scanStart)||to.isAfter(scanEnd))))throw integrity();
        var result=new ArrayList<RankedReading>();
        long retainedBytes=0;var hash=digest();
        try(var input=new DataInputStream(new DigestInputStream(new BufferedInputStream(Files.newInputStream(days[date.getDayOfMonth()-1])),hash))) {
            while(true) {
                checkRemaining();
                int first=input.read();if(first<0)break;
                int size=(first<<24)|(input.readUnsignedByte()<<16)|(input.readUnsignedByte()<<8)|input.readUnsignedByte();
                if(size<0 || size>limits.maximumRowBytes())throw integrity();
                int ordinal=input.readInt();if(ordinal<0||ordinal>=limits.maximumRows())throw integrity();
                byte[] encoded=input.readNBytes(size);if(encoded.length!=size)throw integrity();
                // 先按证据 ID 去封存，再限制将要解码驻留的字节；磁盘上限不能替代 JVM 堆上限。
                try(var idInput=new DataInputStream(new ByteArrayInputStream(encoded))) {
                    if(sealedFacts.contains(idInput.readUTF()))continue;
                }
                retainedBytes+=size;if(retainedBytes>limits.maximumRetainedBytes())throw capacity();
                var row=decode(encoded);
                // 封存去重仍在各日自己的事务和快照集合内执行；先去重，再应用原来的保留量上限。
                result.add(new RankedReading(ordinal,row));
                if(result.size()>limits.maximumRetained())throw new BusinessException(409,"Daily manual sources exceed the retained-source limit");
            }
            if(!MessageDigest.isEqual(hashes[date.getDayOfMonth()-1],hash.digest()))throw integrity();
            // Restore the original metadata (start,fact) order, not the PK-oriented physical fetch order.
            result.sort(Comparator.comparingInt(RankedReading::ordinal));
            return result.stream().map(RankedReading::reading).toList();
        } catch(IOException failure){throw new UncheckedIOException("Daily source read failed",failure);}
    }

    private byte[] encode(ClassificationRepository.StoredReading row) throws IOException {
        if(row.json()==null)throw capacity();
        byte[] json=row.json().getBytes(StandardCharsets.UTF_8);
        if(json.length>limits.maximumRowBytes())throw capacity();
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeUTF(row.id());out.writeUTF(row.batchId());out.writeInt(json.length);out.write(json);
            out.writeBoolean(row.quantity()!=null);if(row.quantity()!=null)out.writeUTF(row.quantity().toString());
            out.writeLong(row.author());out.writeUTF(row.batchStatus());out.writeLong(row.startInclusive().toEpochMilli());
        }
        if(bytes.size()>limits.maximumRowBytes())throw capacity();
        return bytes.toByteArray();
    }

    private ClassificationRepository.StoredReading decode(byte[] bytes) throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            String id=in.readUTF(),batch=in.readUTF();int size=in.readInt();
            if(size<0 || size>limits.maximumRowBytes())throw integrity();
            byte[] json=in.readNBytes(size);if(json.length!=size)throw integrity();
            BigDecimal quantity=in.readBoolean()?new BigDecimal(in.readUTF()):null;
            var row=new ClassificationRepository.StoredReading(id,batch,new String(json,StandardCharsets.UTF_8),quantity,
                    in.readLong(),in.readUTF(),Instant.ofEpochMilli(in.readLong()));
            if(in.available()!=0)throw integrity();return row;
        }
    }

    private void checkRemaining() {
        Duration budget=remaining.get();
        if(closed.get())throw new IllegalStateException("Daily source window is closed");
        if(Thread.currentThread().isInterrupted()||budget==null||budget.isZero()||budget.isNegative())
            throw new QueryTimeoutException("Daily source request budget expired");
    }
    private static Instant start(LocalDate day){return day.atStartOfDay(ZONE).toInstant();}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static BusinessException capacity(){return new BusinessException(503,"DAILY_SOURCE_CAPACITY","Daily source preparation exceeds its bounded capacity");}
    private static BusinessException integrity(){return new BusinessException(503,"DAILY_SOURCE_INTEGRITY","Daily source window integrity check failed");}
    Path directory(){return directory;}
    private void closeAfterFailure(Throwable failure){try{close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}}

    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        IOException failure=null;
        for(Path day:days)try{Files.deleteIfExists(day);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        try{Files.deleteIfExists(directory);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw new UncheckedIOException("Daily source cleanup failed",failure);
    }
}
