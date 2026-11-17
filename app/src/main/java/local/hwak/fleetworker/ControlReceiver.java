package local.hwak.fleetworker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class ControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        String action=intent.getAction();Store store=Store.get(c);
        try {
            if("local.hwak.fleetworker.CONFIGURE".equals(action)) {
                new Configuration(intent.getStringExtra("base_url"),intent.getStringExtra("worker_id"),intent.getStringExtra("token")).save(c);
                store.setState("last_error","");WorkerService.start(c,false);
            } else if("local.hwak.fleetworker.CONFIGURE_RELEASES".equals(action)) {
                ReleaseConfiguration.save(c,intent.getStringExtra("release_sources"));WorkerService.start(c,true);
            } else if("local.hwak.fleetworker.CONFIGURE_PROXY".equals(action)) {
                ProxyConfiguration.save(c,intent.getStringExtra("proxy_json"),intent.getStringExtra("proxy_sources"),intent.getStringExtra("proxy_cache"));WorkerService.start(c,false);
            } else if("local.hwak.fleetworker.RELOAD".equals(action))WorkerService.start(c,false);
            else if("local.hwak.fleetworker.UPDATE_CHECK".equals(action))WorkerService.start(c,true);
            else if("local.hwak.fleetworker.CANCEL".equals(action))WorkerService.cancelCurrent();
            org.json.JSONObject status=store.status();Configuration configured=Configuration.load(c);status.put("configured",configured!=null);if(configured!=null)status.put("worker_id",configured.workerId);
            setResultCode(0);setResultData(status.toString());Bundle b=new Bundle();b.putString("status_json",status.toString());setResultExtras(b);
        } catch(Exception e) {store.setState("last_error","control_rejected");setResultCode(1);setResultData("{\"error\":\"control_rejected\"}");}
    }
}
