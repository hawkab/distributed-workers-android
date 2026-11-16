package local.hwak.fleetworker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        Store store=Store.get(context);int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        int session=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1),expected=intent.getIntExtra("expected_session_id",-1),version=intent.getIntExtra("version_code",0);
        if(!UpdatePolicy.callbackMatches(session,expected,store.getState("update_session",""),version,store.getState("update_version","")))return;
        if(status==PackageInstaller.STATUS_SUCCESS){store.setState("update_state","applied");store.setState("update_session","");Updater.cleanupApplied(context,version);}
        else if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            store.setState("update_state","blocked_user_action");
            try{context.getPackageManager().getPackageInstaller().abandonSession(session);}catch(RuntimeException ignored){ }
            store.setState("update_session","");store.setState("update_retry_at",Long.toString(System.currentTimeMillis()+30000));
        }
        else {store.setState("update_state","failed_"+status);store.setState("update_session","");store.setState("update_retry_at",Long.toString(System.currentTimeMillis()+30000));}
        // Never launch EXTRA_INTENT. A blocked silent install is a reported state.
        WorkerService.start(context,false);
    }
}
