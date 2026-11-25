package local.hwak.fleetworker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class Api {
    static final int CONTROL_JSON_BYTES=16*1024*1024;
    static final class Failure extends IOException { final int status; Failure(int s){super("api_http_"+s);status=s;} }
    private final Configuration config;
    interface Profiles { String header(JSONArray capabilities)throws Exception; }
    private final EffectOutbox effects;
    private final Profiles profiles;
    private final JSONArray capabilities;
    volatile boolean claimWaitApplied;
    static boolean appliedClaimWait(String path,String header){return path.equals("/v1/jobs/claim")&&header!=null&&header.matches("(?i).*(?:^|[,;\\s])wait=([1-9][0-9]*)(?:$|[,;\\s]).*");}
    Api(Configuration c,Store store,JSONArray capabilities,Profiles profiles){config=c;effects=new EffectOutbox(store.effectPersistence());this.capabilities=capabilities;this.profiles=profiles;}
    static boolean carriesProfile(String path){return path.equals("/v1/workers/connect")||path.equals("/v1/workers/heartbeat")||path.equals("/v1/jobs/claim");}
    static String profileHeader(String path,JSONObject body,JSONArray defaults,Profiles profiles)throws Exception {
        if(!carriesProfile(path))return null;
        JSONArray advertised=body.optJSONArray("capabilities");
        return profiles.header(advertised==null?defaults:advertised);
    }
    static HttpsURLConnection openDirect(String address)throws IOException {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(address).openConnection(java.net.Proxy.NO_PROXY);
        connection.setSSLSocketFactory(new DirectTlsSockets(connection.getSSLSocketFactory()));return connection;
    }
    boolean flushEffect()throws Exception{return effects.flushOne((path,body)->post(path,body));}
    JSONObject post(String path,JSONObject body)throws Exception {
        if(config.workerId.isEmpty()&&!path.equals("/v1/workers/connect"))throw new IllegalStateException("worker_not_connected");
        String profile=profileHeader(path,body,capabilities,profiles);
        HttpsURLConnection c=openDirect(config.baseUrl+path);
        c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+config.token);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
        if(profile!=null)c.setRequestProperty("X-Worker-Profile",profile);
        if(path.equals("/v1/jobs/claim"))c.setRequestProperty("Prefer","wait=20");
        byte[] raw=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(raw.length);
        boolean reusable=false;
        try {try(java.io.OutputStream out=c.getOutputStream()){out.write(raw);}int status=c.getResponseCode();if(path.equals("/v1/jobs/claim"))claimWaitApplied=appliedClaimWait(path,c.getHeaderField("Preference-Applied"));if(status==204){reusable=true;return null;}if(status!=200)throw new Failure(status);try(InputStream in=c.getInputStream()){JSONObject result=new JSONObject(read(in));reusable=true;return result;}}
        finally {if(!reusable)c.disconnect();}
    }
    static String read(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}
    NetworkOperations.Gateway networkGateway(JSONObject job,BooleanSupplier cancelled) {
        if(config.workerId.isEmpty())throw new IllegalStateException("worker_not_connected");
        return new NetworkOperations.Gateway() {
            private volatile HttpsURLConnection active;
            private JSONObject identity()throws Exception{return new JSONObject().put("worker_id",config.workerId).put("attempt_id",job.getString("attempt_id")).put("fencing_token",job.get("fencing_token"));}
            private String effect(String id)throws Exception{return "/v1/jobs/"+encode(job.getString("id"))+"/effects/"+encode(id);}
            private JSONObject request(String method,String path,JSONObject json,byte[] bytes,int length,boolean preserveResponse)throws Exception {
                long delay=1000;
                while(true) {
                    if(!preserveResponse&&(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()))throw new CancellationException("job_cancelled");
                    try{return once(method,path,json,bytes,length);}
                    catch(Failure e){if(e.status!=408&&e.status!=425&&e.status!=429&&e.status<500)throw e;}
                    catch(IOException e){ }
                    if(preserveResponse&&cancelled.getAsBoolean())throw new CancellationException("response_delivery_pending");
                    Thread.sleep(delay);delay=Math.min(30000,delay*2);
                }
            }
            private JSONObject once(String method,String path,JSONObject json,byte[] bytes,int length)throws Exception {
                HttpsURLConnection c=openDirect(config.baseUrl+path);active=c;
                c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
                c.setRequestProperty("Authorization","Bearer "+config.token);c.setRequestProperty("Accept","application/json");
                c.setRequestProperty("X-Worker-Id",config.workerId);c.setRequestProperty("X-Attempt-Id",job.getString("attempt_id"));c.setRequestProperty("X-Fencing-Token",job.get("fencing_token").toString());
                try {
                    if(json!=null||bytes!=null){c.setDoOutput(true);byte[] body=json!=null?json.toString().getBytes(StandardCharsets.UTF_8):bytes;int size=json!=null?body.length:length;c.setRequestProperty("Content-Type",json!=null?"application/json":"application/octet-stream");c.setFixedLengthStreamingMode(size);try(java.io.OutputStream out=c.getOutputStream()){out.write(body,0,size);}}
                    int status=c.getResponseCode();if(status!=200&&status!=201)throw new Failure(status);
                    try(InputStream in=c.getInputStream()){return new JSONObject(read(in));}
                }finally{active=null;c.disconnect();}
            }
            @Override public JSONObject begin(String id,String execution,JSONObject spec)throws Exception {
                return request("POST",effect(id)+"/begin",identity().put("execution_id",execution).put("request",spec),null,0,false);
            }
            @Override public JSONObject status(String id)throws Exception {
                try{return request("GET",effect(id)+"/status",null,null,0,false);}catch(Failure missing){if(missing.status==404)return null;throw missing;}
            }
            @Override public void finish(String id,String execution,String hash,JSONObject response,JSONArray artifacts)throws Exception {
                JSONObject record=effects.record(effect(id)+"/finish",identity().put("execution_id",execution).put("request_sha256",hash).put("response",response).put("artifact_ids",artifacts));
                effects.deliver(record,(path,body)->request("POST",path,body,null,0,true));
            }
            @Override public boolean inlineFits(String execution,String hash,JSONObject response,JSONArray artifacts)throws Exception {
                JSONObject body=identity().put("execution_id",execution).put("request_sha256",hash).put("response",response).put("artifact_ids",artifacts);
                return body.toString().getBytes(StandardCharsets.UTF_8).length<=CONTROL_JSON_BYTES;
            }
            @Override public String create(String key,String name,String type)throws Exception {
                JSONObject ack=request("POST","/v1/jobs/"+encode(job.getString("id"))+"/artifacts",identity().put("key",key).put("name",name).put("media_type",type),null,0,false);return ack.getString("upload_id");
            }
            @Override public void chunk(String upload,long offset,byte[] bytes,int length)throws Exception {
                JSONObject ack=request("PUT","/v1/artifacts/"+encode(upload)+"/chunks/"+offset,null,bytes,length,false);
                if(!upload.equals(ack.optString("upload_id"))||ack.getLong("next_offset")!=offset+length||ack.getLong("length")!=length)throw new IllegalStateException("chunk_not_acknowledged");
            }
            @Override public JSONObject seal(String upload,long length,String hash)throws Exception {
                return request("POST","/v1/artifacts/"+encode(upload)+"/finish",identity().put("length",length).put("sha256",hash),null,0,false);
            }
            @Override public void cancel(){HttpsURLConnection c=active;if(c!=null)c.disconnect();}
        };
    }
    private static String encode(String value)throws Exception{return URLEncoder.encode(value,"UTF-8").replace("+","%20");}
}
