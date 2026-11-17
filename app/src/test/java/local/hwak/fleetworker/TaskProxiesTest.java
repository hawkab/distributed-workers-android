package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class TaskProxiesTest {
    private static final Operations.Checkpoints NOOP=new Operations.Checkpoints(){public void checkpoint(String s){}public void progress(String s){}};
    private static TaskProxies pool(JSONObject config)throws Exception{return new TaskProxies(config,new JSONObject(),new JSONObject(),v->{});}
    private static JSONObject config(String url)throws Exception{return new JSONObject().put("mode","required").put("servers",new JSONArray().put(new JSONObject().put("url",url)));}
    private static NetworkOperations network(NetworkOperationsTest.Sink sink,TaskProxies proxies){return new NetworkOperations(sink,()->false,NOOP,null,new JSONArray(),v->{},proxies);}
    @Test public void forwardsAbsoluteRequestViaHttpProxyWithoutOriginDnsOrCredentialLeak()throws Exception {
        try(MockWebServer proxy=new MockWebServer()) {
            proxy.enqueue(new MockResponse().setBody("proxied"));proxy.start();
            TaskProxies proxies=pool(config(proxy.url("/").toString()));
            try(NetworkOperations net=network(new NetworkOperationsTest.Sink(),proxies)) {
                JSONObject spec=NetworkOperationsTest.request("http://unresolvable.example.invalid/entity").put("headers",new JSONObject().put("Authorization","origin-only"));
                JSONObject response=net.request(spec);assertEquals(200,response.getInt("status"));assertEquals(7,response.getJSONObject("artifact").getLong("length"));
            }
            RecordedRequest request=proxy.takeRequest();assertEquals("GET http://unresolvable.example.invalid/entity HTTP/1.1",request.getRequestLine());assertNull(request.getHeader("Proxy-Authorization"));assertEquals("origin-only",request.getHeader("Authorization"));
        }
    }
    @Test public void requiredMissingRouteDoesNotTryOriginAndCompletedEffectStillReplays()throws Exception {
        try(MockWebServer origin=new MockWebServer()) {
            origin.start();NetworkOperationsTest.Sink sink=new NetworkOperationsTest.Sink();TaskProxies proxies=pool(new JSONObject().put("mode","required"));
            try(NetworkOperations net=network(sink,proxies)){try{net.request(NetworkOperationsTest.request(origin.url("/never-direct").toString()));fail();}catch(NetworkOperations.Retryable expected){}}
            assertEquals(0,origin.getRequestCount());sink.cached=new JSONObject().put("status",204);
            try(NetworkOperations net=network(sink,proxies)){assertEquals(204,net.request(NetworkOperationsTest.request(origin.url("/replay").toString())).getInt("status"));}
            assertEquals(0,origin.getRequestCount());
        }
    }
    @Test public void missingProxyDoesNotReserveNewNeverIntentAndCompletedStatusStillValidatesRequestHash()throws Exception {
        AtomicInteger begins=new AtomicInteger(),uploads=new AtomicInteger();TaskProxies required=pool(new JSONObject().put("mode","required"));
        NetworkOperationsTest.Sink fresh=new NetworkOperationsTest.Sink(){@Override public JSONObject begin(String e,String x,JSONObject r)throws Exception{begins.incrementAndGet();return super.begin(e,x,r);}@Override public String create(String k,String n,String m)throws Exception{uploads.incrementAndGet();return super.create(k,n,m);}};
        try(NetworkOperations net=network(fresh,required)){try{net.request(NetworkOperationsTest.request("http://127.0.0.1:1/no-source").put("method","POST").put("replay","never"));fail();}catch(NetworkOperations.Retryable expected){}}
        assertEquals(0,begins.get());assertEquals(0,uploads.get());
        fresh.cached=new JSONObject().put("status",202);
        try(NetworkOperations net=network(fresh,required)){assertEquals(202,net.request(NetworkOperationsTest.request("http://127.0.0.1:1/no-source")).getInt("status"));}assertEquals(1,begins.get());
        NetworkOperationsTest.Sink changed=new NetworkOperationsTest.Sink(){@Override public JSONObject status(String effect)throws Exception{return new JSONObject().put("state","completed");}@Override public JSONObject begin(String e,String x,JSONObject r){throw new IllegalArgumentException("effect_request_hash_mismatch");}};
        try(NetworkOperations net=network(changed,required)){try{net.request(NetworkOperationsTest.request("http://127.0.0.1:1/no-source"));fail();}catch(IllegalArgumentException expected){assertEquals("effect_request_hash_mismatch",expected.getMessage());}}
    }
    @Test public void requiredTransportFailureDoesNotFallBackDirectOrRepeatUnsafeEffect()throws Exception {
        int closed;try(ServerSocket port=new ServerSocket(0)){closed=port.getLocalPort();}
        try(MockWebServer origin=new MockWebServer()) {
            origin.start();TaskProxies proxies=pool(config("http://127.0.0.1:"+closed));
            try(NetworkOperations net=network(new NetworkOperationsTest.Sink(),proxies)){try{net.request(NetworkOperationsTest.request(origin.url("/post").toString()).put("method","POST"));fail();}catch(IllegalStateException expected){assertEquals("effect_uncertain",expected.getMessage());}}
            assertEquals(0,origin.getRequestCount());
        }
    }
    @Test public void sourceCandidatesNeedVerifiedCountryAndHealthIsPersistentHashedMetadata()throws Exception {
        JSONObject config=new JSONObject().put("mode","required").put("required_country","RU");JSONObject health=new JSONObject();AtomicReference<JSONObject> persisted=new AtomicReference<>();
        JSONObject cache=new JSONObject().put("candidates",TaskProxies.candidates("[{\"proxy\":\"http://127.0.0.1:8080\",\"geolocation\":{\"country\":\"RU\"}}]",""));
        try{new TaskProxies(config,cache,health,persisted::set).select(0);fail();}catch(java.io.IOException expected){}
        JSONObject verified=new JSONObject().put("url","http://127.0.0.1:8080").put("verified_at","2026-11-17T00:00:00Z").put("exit_ip","192.0.2.1").put("exit_country","DE");cache.put("verified",new JSONArray().put(verified));
        try{new TaskProxies(config,cache,health,persisted::set).select(0);fail();}catch(java.io.IOException expected){}
        verified.put("exit_country","RU");TaskProxies p=new TaskProxies(config,cache,health,persisted::set);TaskProxies.Endpoint e=p.select(0);p.failed(e);
        assertEquals(1,persisted.get().getJSONObject(e.hash).getInt("failures"));assertFalse(persisted.get().toString().contains("127.0.0.1"));
        try{new TaskProxies(config,cache,persisted.get(),v->{}).select(System.currentTimeMillis());fail();}catch(java.io.IOException expected){}
        p.succeeded(e);assertNotNull(p.select(System.currentTimeMillis()));assertEquals(0,persisted.get().getJSONObject(e.hash).getInt("failures"));
    }
    @Test public void normalizationAndSourceFormatsAreSharedAndUnsupportedTlsProxyFailsClosed()throws Exception {
        TaskProxies.Endpoint e=new TaskProxies.Endpoint(new JSONObject().put("url","http://EXAMPLE.invalid").put("username","u").put("password","p"));assertEquals("http://example.invalid:80",e.url);
        assertEquals(Operations.hex(MessageDigest.getInstance("SHA-256").digest("http://example.invalid:80\nu\np".getBytes(StandardCharsets.UTF_8))),e.hash);
        assertEquals("http://[::1]:80",new TaskProxies.Endpoint(new JSONObject().put("url","http://[::1]")).url);
        for(String invalid:new String[]{"https://example.invalid:443","http://user:password@example.invalid:80","ftp://example.invalid:21"})try{new TaskProxies.Endpoint(new JSONObject().put("url",invalid));fail();}catch(IllegalArgumentException expected){}
        assertEquals(1,TaskProxies.candidates("# list\n127.0.0.1:8080\n","http").length());assertEquals(0,TaskProxies.candidates("127.0.0.1:8080","").length());
        assertEquals(2,ProxyConfiguration.parseSources("# source\nhttps://example.invalid/feed\nhttps://example.invalid/another\n").length());
        assertEquals("socks5",ProxyConfiguration.parseSources("[{\"url\":\"https://example.invalid/list\",\"scheme\":\"socks5\"}]").getJSONObject(0).getString("scheme"));
        try{ProxyConfiguration.parseSources("http://example.invalid/untrusted");fail();}catch(SecurityException|IllegalArgumentException expected){}
    }
    @Test public void offIgnoresSystemProxyAndProxyAuthorizationIsTransportManaged()throws Exception {
        ProxySelector previous=ProxySelector.getDefault();AtomicInteger invoked=new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector(){public List<Proxy> select(URI uri){invoked.incrementAndGet();throw new AssertionError("system proxy must not be used");}public void connectFailed(URI u,java.net.SocketAddress a,java.io.IOException e){}});
        try(MockWebServer origin=new MockWebServer()) {
            origin.start();origin.enqueue(new MockResponse().setBody("direct"));
            try(NetworkOperations net=network(new NetworkOperationsTest.Sink(),TaskProxies.direct())){assertEquals(200,net.request(NetworkOperationsTest.request(origin.url("/").toString())).getInt("status"));}
            assertEquals(0,invoked.get());
            try(NetworkOperations net=network(new NetworkOperationsTest.Sink(),TaskProxies.direct())){try{net.request(NetworkOperationsTest.request(origin.url("/").toString()).put("headers",new JSONObject().put("Proxy-Authorization","secret")));fail();}catch(IllegalArgumentException expected){assertEquals("transport_manages_header",expected.getMessage());}}
        }finally{ProxySelector.setDefault(previous);}
    }
    private static void copy(Socket from,Socket to){try{from.getInputStream().transferTo(to.getOutputStream());to.shutdownOutput();}catch(Exception ignored){}}
    private static String head(Socket socket)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();InputStream in=socket.getInputStream();while(true){int n=in.read();if(n<0)throw new java.io.EOFException();b.write(n);if(b.size()>=4){byte[] v=b.toByteArray();int l=v.length;if(v[l-4]==13&&v[l-3]==10&&v[l-2]==13&&v[l-1]==10)return new String(v,StandardCharsets.ISO_8859_1);}}}
    @Test public void httpConnectUsesProxyOnlyCredentialsAndKeepsOriginTlsVerification()throws Exception {
        HeldCertificate cert=new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(cert).build();HandshakeCertificates clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        try(MockWebServer origin=new MockWebServer();ServerSocket listener=new ServerSocket(0)) {
            origin.useHttps(serverTls.sslSocketFactory(),false);origin.start();origin.enqueue(new MockResponse().setBody("secure"));AtomicReference<String> connect=new AtomicReference<>();
            CompletableFuture<Void> tunnel=CompletableFuture.runAsync(()->{try(Socket incoming=listener.accept();Socket target=new Socket("127.0.0.1",origin.getPort())){connect.set(head(incoming));incoming.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));CompletableFuture<Void> upstream=CompletableFuture.runAsync(()->copy(incoming,target));copy(target,incoming);upstream.join();}catch(Exception e){throw new RuntimeException(e);}});
            JSONObject config=config("http://127.0.0.1:"+listener.getLocalPort());config.getJSONArray("servers").getJSONObject(0).put("username","proxy-user").put("password","proxy-password");
            OkHttpClient trusted=NetworkOperations.defaultClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager()).build();
            try(NetworkOperations net=new NetworkOperations(new NetworkOperationsTest.Sink(),()->false,NOOP,null,new JSONArray(),v->{},pool(config),trusted)){assertEquals(200,net.request(NetworkOperationsTest.request(origin.url("/secure").toString()).put("headers",new JSONObject().put("Authorization","origin-secret"))).getInt("status"));}
            RecordedRequest request=origin.takeRequest();assertEquals("origin-secret",request.getHeader("Authorization"));assertNull(request.getHeader("Proxy-Authorization"));assertTrue(connect.get().startsWith("CONNECT localhost:"));assertTrue(connect.get().contains("Proxy-Authorization: Basic "));assertFalse(connect.get().contains("origin-secret"));tunnel.get(5,TimeUnit.SECONDS);
        }
    }
    private static int read(InputStream in)throws Exception{int n=in.read();if(n<0)throw new java.io.EOFException();return n;}
    @Test public void proxyCannotReplaceOriginTlsCertificateAndNoDirectFallbackOccurs()throws Exception {
        HeldCertificate certificate=new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        try(MockWebServer origin=new MockWebServer();ServerSocket listener=new ServerSocket(0)) {
            origin.useHttps(serverTls.sslSocketFactory(),false);origin.start();
            CompletableFuture<Void> tunnel=CompletableFuture.runAsync(()->{try(Socket incoming=listener.accept();Socket target=new Socket("127.0.0.1",origin.getPort())){head(incoming);incoming.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));CompletableFuture<Void> upstream=CompletableFuture.runAsync(()->copy(incoming,target));copy(target,incoming);upstream.join();}catch(Exception e){throw new RuntimeException(e);}});
            try(NetworkOperations net=network(new NetworkOperationsTest.Sink(),pool(config("http://127.0.0.1:"+listener.getLocalPort())))){try{net.request(NetworkOperationsTest.request(origin.url("/not-trusted").toString()));fail();}catch(NetworkOperations.Retryable expected){}}
            assertEquals(0,origin.getRequestCount());tunnel.get(5,TimeUnit.SECONDS);
        }
    }
    @Test public void failedProxyRotatesToNextHealthyProxyAndMetadataWriteFailureCannotLoseResult()throws Exception {
        JSONObject config=new JSONObject().put("mode","required").put("servers",new JSONArray().put(new JSONObject().put("url","http://127.0.0.1:8080")).put(new JSONObject().put("url","http://127.0.0.1:8081")));
        TaskProxies p=new TaskProxies(config,new JSONObject(),new JSONObject(),v->{throw new IllegalStateException("disk_failed");});TaskProxies.Endpoint first=p.select(System.currentTimeMillis());p.failed(first);assertEquals(8081,p.select(System.currentTimeMillis()).port);p.succeeded(first);
        assertEquals(8080,p.select(System.currentTimeMillis()).port);
    }
    @Test public void controlUpdateAndAnonymousSourceTransportAreActuallyDirectWithSystemProxyConfigured()throws Exception {
        HeldCertificate cert=new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(cert).build();HandshakeCertificates clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        ProxySelector previous=ProxySelector.getDefault();javax.net.ssl.SSLSocketFactory previousTls=javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory();AtomicInteger selected=new AtomicInteger();
        try(MockWebServer server=new MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(),false);server.start();for(int i=0;i<3;i++)server.enqueue(new MockResponse().setBody("{}"));
            ProxySelector.setDefault(new ProxySelector(){public List<Proxy> select(URI uri){selected.incrementAndGet();throw new AssertionError("control proxy leak");}public void connectFailed(URI u,java.net.SocketAddress a,java.io.IOException e){}});javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory());
            javax.net.ssl.HttpsURLConnection control=Api.openDirect(server.url("/control").toString());control.setRequestProperty("Authorization","Bearer control-only");try(InputStream in=control.getInputStream()){assertEquals("{}",Api.read(in));}finally{control.disconnect();}
            assertEquals("{}",new PublicReleaseTransport().text(server.url("/release").toString()));assertEquals("{}",ProxyConfiguration.download(server.url("/feed").toString()));
            assertEquals(0,selected.get());RecordedRequest first=server.takeRequest();assertEquals("Bearer control-only",first.getHeader("Authorization"));for(int i=0;i<2;i++){RecordedRequest anonymous=server.takeRequest();assertNull(anonymous.getHeader("Authorization"));assertNull(anonymous.getHeader("Proxy-Authorization"));}
        }finally{ProxySelector.setDefault(previous);javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(previousTls);}
    }
    @Test public void socks5RemoteDnsAndPerSocketAuthenticationStreamWithoutGlobalAuthenticator()throws Exception {
        Object previous=Authenticator.class.getMethod("getDefault").invoke(null);AtomicReference<String> host=new AtomicReference<>(),user=new AtomicReference<>();
        try(ServerSocket listener=new ServerSocket(0)) {
            CompletableFuture<Void> response=CompletableFuture.runAsync(()->{try(Socket socket=listener.accept()){
                InputStream in=socket.getInputStream();assertEquals(5,read(in));assertEquals(1,read(in));assertEquals(2,read(in));socket.getOutputStream().write(new byte[]{5,2});
                assertEquals(1,read(in));user.set(new String(in.readNBytes(read(in)),StandardCharsets.UTF_8));assertEquals("socks-secret",new String(in.readNBytes(read(in)),StandardCharsets.UTF_8));socket.getOutputStream().write(new byte[]{1,0});
                assertEquals(5,read(in));assertEquals(1,read(in));assertEquals(0,read(in));assertEquals(3,read(in));host.set(new String(in.readNBytes(read(in)),StandardCharsets.US_ASCII));read(in);read(in);socket.getOutputStream().write(new byte[]{5,0,0,1,127,0,0,1,0,80});
                assertFalse(head(socket).contains("socks-secret"));socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nSOCK".getBytes(StandardCharsets.US_ASCII));
            }catch(Exception e){throw new RuntimeException(e);}});
            JSONObject config=config("socks5h://127.0.0.1:"+listener.getLocalPort());config.getJSONArray("servers").getJSONObject(0).put("username","socks-user").put("password","socks-secret");
            try(NetworkOperations net=network(new NetworkOperationsTest.Sink(),pool(config))){JSONObject result=net.request(NetworkOperationsTest.request("http://unresolvable.example.invalid/entity"));assertEquals(4,result.getJSONObject("artifact").getLong("length"));}
            response.get(5,TimeUnit.SECONDS);assertEquals("unresolvable.example.invalid",host.get());assertEquals("socks-user",user.get());assertSame(previous,Authenticator.class.getMethod("getDefault").invoke(null));
        }
    }
    @Test public void socks5TunnelPreservesOriginTlsAndStreamsHttpsResponse()throws Exception {
        HeldCertificate cert=new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();HandshakeCertificates serverTls=new HandshakeCertificates.Builder().heldCertificate(cert).build();HandshakeCertificates clientTls=new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        try(MockWebServer origin=new MockWebServer();ServerSocket listener=new ServerSocket(0)) {
            origin.useHttps(serverTls.sslSocketFactory(),false);origin.start();origin.enqueue(new MockResponse().setBody("socks-tls"));
            CompletableFuture<Void> response=CompletableFuture.runAsync(()->{try(Socket socket=listener.accept();Socket target=new Socket("127.0.0.1",origin.getPort())) {
                InputStream in=socket.getInputStream();assertEquals(5,read(in));assertEquals(1,read(in));assertEquals(0,read(in));socket.getOutputStream().write(new byte[]{5,0});
                assertEquals(5,read(in));assertEquals(1,read(in));assertEquals(0,read(in));assertEquals(3,read(in));assertEquals("localhost",new String(in.readNBytes(read(in)),StandardCharsets.US_ASCII));read(in);read(in);socket.getOutputStream().write(new byte[]{5,0,0,1,127,0,0,1,0,80});
                CompletableFuture<Void> upstream=CompletableFuture.runAsync(()->copy(socket,target));copy(target,socket);upstream.join();
            }catch(Exception e){throw new RuntimeException(e);}});
            OkHttpClient trusted=NetworkOperations.defaultClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager()).build();
            try(NetworkOperations net=new NetworkOperations(new NetworkOperationsTest.Sink(),()->false,NOOP,null,new JSONArray(),v->{},pool(config("socks5h://127.0.0.1:"+listener.getLocalPort())),trusted)) {
                assertEquals(9,net.request(NetworkOperationsTest.request(origin.url("/socks-tls").toString())).getJSONObject("artifact").getLong("length"));
            }
            assertNull(origin.takeRequest().getHeader("Proxy-Authorization"));response.get(5,TimeUnit.SECONDS);
        }
    }
}
