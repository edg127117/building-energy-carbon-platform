package com.platform.iot.daikin.client;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpHandler;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.*;

/** 仅测试使用：临时证书只被当前客户端信任，实际生产 transport/TLS 校验保持启用。 */
public final class DaikinLoopbackHttps implements AutoCloseable {
    private final HttpsServer server;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Path directory;
    public final DaikinHttpTransport transport;
    public final URI uri;

    public DaikinLoopbackHttps(HttpHandler handler) throws Exception {
        directory=Files.createTempDirectory(Path.of("target"),"daikin-loopback-");
        Path store=directory.resolve("test.p12");
        String keytool=Path.of(System.getProperty("java.home"),"bin","keytool"+(System.getProperty("os.name").startsWith("Windows")?".exe":"")).toString();
        var process=new ProcessBuilder(keytool,"-genkeypair","-alias","loopback","-keyalg","RSA","-keysize","2048",
                "-storetype","PKCS12","-keystore",store.toString(),"-storepass","loopback-test-only",
                "-dname","CN=localhost","-ext","SAN=IP:127.0.0.1,DNS:localhost","-validity","2","-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS) || process.exitValue()!=0) throw new IllegalStateException("本地测试证书生成失败");
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(store)) { keys.load(input,"loopback-test-only".toCharArray()); }
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys,"loopback-test-only".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        var ssl=SSLContext.getInstance("TLS"); ssl.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        server=HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.setExecutor(workers); server.createContext("/",handler); server.start();
        uri=URI.create("https://127.0.0.1:"+server.getAddress().getPort());
        transport=new DaikinHttpTransport.Jdk(HttpClient.newBuilder().sslContext(ssl)
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    public void close() throws Exception {
        server.stop(0); workers.shutdownNow(); workers.awaitTermination(5,TimeUnit.SECONDS);
        Files.deleteIfExists(directory.resolve("test.p12")); Files.deleteIfExists(directory.resolve("keytool.log")); Files.deleteIfExists(directory);
    }
}
