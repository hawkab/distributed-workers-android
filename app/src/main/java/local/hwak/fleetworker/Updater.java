package local.hwak.fleetworker;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import org.json.JSONObject;

final class Updater {
    private final Context context;private final Store store;
    Updater(Context c,Store s){context=c;store=s;}
    void check(Configuration config)throws Exception {
        int expected=parseInt(store.getState("update_version","0"));
        if(System.currentTimeMillis()<parseLong(store.getState("update_retry_at","0")))return;
        if(expected>0 && BuildConfig.VERSION_CODE>=expected){store.setState("update_state","applied");store.setState("update_session","");cleanupApplied(context,BuildConfig.VERSION_CODE);}
        String sessionText=store.getState("update_session","");
        if(!sessionText.isEmpty()) {
            PackageInstaller.SessionInfo info=context.getPackageManager().getPackageInstaller().getSessionInfo(parseInt(sessionText));
            if(info!=null && !store.getState("update_state","").equals("writing"))return;
            if(info!=null)context.getPackageManager().getPackageInstaller().abandonSession(info.getSessionId());
            store.setState("update_session","");
        }
        byte[] key;try(InputStream in=context.getAssets().open("release-public.der")){key=readBytes(in);}
        ReleaseResolver resolver=new ReleaseResolver(key,BuildConfig.VERSION_CODE,context.getCacheDir(),new ReleaseResolver.TrustStore(){
            @Override public JSONObject read()throws Exception {String saved=store.getState("update_trust","");return saved.isEmpty()?null:new JSONObject(saved);}
            @Override public void save(JSONObject value){store.setState("update_trust",value.toString());}
        },(source,state)->{store.setState("update_source",source);store.setState("update_source_state",state);});
        ReleaseResolver.Candidate candidate;
        try {candidate=resolver.resolve(ReleaseConfiguration.load(context),new PublicReleaseTransport(),this::verifyApk);}
        catch(ReleaseResolver.Unavailable e){store.setState("update_state","sources_unavailable");return;}
        if(candidate==null){store.setState("update_state","current");return;}
        ReleaseManifest release=candidate.manifest;File apk=candidate.apk;
        store.setState("update_version",Integer.toString(release.versionCode));
        String block=UpdatePolicy.silentBlock(Build.VERSION.SDK_INT,context.getPackageManager().canRequestPackageInstalls());
        if(block!=null){store.setState("update_state",block);return;}
        PackageInstaller installer=context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());params.setSize(apk.length());
        if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id=installer.createSession(params);store.setState("update_session",Integer.toString(id));store.setState("update_sha256",release.sha256);store.setState("update_state","writing");
        try(PackageInstaller.Session session=installer.openSession(id)) {
            try(InputStream in=new FileInputStream(apk);OutputStream out=session.openWrite("base.apk",0,apk.length())){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);session.fsync(out);}
            Intent receipt=new Intent(context,InstallReceiver.class).setAction("local.hwak.fleetworker.INSTALL_RESULT");receipt.putExtra("version_code",release.versionCode);receipt.putExtra("expected_session_id",id);
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            PendingIntent pending=PendingIntent.getBroadcast(context,id,receipt,flags);
            store.setState("update_state","committing");session.commit(pending.getIntentSender());
        }
    }
    @SuppressWarnings("deprecation") private void verifyApk(ReleaseManifest r,File apk)throws Exception {
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo archive=pm.getPackageArchiveInfo(apk.getPath(),flags),installed=pm.getPackageInfo(context.getPackageName(),flags);
        if(archive==null)throw new SecurityException("apk_identity");
        if(Build.VERSION.SDK_INT>=34 && (archive.applicationInfo==null || archive.applicationInfo.targetSdkVersion<31))throw new SecurityException("apk_silent_target");
        if(Build.VERSION.SDK_INT>=34){String owner=pm.getInstallSourceInfo(context.getPackageName()).getUpdateOwnerPackageName();if(owner!=null && !context.getPackageName().equals(owner))throw new SecurityException("foreign_update_owner");}
        String candidate=certificate(archive),own=certificate(installed);
        UpdatePolicy.checkIdentity(context.getPackageName(),archive.packageName,version(installed),r.versionCode,version(archive),r.versionName,archive.versionName,own,candidate,r.certificate);
    }
    @SuppressWarnings("deprecation") private static String certificate(PackageInfo p)throws Exception {
        Signature[] signatures=Build.VERSION.SDK_INT>=28?p.signingInfo.getApkContentsSigners():p.signatures;
        if(signatures==null || signatures.length!=1)throw new SecurityException("apk_signer_count");
        return Operations.hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }
    @SuppressWarnings("deprecation") private static long version(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    private static byte[] readBytes(InputStream in)throws Exception {java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}
    static void cleanupApplied(Context context,int version) {
        File[] files=context.getCacheDir().listFiles();if(files==null)return;
        for(File file:files){java.util.regex.Matcher m=java.util.regex.Pattern.compile("worker-update-([0-9]+)\\.apk").matcher(file.getName());if(m.matches() && parseInt(m.group(1))<=version)file.delete();}
    }
    private static long parseLong(String s){try{return Long.parseLong(s);}catch(NumberFormatException e){return 0;}}
    private static int parseInt(String s){try{return Integer.parseInt(s);}catch(NumberFormatException e){return 0;}}
}
