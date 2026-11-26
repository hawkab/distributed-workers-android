package local.hwak.fleetworker;

import android.app.ActivityManager;
import android.content.Context;
import android.os.StatFs;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resource quantities only. Device/model/host/serial identifiers are never inspected. */
final class WorkerProfile {
    private WorkerProfile() {}
    static String collect(Context context,JSONArray capabilities)throws Exception {return collect(context,capabilities,null);}
    static String collect(Context context,JSONArray capabilities,JSONObject execution)throws Exception {
        Integer cores=null;Double availableCores=null;Long totalRam=null,availableRam=null,totalDisk=null,availableDisk=null;
        Runtime runtime=Runtime.getRuntime();
        int processors=runtime.availableProcessors();
        if(processors>0){cores=processors;availableCores=(double)processors;}
        Integer affinity=affinityCores();
        if(affinity!=null)availableCores=availableCores==null?(double)affinity:Math.min(availableCores,affinity);
        try {
            ActivityManager manager=context.getSystemService(ActivityManager.class);
            if(manager!=null){ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();manager.getMemoryInfo(memory);
                if(memory.totalMem>0)totalRam=memory.totalMem;
                if(memory.availMem>=0&&memory.totalMem>0)availableRam=memory.availMem;
            }
        }catch(RuntimeException ignored){}
        availableRam=availableMemory(availableRam,runtime.maxMemory(),runtime.totalMemory(),runtime.freeMemory());
        try {
            File stateDirectory=context.getDatabasePath("worker.db").getParentFile();
            if(stateDirectory==null||!stateDirectory.isDirectory())stateDirectory=context.getFilesDir();
            StatFs state=new StatFs(stateDirectory.getAbsolutePath());
            if(state.getTotalBytes()>0)totalDisk=state.getTotalBytes();
            if(state.getAvailableBytes()>=0)availableDisk=state.getAvailableBytes();
        }catch(RuntimeException ignored){}
        JSONObject p=document(capabilities,cores,availableCores,totalRam,availableRam,totalDisk,availableDisk);if(execution!=null)p.put("execution",new JSONObject(execution.toString()));return encode(p);
    }
    static Long availableMemory(Long osAvailable,long heapMaximum,long heapAllocated,long heapFree) {
        if(heapMaximum<=0||heapAllocated<0||heapFree<0||heapFree>heapAllocated)return osAvailable;
        long heapAvailable=Math.max(0,heapMaximum-(heapAllocated-heapFree));
        return osAvailable==null?heapAvailable:Math.min(osAvailable,heapAvailable);
    }
    static JSONObject document(JSONArray capabilities,Integer cores,Double availableCores,Long totalRam,Long availableRam,Long totalDisk,Long availableDisk)throws Exception {
        JSONObject resources=new JSONObject().put("cpu_logical_cores",nullable(cores)).put("cpu_available_cores",nullable(availableCores))
            .put("ram_total_bytes",nullable(totalRam)).put("ram_available_bytes",nullable(availableRam))
            .put("disk_total_bytes",nullable(totalDisk)).put("disk_available_bytes",nullable(availableDisk))
            .put("gpu_status","unknown").put("gpus",new JSONArray());
        return new JSONObject().put("schema",1).put("capabilities",new JSONArray(capabilities.toString())).put("resources",resources);
    }
    static String encode(JSONObject profile){return Base64.getUrlEncoder().withoutPadding().encodeToString(profile.toString().getBytes(StandardCharsets.UTF_8));}
    private static Object nullable(Object value){return value==null?JSONObject.NULL:value;}
    private static Integer affinityCores() {
        try(BufferedReader input=new BufferedReader(new FileReader("/proc/self/status"))){
            for(String line;(line=input.readLine())!=null;)if(line.startsWith("Cpus_allowed_list:"))return affinityCount(line.substring(line.indexOf(':')+1).trim());
        }catch(Exception ignored){}
        return null;
    }
    static Integer affinityCount(String value) {
        try {
            if(value.isEmpty())return null;
            long count=0;long previous=-1;
            for(String part:value.split(",",-1)){
                String[] range=part.split("-",-1);if(range.length<1||range.length>2)return null;
                long first=Long.parseLong(range[0]),last=range.length==1?first:Long.parseLong(range[1]);
                if(first<0||last<first||first<=previous)return null;
                count=Math.addExact(count,Math.addExact(last-first,1));previous=last;
            }
            return count>0&&count<=Integer.MAX_VALUE?(int)count:null;
        }catch(RuntimeException invalid){return null;}
    }
}
