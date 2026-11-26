package local.hwak.fleetworker;
import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.FileReader;
import org.json.JSONObject;

/** Owner-first admission: low-priority work, bounded app heap and no assumption of idle CPU. */
final class Admission {
    private long priorTotal=-1,priorIdle;
    private int previous=1;
    static int slots(int cores,long heapFree,long heapMax,boolean lowMemory,boolean charging,boolean interactive,boolean television,int thermal,Double cpu,int active,int previous){
        long reserve=heapMax/4,perJob=64L*1024*1024;
        if(lowMemory||heapFree<=reserve||thermal>=2)return 0;
        int memory=active+(int)((heapFree-reserve)/perJob);
        if(memory<=0)return 0;
        if(cpu==null)return 1;
        double target=television?.20:charging?.35:.15;
        int maximum=charging?Math.min(2,Math.max(1,cores/2)):1;
        if(interactive||thermal>0)maximum=1;
        int n=active+(int)Math.floor((target-cpu)*cores);
        if(cpu>target)n=active-(int)Math.ceil((cpu-target)*cores);
        if(active==0&&cpu<target)n=Math.max(1,n);
        return Math.max(0,Math.min(Math.min(maximum,memory),Math.min(n,previous+1)));
    }
    synchronized JSONObject sample(Context c,int active)throws Exception{
        ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();c.getSystemService(ActivityManager.class).getMemoryInfo(m);
        PowerManager power=c.getSystemService(PowerManager.class);Intent battery=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean charging=battery!=null&&(!battery.getBooleanExtra(BatteryManager.EXTRA_PRESENT,true)||battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0);
        UiModeManager ui=c.getSystemService(UiModeManager.class);boolean tv=ui!=null&&ui.getCurrentModeType()==Configuration.UI_MODE_TYPE_TELEVISION;
        int thermal=Build.VERSION.SDK_INT>=29?power.getCurrentThermalStatus():0;
        Runtime r=Runtime.getRuntime();long free=Math.max(0,r.maxMemory()-(r.totalMemory()-r.freeMemory()));Double cpu=cpu();
        int n=slots(r.availableProcessors(),free,r.maxMemory(),m.lowMemory||m.availMem<=m.threshold,charging,power.isInteractive(),tv,thermal,cpu,active,previous);previous=n;
        return new JSONObject().put("max_concurrent_jobs",n).put("mode",tv?"television":charging?"mobile_charging":"mobile_battery").put("cpu_utilization",cpu==null?JSONObject.NULL:cpu).put("memory_reserve_bytes",r.maxMemory()/4);
    }
    private Double cpu(){
        try(BufferedReader in=new BufferedReader(new FileReader("/proc/stat"))){String line=in.readLine();if(line==null||!line.startsWith("cpu "))return null;String[] words=line.trim().split("\\s+");long total=0,idle=0;for(int i=1;i<Math.min(words.length,9);i++){long v=Long.parseLong(words[i]);total+=v;if(i==4||i==5)idle+=v;}Double utilization=priorTotal>=0&&total>priorTotal?Math.max(0,Math.min(1,1.0-(double)(idle-priorIdle)/(total-priorTotal))):null;priorTotal=total;priorIdle=idle;return utilization;}catch(Exception e){return null;}
    }
}
