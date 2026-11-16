package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import okio.Buffer;

public class NetworkOperationsTest {
    private static final Operations.Checkpoints NOOP=new Operations.Checkpoints(){public void checkpoint(String s){}public void progress(String s){}};
    static final class Sink implements NetworkOperations.Gateway {
        MessageDigest digest;long length;int largest;JSONObject cached;String state="execute";boolean cancel;Runnable created=()->{};
        public JSONObject begin(String effect,String execution,JSONObject request)throws Exception{return cached!=null?new JSONObject().put("state","completed").put("response",cached):new JSONObject().put("state",state).put("request_sha256","a".repeat(64));}
        public void finish(String effect,String execution,String hash,JSONObject response,JSONArray ids){cached=response;}
        public String create(String key,String name,String type)throws Exception{digest=MessageDigest.getInstance("SHA-256");length=0;created.run();return "upload";}
        public void chunk(String upload,long offset,byte[] bytes,int n){assertEquals(length,offset);assertTrue(n<=NetworkOperations.CHUNK_BYTES);largest=Math.max(largest,n);digest.update(bytes,0,n);length+=n;}
        public JSONObject seal(String upload,long n,String hash)throws Exception{assertEquals(length,n);assertEquals(Operations.hex(digest.digest()),hash);return new JSONObject().put("artifact_id","blob").put("length",n).put("sha256",hash).put("media_type","application/octet-stream");}
        public void cancel(){cancel=true;}
    }
    static JSONObject request(String url)throws Exception{return new JSONObject().put("effect_id","test").put("url",url);}
    @Test public void streamsSixtyFourMiBWithOneBoundedWorkerBufferAndNoFiles()throws Exception {
        long size=64L*1024*1024;byte[] block=new byte[16384];for(int i=0;i<block.length;i++)block[i]=(byte)i;
        MessageDigest expected=MessageDigest.getInstance("SHA-256");Buffer source=new Buffer();for(long n=0;n<size;n+=block.length){expected.update(block);source.write(block);}
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(source));server.start();Sink sink=new Sink();
            try(NetworkOperations net=new NetworkOperations(sink,()->false,NOOP,null)) {
                JSONObject result=net.request(request(server.url("/binary").toString()));assertEquals(size,result.getJSONObject("artifact").getLong("length"));assertEquals(Operations.hex(expected.digest()),result.getJSONObject("artifact").getString("sha256"));assertEquals(NetworkOperations.CHUNK_BYTES,sink.largest);assertEquals(1,net.artifactIds().length());
            }
            RecordedRequest sent=server.takeRequest();assertEquals("identity",sent.getHeader("Accept-Encoding"));assertNull(sent.getHeader("Authorization"));
        }
    }
    @Test public void inlineOverflowAndInvalidUtf8PreserveEveryOriginalByte()throws Exception {
        byte[] bytes=new byte[]{1,2,3,4,5,6,(byte)0xff};
        try(MockWebServer server=new MockWebServer()) {
            server.start();for(int max:new int[]{3,32}) {
                server.enqueue(new MockResponse().setBody(new Buffer().write(bytes)));Sink sink=new Sink();
                try(NetworkOperations net=new NetworkOperations(sink,()->false,NOOP,null)) {
                    JSONObject spec=request(server.url("/data").toString()).put("response",new JSONObject().put("mode","text").put("max_bytes",max));JSONObject response=net.request(spec);
                    assertEquals(max==3?"inline_limit_exceeded":"invalid_utf8",response.getJSONObject("body_error").getString("code"));assertEquals(bytes.length,response.getJSONObject("artifact").getLong("length"));assertEquals(Operations.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),response.getJSONObject("artifact").getString("sha256"));
                }
            }
        }
    }
    @Test public void strictJsonFallbackAndRealPatchRequest()throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(422).setBody("{unquoted:1}"));server.start();
            try(NetworkOperations net=new NetworkOperations(new Sink(),()->false,NOOP,null)) {
                JSONObject response=net.request(request(server.url("/patch").toString()).put("method","PATCH").put("body_text","hello").put("response",new JSONObject().put("mode","json").put("max_bytes",100)));
                assertEquals(422,response.getInt("status"));assertEquals("invalid_json",response.getJSONObject("body_error").getString("code"));assertTrue(response.has("artifact"));
            }
            RecordedRequest sent=server.takeRequest();assertEquals("PATCH",sent.getMethod());assertEquals("hello",sent.getBody().readUtf8());
        }
    }
    @Test public void completedEffectAvoidsAnotherVendorRequestAndUncertainNeverExecutes()throws Exception {
        Sink sink=new Sink();sink.cached=new JSONObject().put("status",201).put("artifact",new JSONObject().put("artifact_id","saved"));
        try(NetworkOperations net=new NetworkOperations(sink,()->false,NOOP,null)){assertEquals(201,net.request(request("http://127.0.0.1:1/unused")).getInt("status"));assertEquals("saved",net.artifactIds().getString(0));}
        sink.cached=null;sink.state="uncertain";
        try(NetworkOperations net=new NetworkOperations(sink,()->false,NOOP,null)){try{net.request(request("http://127.0.0.1:1/unused").put("method","POST"));fail();}catch(IllegalStateException expected){assertEquals("effect_uncertain",expected.getMessage());}}
    }
    @Test public void redirectPoliciesStripAllSecretsAndDoNotReplayUnsafeCrossOrigin()throws Exception {
        try(MockWebServer target=new MockWebServer();MockWebServer source=new MockWebServer()) {
            target.start();source.start();target.enqueue(new MockResponse().setResponseCode(204));
            source.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",target.url("/target")));
            try(NetworkOperations net=new NetworkOperations(new Sink(),()->false,NOOP,null)){assertEquals(204,net.request(request(source.url("/get").toString()).put("headers",new JSONObject().put("X-Api-Key","vendor-only").put("Authorization","vendor-secret"))).getInt("status"));}
            RecordedRequest sent=target.takeRequest();assertNull(sent.getHeader("X-Api-Key"));assertNull(sent.getHeader("Authorization"));
            source.enqueue(new MockResponse().setResponseCode(307).setHeader("Location",target.url("/target")));
            try(NetworkOperations net=new NetworkOperations(new Sink(),()->false,NOOP,null)){assertEquals(307,net.request(request(source.url("/post").toString()).put("method","POST")).getInt("status"));}
            assertEquals(1,target.getRequestCount());
            source.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",target.url("/target")));
            try(NetworkOperations net=new NetworkOperations(new Sink(),()->false,NOOP,new JSONObject().put("origins",new JSONArray().put(NetworkOperations.origin(source.url("/")))))){try{net.request(request(source.url("/get").toString()));fail();}catch(SecurityException expected){assertEquals("network_policy_denied_origins",expected.getMessage());}}
        }
    }
    @Test public void cancelUnblocksAStalledHttpBodyWithoutJobDeadline()throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            CountDownLatch entered=new CountDownLatch(1);server.enqueue(new MockResponse().setBody("stalled").setBodyDelay(2,TimeUnit.SECONDS));server.start();Sink sink=new Sink();sink.created=entered::countDown;
            AtomicBoolean cancelled=new AtomicBoolean();NetworkOperations net=new NetworkOperations(sink,cancelled::get,NOOP,null);
            CompletableFuture<Throwable> future=CompletableFuture.supplyAsync(()->{try{net.request(request(server.url("/stall").toString()));return null;}catch(Throwable e){return e;}});
            try{assertTrue(entered.await(10,TimeUnit.SECONDS));cancelled.set(true);net.cancel();assertTrue(future.get(1,TimeUnit.SECONDS) instanceof java.util.concurrent.CancellationException);}
            finally{net.close();}
        }
    }
    @Test public void thirdArgumentAddsNetworkWithoutChangingResumeOrLegacyCallbacks()throws Exception {
        Object result=Operations.javascript("function run(input,resume,ctx){ctx.checkpoint({n:resume.n});ctx.progress({ok:true});return ctx.http.request({effect_id:'x',url:input.url});}",new JSONObject().put("url","https://source.example.invalid"),"{\"n\":7}",()->false,NOOP,spec->new JSONObject().put("status",200).put("body_text",spec.getString("url")));
        assertEquals("https://source.example.invalid",((JSONObject)result).getString("body_text"));
    }
}
