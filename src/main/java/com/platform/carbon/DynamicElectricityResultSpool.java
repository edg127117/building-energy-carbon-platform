package com.platform.carbon;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static com.platform.carbon.DynamicElectricityRepository.*;

/**
 * 单次计算私有的有界暂存。逐条无损压缩完整规范证据，内存只保留发布守卫所需的版本头。
 * 调用方须在全部来源复核后于同一发布事务内读取；读取异常必须回滚，结束或失败均关闭删除。
 */
final class DynamicElectricityResultSpool implements AutoCloseable {
    static final Limits DEFAULT_LIMITS=new Limits(744,MAX_RESULT_PAYLOAD_BYTES,256L*1024*1024);
    record Limits(int rows,int rowBytes,long totalBytes) { }
    record Head(String id,Instant start,Instant end,String sourceVersion,String hash,int bytes,int storedBytes) { }
    private final Path path;
    private final Limits limits;
    private final CarbonCalculationDeadline deadline;
    private final List<Head> heads=new ArrayList<>();
    private DataOutputStream output;
    private long bytes;
    private boolean closed;

    DynamicElectricityResultSpool(CarbonCalculationDeadline deadline) {
        this(deadline,DEFAULT_LIMITS,null);
    }

    DynamicElectricityResultSpool(CarbonCalculationDeadline deadline,Limits limits,Path parent) {
        this.deadline=Objects.requireNonNull(deadline);this.limits=Objects.requireNonNull(limits);
        Path created=null;
        try {
            var attributes=FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ?new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                    :new java.nio.file.attribute.FileAttribute<?>[0];
            created=parent==null?Files.createTempFile("dynamic-results-",".bin",attributes)
                    :Files.createTempFile(parent,"dynamic-results-",".bin",attributes);
            output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(created,StandardOpenOption.WRITE)));
            path=created;
        } catch(IOException failure) {
            if(created!=null)try{Files.deleteIfExists(created);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw new UncheckedIOException("动态计算暂存创建失败",failure);
        }
    }

    void append(Result result) {
        requireOpen();deadline.requireRemaining();
        if(output==null)throw new IllegalStateException("动态计算暂存已经封存");
        if(heads.size()>=limits.rows())throw capacity();
        String payload=encode(result);byte[] encoded=payload.getBytes(StandardCharsets.UTF_8);
        if(encoded.length>limits.rowBytes())throw capacity();
        byte[] compressed=compress(encoded);
        // 压缩没有收益时保留原帧，最坏磁盘成本仍不超过原JSON加帧头，不依赖压缩比承诺容量。
        byte[] stored=compressed.length<encoded.length?compressed:encoded;
        long next=bytes+Integer.BYTES+stored.length;
        if(next>limits.totalBytes())
            throw DynamicElectricityCore.problem("DYNAMIC_CAPACITY","动态计算证据超过暂存上限");
        try {output.writeInt(stored.length);output.write(stored);}
        catch(IOException failure){throw new UncheckedIOException("动态计算暂存写入失败",failure);}
        heads.add(new Head(result.id(),result.start(),result.end(),result.sourceVersion(),hash(payload),encoded.length,stored.length));
        bytes=next;
    }

    List<Head> heads(){return List.copyOf(heads);}
    List<String> ids(){return heads.stream().map(Head::id).toList();}

    /** 压缩与展开长度均由内存头限定；必须完整解压、无尾随数据且原摘要一致，才交给发布操作。 */
    void visit(BiConsumer<String,String> consumer) {
        requireOpen();seal();
        try(var input=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for(var head:heads) {
                deadline.requireRemaining();
                int size=input.readInt();
                if(size<0 || size>limits.rowBytes() || size!=head.storedBytes()
                        || head.bytes()<0 || head.bytes()>limits.rowBytes())throw integrity();
                byte[] stored=input.readNBytes(size);
                if(stored.length!=size)throw integrity();
                byte[] encoded=size==head.bytes()?stored:expand(stored,head.bytes());
                String payload=new String(encoded,StandardCharsets.UTF_8);
                if(!hash(payload).equals(head.hash()))throw integrity();
                consumer.accept(payload,head.hash());
            }
            if(input.read()!=-1)throw integrity();
        } catch(IOException failure){throw new UncheckedIOException("动态计算暂存读取失败",failure);}
    }

    private void seal() {
        if(output==null)return;
        try{output.close();output=null;}
        catch(IOException failure){throw new UncheckedIOException("动态计算暂存封存失败",failure);}
    }
    private void requireOpen(){if(closed)throw new IllegalStateException("动态计算暂存已经关闭");}
    private static IllegalStateException integrity(){return new IllegalStateException("动态计算暂存证据不一致");}
    private static RuntimeException capacity(){return DynamicElectricityCore.problem("DYNAMIC_CAPACITY","动态计算证据超过暂存上限");}

    private byte[] compress(byte[] encoded) {
        var compressor=new Deflater(Deflater.BEST_SPEED);
        try {
            compressor.setInput(encoded);compressor.finish();
            var compressed=new ByteArrayOutputStream(Math.min(encoded.length,8192));
            byte[] buffer=new byte[8192];
            while(!compressor.finished()) {
                deadline.requireRemaining();
                int count=compressor.deflate(buffer);
                // 即使是不可压缩的输入，也不增长到超过原始帧大小。
                if(count==0 || compressed.size()+count>=encoded.length)return encoded;
                compressed.write(buffer,0,count);
            }
            return compressed.toByteArray();
        } finally {compressor.end();}
    }

    private byte[] expand(byte[] compressed,int size) {
        var inflater=new Inflater();
        try {
            inflater.setInput(compressed);byte[] result=new byte[size];int offset=0;
            while(!inflater.finished() && offset<size) {
                deadline.requireRemaining();
                int count=inflater.inflate(result,offset,Math.min(8192,size-offset));
                if(count==0)throw integrity();
                offset+=count;
            }
            if(offset!=size || !inflater.finished() || inflater.getRemaining()!=0)throw integrity();
            return result;
        } catch(DataFormatException failure){throw integrity();}
        finally {inflater.end();}
    }

    @Override public void close() {
        if(closed)return;closed=true;IOException failure=null;
        if(output!=null)try{output.close();}catch(IOException error){failure=error;}
        output=null;
        try{Files.deleteIfExists(path);}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        heads.clear();
        if(failure!=null)throw new UncheckedIOException("动态计算暂存清理失败",failure);
    }
}
