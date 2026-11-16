package local.hwak.fleetworker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/** The portable synchronous HTTP host. Only control JSON and one bounded chunk live locally. */
final class NetworkOperations implements Operations.Network, AutoCloseable {
    static final int CHUNK_BYTES=1024*1024;
    static final class Retryable extends java.io.IOException { Retryable(){super("network_transport_retry");} }
    interface Gateway {
        JSONObject begin(String effect,String execution,JSONObject request)throws Exception;
        void finish(String effect,String execution,String requestHash,JSONObject response,JSONArray artifacts)throws Exception;
        String create(String key,String name,String mediaType)throws Exception;
        void chunk(String upload,long offset,byte[] bytes,int length)throws Exception;
        JSONObject seal(String upload,long length,String hash)throws Exception;
        default boolean inlineFits(String execution,String requestHash,JSONObject response,JSONArray artifacts)throws Exception{return true;}
        void cancel();
    }
    private final Gateway gateway;
    private final BooleanSupplier cancelled;
    private final Operations.Checkpoints checkpoints;
    private final JSONObject policy;
    private final OkHttpClient clientPool=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(0,TimeUnit.MILLISECONDS).readTimeout(0,TimeUnit.MILLISECONDS).writeTimeout(0,TimeUnit.MILLISECONDS).connectTimeout(15000,TimeUnit.MILLISECONDS).build();
    private final Set<String> artifacts=new LinkedHashSet<>();
    private final java.util.function.Consumer<JSONArray> artifactPersistence;
    private volatile Call activeCall;
    NetworkOperations(Gateway gateway,BooleanSupplier cancelled,Operations.Checkpoints checkpoints,JSONObject policy) {
        this(gateway,cancelled,checkpoints,policy,new JSONArray(),value->{});
    }
    NetworkOperations(Gateway gateway,BooleanSupplier cancelled,Operations.Checkpoints checkpoints,JSONObject policy,JSONArray retained,java.util.function.Consumer<JSONArray> artifactPersistence) {
        this.gateway=gateway;this.cancelled=cancelled;this.checkpoints=checkpoints;this.policy=policy;
        this.artifactPersistence=artifactPersistence;
        for(int i=0;i<retained.length();i++){String id=retained.optString(i,"");if(!id.isEmpty())artifacts.add(id);}
    }
    JSONArray artifactIds(){JSONArray result=new JSONArray();for(String id:artifacts)result.put(id);return result;}
    JSONObject resultArtifact(String json,String attempt)throws Exception {
        JSONObject artifact;try(JsonResultStream input=new JsonResultStream(json)){artifact=stream(input,"result/"+attempt+"/"+JsonResultStream.sha256(json),"result.json","application/json");}
        return new JSONObject().put("artifact",artifact).put("result_format","json").put("body_error",new JSONObject().put("code","control_budget_exceeded"));
    }
    void cancel(){Call call=activeCall;if(call!=null)call.cancel();gateway.cancel();}
    @Override public void close(){cancel();clientPool.connectionPool().evictAll();clientPool.dispatcher().executorService().shutdown();}
    private void check(){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("job_cancelled");}
    @Override public JSONObject request(JSONObject original)throws Exception {
        check();JSONObject spec=new JSONObject(original.toString());
        String effect=spec.optString("effect_id","request");if(effect.isEmpty())throw new IllegalArgumentException("stable_effect_id_required");
        String method=spec.optString("method","GET").toUpperCase(Locale.ROOT);
        if(!method.matches("[!#$%&'*+.^_`|~0-9A-Z-]+")||method.equals("CONNECT"))throw new IllegalArgumentException("unsupported_http_method");
        JSONObject options=spec.optJSONObject("response");if(options==null)options=new JSONObject();
        String mode=options.optString("mode","artifact");
        if(!mode.equals("artifact")&&!mode.equals("text")&&!mode.equals("json"))throw new IllegalArgumentException("invalid_response_mode");
        String redirect=spec.optString("redirect","follow");
        if(!redirect.equals("follow")&&!redirect.equals("manual"))throw new IllegalArgumentException("invalid_redirect_policy");
        long max=options.optLong("max_bytes",-1);
        if(!mode.equals("artifact")&&(max<0||max>Integer.MAX_VALUE-8))throw new IllegalArgumentException("inline_max_bytes_required");
        JSONObject supplied=spec.optJSONObject("headers");if(supplied==null)supplied=new JSONObject();
        String replay=spec.optString("replay",method.equals("GET")||method.equals("HEAD")?"safe":"never");
        if(!replay.equals("safe")&&!replay.equals("never")&&!replay.equals("idempotent"))throw new IllegalArgumentException("invalid_replay_policy");
        if(replay.equals("safe")&&!method.equals("GET")&&!method.equals("HEAD"))throw new IllegalArgumentException("unsafe_method_replay");
        String idempotency=spec.optString("idempotency_key",""),idempotencyHeader=spec.optString("idempotency_header","Idempotency-Key");
        if(replay.equals("idempotent")&&idempotency.isEmpty())throw new IllegalArgumentException("vendor_idempotency_key_required");
        JSONObject headers=new JSONObject(supplied.toString());
        Set<String> headerNames=new HashSet<>();
        for(String name:keys(headers))if(!headerNames.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("duplicate_http_header");
        defaultHeader(headers,"Accept","*/*");defaultHeader(headers,"Accept-Encoding","identity");defaultHeader(headers,"User-Agent","DistributedWorker/1");
        if(replay.equals("idempotent")) {
            for(String name:keys(headers))if(name.equalsIgnoreCase(idempotencyHeader)&&!headers.getString(name).equals(idempotency))throw new IllegalArgumentException("idempotency_header_conflict");
            headers.put(idempotencyHeader,idempotency);
        }
        for(String name:keys(headers)) {
            if(!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")||headers.getString(name).contains("\r")||headers.getString(name).contains("\n"))throw new IllegalArgumentException("invalid_http_header");
            if(name.equalsIgnoreCase("Content-Length")||name.equalsIgnoreCase("Transfer-Encoding")||name.equalsIgnoreCase("Connection"))throw new IllegalArgumentException("transport_manages_header");
        }
        HttpUrl url=HttpUrl.get(spec.getString("url"));validate(url,method,headers,mode);
        byte[] body=spec.has("body_text")?spec.getString("body_text").getBytes(StandardCharsets.UTF_8):null;
        if(spec.has("body_artifact_id"))throw new IllegalArgumentException("http_body_artifact_not_supported");
        if((method.equals("GET")||method.equals("HEAD"))&&body!=null)throw new IllegalArgumentException("body_not_supported_for_method");
        JSONObject timeouts=spec.optJSONObject("timeouts");
        OkHttpClient.Builder builder=clientPool.newBuilder();
        if(timeouts!=null) {
            if(timeouts.has("connect_ms"))builder.connectTimeout(nonnegative(timeouts,"connect_ms"),TimeUnit.MILLISECONDS);
            if(timeouts.has("read_idle_ms"))builder.readTimeout(nonnegative(timeouts,"read_idle_ms"),TimeUnit.MILLISECONDS);
        }
        OkHttpClient client=builder.build();Set<String> visited=new HashSet<>();
        String execution=UUID.randomUUID().toString();JSONObject begin=gateway.begin(effect,execution,original);
        String state=begin.getString("state");
        if(state.equals("completed")){JSONObject saved=begin.getJSONObject("response");remember(saved);return saved;}
        if(!state.equals("execute"))throw new IllegalStateException("effect_uncertain");
        String requestHash=begin.getString("request_sha256");
        try {
            while(true) {
                check();validate(url,method,headers,mode);
                if(!visited.add(method+" "+url))throw new IllegalArgumentException("redirect_cycle");
                Request.Builder request=new Request.Builder().url(url);
                request.header("Accept","*/*").header("Accept-Encoding","identity").header("User-Agent","DistributedWorker/1");
                for(String name:keys(headers))request.header(name,headers.getString(name));
                boolean needsBody=method.equals("POST")||method.equals("PUT")||method.equals("PATCH")||method.equals("PROPPATCH")||method.equals("REPORT");
                RequestBody payload=body!=null||needsBody?RequestBody.create((MediaType)null,body==null?new byte[0]:body):null;
                activeCall=client.newCall(request.method(method,payload).build());
                try(Response response=activeCall.execute()) {
                    String location=response.header("Location");int status=response.code();
                    if(location!=null&&(status==301||status==302||status==303||status==307||status==308)&&redirect.equals("follow")) {
                        HttpUrl next=url.resolve(location);
                        boolean rewrite=(status==303&&!method.equals("HEAD"))||((status==301||status==302)&&method.equals("POST"));
                        String nextMethod=rewrite?"GET":method;
                        // A new vendor cannot inherit the first vendor's idempotency guarantee.
                        if(next!=null&&!visited.contains(nextMethod+" "+next)&&(origin(next).equals(origin(url))||nextMethod.equals("GET")||nextMethod.equals("HEAD"))) {
                            JSONObject nextHeaders=new JSONObject(headers.toString());
                            if(rewrite)for(String h:keys(nextHeaders))if(h.toLowerCase(Locale.ROOT).startsWith("content-")||h.equalsIgnoreCase("Expect")||h.equalsIgnoreCase("Transfer-Encoding"))nextHeaders.remove(h);
                            if(!origin(next).equals(origin(url)))for(String h:keys(nextHeaders))if(!h.equalsIgnoreCase("Accept")&&!h.equalsIgnoreCase("Accept-Encoding")&&!h.equalsIgnoreCase("User-Agent")&&!h.equalsIgnoreCase("Content-Type"))nextHeaders.remove(h);
                            boolean follow=true;try{validate(next,nextMethod,nextHeaders,mode);}catch(SecurityException|IllegalArgumentException denied){follow=false;}
                            if(follow){method=nextMethod;headers=nextHeaders;if(rewrite)body=null;url=next;continue;}
                        }
                    }
                    JSONObject responseHeaders=new JSONObject();
                    for(String name:response.headers().names())responseHeaders.put(name.toLowerCase(Locale.ROOT),new JSONArray(response.headers().values(name)));
                    JSONObject result=new JSONObject().put("status",status).put("headers",responseHeaders).put("url",url.toString());
                    String media=options.optString("media_type",response.header("Content-Type","application/octet-stream"));
                    String name=options.optString("name","response");
                    try(InputStream input=response.body().byteStream()) {
                        if(mode.equals("artifact"))result.put("artifact",stream(input,effect+"/"+execution,name,media));
                        else inline(input,result,mode,max,effect+"/"+execution,name,media,execution,requestHash);
                    }
                    remember(result);
                    gateway.finish(effect,execution,requestHash,result,responseArtifacts(result));
                    return result;
                }
            }
        }catch(Api.Failure e){throw e;}
        catch(java.io.IOException e){check();if(replay.equals("safe")||replay.equals("idempotent"))throw new Retryable();throw new IllegalStateException("effect_uncertain",e);}
        finally{activeCall=null;}
    }
    private static long nonnegative(JSONObject o,String name)throws Exception {long value=o.getLong(name);if(value<0)throw new IllegalArgumentException("invalid_timeout");return value;}
    private void inline(InputStream source,JSONObject result,String mode,long max,String key,String name,String media,String execution,String requestHash)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=source.read(buffer))!=-1) {
            check();
            if((long)bytes.size()+n>max) {
                InputStream prefix=new java.io.SequenceInputStream(new ByteArrayInputStream(bytes.toByteArray()),new java.io.SequenceInputStream(new ByteArrayInputStream(buffer,0,n),source));
                result.put("artifact",stream(prefix,key,name,media)).put("body_error",new JSONObject().put("code","inline_limit_exceeded"));return;
            }
            bytes.write(buffer,0,n);
        }
        byte[] raw=bytes.toByteArray();String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}
        catch(java.nio.charset.CharacterCodingException e){result.put("artifact",stream(new ByteArrayInputStream(raw),key,name,media)).put("body_error",new JSONObject().put("code","invalid_utf8"));return;}
        if(mode.equals("text"))result.put("body_text",text);
        else {
            try{result.put("body_json",strictJson(text));}
            catch(Exception|StackOverflowError e){result.put("artifact",stream(new ByteArrayInputStream(raw),key,name,media)).put("body_error",new JSONObject().put("code","invalid_json"));return;}
        }
        if(!gateway.inlineFits(execution,requestHash,result,responseArtifacts(result))) {
            result.remove("body_text");result.remove("body_json");
            result.put("artifact",stream(new ByteArrayInputStream(raw),key,name,media)).put("body_error",new JSONObject().put("code","control_budget_exceeded"));
        }
    }
    private static Object strictJson(String text)throws org.json.JSONException {
        Context cx=Context.enter();try {
            cx.setOptimizationLevel(-1);ScriptableObject scope=cx.initSafeStandardObjects();Scriptable json=(Scriptable)ScriptableObject.getProperty(scope,"JSON");
            Object value=((Function)ScriptableObject.getProperty(json,"parse")).call(cx,scope,json,new Object[]{text});
            Object serialized=((Function)ScriptableObject.getProperty(json,"stringify")).call(cx,scope,json,new Object[]{value});
            return new JSONTokener(Context.toString(serialized)).nextValue();
        }finally{Context.exit();}
    }
    private JSONObject stream(InputStream source,String key,String name,String media)throws Exception {
        String upload=gateway.create(key,name,media);byte[] buffer=new byte[CHUNK_BYTES];long offset=0;MessageDigest hash=MessageDigest.getInstance("SHA-256");
        while(true) {
            int length=0;while(length<buffer.length){check();int n=source.read(buffer,length,buffer.length-length);if(n<0)break;if(n==0)continue;length+=n;}
            if(length==0)break;check();gateway.chunk(upload,offset,buffer,length);hash.update(buffer,0,length);offset+=length;
            checkpoints.progress(new JSONObject().put("phase","streaming").put("bytes",offset).toString());
        }
        String digest=Operations.hex(hash.digest());JSONObject artifact=gateway.seal(upload,offset,digest);
        if(artifact.getLong("length")!=offset||!digest.equals(artifact.getString("sha256")))throw new IllegalStateException("artifact_integrity_mismatch");
        if(artifacts.add(artifact.getString("artifact_id")))artifactPersistence.accept(artifactIds());return artifact;
    }
    private void remember(JSONObject response)throws Exception {JSONObject a=response.optJSONObject("artifact");if(a!=null&&artifacts.add(a.getString("artifact_id")))artifactPersistence.accept(artifactIds());}
    private static JSONArray responseArtifacts(JSONObject response)throws Exception {JSONArray ids=new JSONArray();JSONObject a=response.optJSONObject("artifact");if(a!=null)ids.put(a.getString("artifact_id"));return ids;}
    static String origin(HttpUrl url){String host=url.host();if(host.contains(":"))host="["+host+"]";return url.scheme()+"://"+host+((url.port()==80&&url.scheme().equals("http"))||(url.port()==443&&url.scheme().equals("https"))?"":":"+url.port());}
    private void validate(HttpUrl url,String method,JSONObject headers,String mode)throws Exception {
        if(!url.username().isEmpty()||!url.password().isEmpty())throw new IllegalArgumentException("credentials_in_url_not_supported");
        if(policy==null)return;
        allowed("origins",origin(url),false);allowed("methods",method,false);allowed("response_modes",mode,false);
        for(String header:keys(headers))allowed("request_headers",header,true);
    }
    private void allowed(String key,String value,boolean ignoreCase)throws Exception {
        if(!policy.has(key))return;JSONArray values=policy.getJSONArray(key);
        for(int i=0;i<values.length();i++)if(ignoreCase?values.getString(i).equalsIgnoreCase(value):values.getString(i).equals(value))return;
        throw new SecurityException("network_policy_denied_"+key);
    }
    private static java.util.List<String> keys(JSONObject value){java.util.List<String> result=new ArrayList<>();java.util.Iterator<String> it=value.keys();while(it.hasNext())result.add(it.next());return result;}
    private static void defaultHeader(JSONObject headers,String name,String value)throws org.json.JSONException {for(String key:keys(headers))if(key.equalsIgnoreCase(name))return;headers.put(name,value);}
    private static void remove(JSONObject headers,String name){for(String key:new ArrayList<>(keys(headers)))if(key.equalsIgnoreCase(name))headers.remove(key);}
}
