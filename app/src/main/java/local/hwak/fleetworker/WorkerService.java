package local.hwak.fleetworker;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class WorkerService extends Service {
    private static volatile WorkerService running;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<Job> active=new AtomicReference<>();
    private final Object wake=new Object();
    private final ExecutorService threads=Executors.newFixedThreadPool(2);
    private Store store;
    static final class Job {
        final JSONObject payload;final AtomicBoolean cancelled=new AtomicBoolean();volatile boolean leaseLost;volatile String progress="{}";volatile long nextHeartbeat;volatile NetworkOperations network;
        Job(JSONObject p){payload=p;}
        void cancel(){cancelled.set(true);NetworkOperations n=network;if(n!=null)n.cancel();}
    }
    static void start(Context c,boolean update) {
        if(update)Store.get(c).setState("update_requested","true");
        WorkerService existing=running;if(existing!=null){existing.signal();return;}
        try {c.startForegroundService(new Intent(c,WorkerService.class));}
        catch(RuntimeException e){Store.get(c).setState("worker_state","start_required");Store.get(c).setState("last_error","foreground_start_restricted");}
    }
    static void cancelCurrent(){WorkerService s=running;if(s!=null){Job j=s.active.get();if(j!=null)j.cancel();}}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();store=Store.get(this);notification();running=this;threads.execute(this::workLoop);threads.execute(this::heartbeatLoop);}
    @Override public int onStartCommand(Intent intent,int flags,int id){notification();signal();return START_STICKY;}
    private void notification() {
        NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel channel=new NotificationChannel("fleet_worker","Fleet Worker",NotificationManager.IMPORTANCE_MIN);channel.setShowBadge(false);manager.createNotificationChannel(channel);
        Notification notification=new Notification.Builder(this,"fleet_worker").setSmallIcon(R.drawable.ic_worker).setContentTitle("Fleet Worker").setContentText("Фоновая обработка заданий").setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1,notification);
    }
    private void workLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        long backoff=1000;
        while(!closed.get()) {
            try {
                Configuration config=Configuration.load(this);if(config==null){store.setState("worker_state","unconfigured");pause(5000);continue;}
                Api api=new Api(config,store);
                if(api.flushEffect()){backoff=1000;continue;}
                if(flushCompletion(api)) {backoff=1000;continue;}
                JSONObject pending=store.current();if(pending!=null){runJob(api,config,pending);backoff=1000;continue;}
                if(!admitted()){store.setState("worker_state","paused_resources");pause(5000);continue;}
                long now=System.currentTimeMillis(),last=parseLong(store.getState("last_update_check","0")),interval=parseLong(store.getState("update_poll_seconds","900"))*1000;
                if(store.getState("update_requested","false").equals("true") || now-last>=interval) {
                    store.setState("update_requested","false");store.setState("last_update_check",Long.toString(now));
                    try {new Updater(this,store).check(config);}catch(Exception e){store.setState("update_state","check_failed");store.setState("last_error","update_check_failed");}
                    if(store.getState("update_state","").equals("committing")){pause(1000);continue;}
                }
                if(UpdatePolicy.blocksClaims(store.getState("update_state",""))) {
                    try{new Updater(this,store).check(config);}catch(Exception e){store.setState("last_error","update_reconcile_failed");}
                    if(UpdatePolicy.blocksClaims(store.getState("update_state",""))){store.setState("worker_state","updating");pause(1000);continue;}
                }
                store.setState("worker_state","idle");
                JSONObject response=api.post("/v1/jobs/claim",new JSONObject().put("worker_id",config.workerId).put("capabilities",capabilities()));
                if(response==null){pause(5000);continue;}
                JSONObject job=response.getJSONObject("job");job.getString("id");job.getString("attempt_id");job.get("fencing_token");
                store.claimed(job);backoff=1000;
            } catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(Exception e){store.setState("last_error",e instanceof Api.Failure?"api_http_"+((Api.Failure)e).status:"worker_retry");store.setState("worker_state","waiting_connection");pauseQuiet(backoff);backoff=Math.min(60000,backoff*2);}
        }
    }
    private boolean flushCompletion(Api api)throws Exception {
        JSONObject completion=store.nextCompletion();if(completion==null)return false;
        String id=completion.getString("job_id"),attempt=completion.getString("attempt_id");JSONObject request=new JSONObject(completion.toString());request.remove("job_id");
        JSONObject ack=api.post("/v1/jobs/"+path(id)+"/complete",request);
        if(ack==null || !id.equals(ack.optString("job_id")) || !attempt.equals(ack.optString("attempt_id")) || !(ack.optBoolean("committed")||ack.optBoolean("candidate_saved")))throw new IllegalStateException("completion_not_acknowledged");
        store.acknowledged(id,attempt);store.setState("last_job_id",id);store.setState("last_job_status",completion.optString("status"));store.setState("last_error","");return true;
    }
    private void runJob(Api api,Configuration config,JSONObject payload)throws Exception {
        Job control=new Job(payload);active.set(control);store.setState("worker_state","running");
        try {
            heartbeatJob(api,config,control);
            if(control.leaseLost){store.abandonCurrent(payload.getString("id"));return;}
            Object result;
            try {
                Operations.Checkpoints callbacks=new Operations.Checkpoints(){
                    @Override public void checkpoint(String json){store.checkpoint(payload.optString("id"),json);control.progress=json;}
                    @Override public void progress(String json){control.progress=json;}
                };
                control.network=new NetworkOperations(api.networkGateway(payload,control.cancelled::get),control.cancelled::get,callbacks,payload.optJSONObject("network"));
                result=Operations.execute(payload,store.checkpoint(payload.getString("id")),control.cancelled::get,callbacks,control.network);
            }catch(CancellationException e){
                if(closed.get())return;if(control.leaseLost){store.abandonCurrent(payload.getString("id"));return;}
                saveCompletion(config,payload,"failed",JSONObject.NULL,"cancelled");return;
            }catch(Exception e){if(closed.get())return;Throwable cause=e;java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());while(cause!=null&&seen.add(cause)){if(cause instanceof NetworkOperations.Retryable)throw (NetworkOperations.Retryable)cause;if("effect_uncertain".equals(cause.getMessage())){saveCompletion(config,payload,"failed",JSONObject.NULL,"effect_uncertain");return;}cause=cause.getCause();}saveCompletion(config,payload,"failed",JSONObject.NULL,"execution_"+e.getClass().getSimpleName());return;}
            catch(StackOverflowError e){if(closed.get())return;saveCompletion(config,payload,"failed",JSONObject.NULL,"script_stack_exhausted");return;}
            catch(OutOfMemoryError e){if(closed.get())return;saveCompletion(config,payload,"failed",JSONObject.NULL,"script_memory_exhausted");return;}
            if(!closed.get())saveCompletion(config,payload,"succeeded",result,null);
        }finally {if(control.network!=null)control.network.close();active.compareAndSet(control,null);}
    }
    private void saveCompletion(Configuration config,JSONObject payload,String status,Object result,String code)throws Exception {
        JSONObject completion=identity(config,payload).put("job_id",payload.getString("id")).put("status",status).put("result",result);
        Job control=active.get();if(control!=null&&control.network!=null)completion.put("artifact_ids",control.network.artifactIds());
        if(code!=null)completion.put("error",new JSONObject().put("code",code).put("message",code));
        store.completion(payload,completion);store.setState("worker_state","uploading");
    }
    private void heartbeatLoop() {
        long nextWorker=0;
        while(!closed.get()) {
            try {
                Configuration config=Configuration.load(this);if(config==null){pause(1000);continue;}Api api=new Api(config,store);long now=System.currentTimeMillis();
                if(now>=nextWorker) {
                    JSONObject body=new JSONObject().put("worker_id",config.workerId).put("version_code",BuildConfig.VERSION_CODE).put("version_name",BuildConfig.VERSION_NAME).put("capabilities",capabilities()).put("abis",new JSONArray(Build.SUPPORTED_ABIS)).put("state",store.getState("worker_state","starting"));
                    Job current=active.get();if(current!=null)body.put("active_job_id",current.payload.optString("id"));
                    JSONObject policy=api.post("/v1/workers/heartbeat",body);
                    if(policy!=null && policy.has("update_poll_seconds") && policy.getLong("update_poll_seconds")>0)store.setState("update_poll_seconds",Long.toString(policy.getLong("update_poll_seconds")));
                    store.setState("last_heartbeat_at",Long.toString(now));nextWorker=now+15000;
                }
                Job job=active.get();if(job!=null && now>=job.nextHeartbeat)heartbeatJob(api,config,job);
                pause(1000);
            }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(Exception e){store.setState("last_error","heartbeat_unavailable");pauseQuiet(3000);}
        }
    }
    private void heartbeatJob(Api api,Configuration config,Job job)throws Exception {
        synchronized(job) {
            if(job.leaseLost)return;
            JSONObject progress=new JSONObject().put("state","executing");Object value=new JSONTokener(job.progress).nextValue();progress.put("value",value);
            String checkpoint=store.checkpoint(job.payload.getString("id"));if(!checkpoint.equals("null"))progress.put("checkpoint",new JSONTokener(checkpoint).nextValue());
            try {
                JSONObject response=api.post("/v1/jobs/"+path(job.payload.getString("id"))+"/heartbeat",identity(config,job.payload).put("progress",progress));
                long delay=15000;
                    if(response!=null){if(response.optBoolean("cancel_requested"))job.cancel();String until=response.optString("lease_until","");if(!until.isEmpty()){try{long remaining=Instant.parse(until).toEpochMilli()-System.currentTimeMillis();if(remaining>0)delay=Math.max(1000,Math.min(15000,remaining/3));}catch(Exception ignored){}}}
                job.nextHeartbeat=System.currentTimeMillis()+delay;
            }catch(Api.Failure e){if(e.status==404 || e.status==409){job.leaseLost=true;job.cancel();store.setState("last_error","lease_lost");return;}throw e;}
        }
    }
    private static JSONObject identity(Configuration c,JSONObject p)throws Exception{return new JSONObject().put("worker_id",c.workerId).put("attempt_id",p.getString("attempt_id")).put("fencing_token",p.get("fencing_token"));}
    private boolean admitted() {
        ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();getSystemService(ActivityManager.class).getMemoryInfo(memory);
        if(memory.lowMemory || memory.availMem<=memory.threshold)return false;
        return Build.VERSION.SDK_INT<29 || getSystemService(PowerManager.class).getCurrentThermalStatus()<PowerManager.THERMAL_STATUS_SEVERE;
    }
    static JSONArray capabilities(){return new JSONArray().put("sha256").put("text.normalize").put("javascript").put("javascript.portable.v1").put("http.request").put("artifact.stream");}
    private static String path(String id)throws Exception{return URLEncoder.encode(id,"UTF-8");}
    private static long parseLong(String s){try{return Long.parseLong(s);}catch(NumberFormatException e){return 0;}}
    private void signal(){synchronized(wake){wake.notifyAll();}}
    private void pause(long millis)throws InterruptedException {synchronized(wake){wake.wait(millis);}}
    private void pauseQuiet(long millis){try{pause(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    @Override public void onDestroy(){closed.set(true);Job job=active.get();if(job!=null)job.cancel();signal();threads.shutdownNow();running=null;super.onDestroy();}
}
