package local.hwak.fleetworker;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.List;
import org.json.JSONObject;

/** Ordered failover encloses discovery, authentication, transfer and APK validation. */
final class ReleaseResolver {
    interface Transport {String text(String url)throws Exception;void download(String url,File target)throws Exception;}
    interface Validator {void validate(ReleaseManifest manifest,File artifact)throws Exception;}
    interface Observer {void event(String source,String state);}
    interface TrustStore {JSONObject read()throws Exception;void save(JSONObject value)throws Exception;}
    static final class Candidate {
        final ReleaseManifest manifest;final File apk;final String source;
        Candidate(ReleaseManifest manifest,File apk,String source){this.manifest=manifest;this.apk=apk;this.source=source;}
    }
    static final class Unavailable extends Exception {Unavailable(){super("release_sources_unavailable");}}
    private final byte[] key;private final int installedVersion;private final File directory;private final TrustStore trust;private final Observer observer;
    ReleaseResolver(byte[] key,int installedVersion,File directory,TrustStore trust,Observer observer){this.key=key;this.installedVersion=installedVersion;this.directory=directory;this.trust=trust;this.observer=observer;}
    Candidate resolve(List<ReleaseSource> sources,Transport transport,Validator validator)throws Exception {
        if(sources.isEmpty())throw new Unavailable();
        boolean foundCurrent=false,foundNewer=false;
        for(ReleaseSource source:sources) {
            if(source.unconfigured()){observer.event(source.id,"not_configured");continue;}
            File partial=null;String stage="discovery";
            try {
                observer.event(source.id,"checking");ReleaseSource.Location location=source.discover(transport);
                stage="manifest";
                ReleaseManifest manifest=ReleaseManifest.verify(location.manifest,key);authenticateMonotonic(manifest);
                if(manifest.versionCode<=installedVersion){foundCurrent=true;observer.event(source.id,"current");continue;}
                foundNewer=true;
                File apk=new File(directory,"worker-update-"+manifest.versionCode+".apk");
                if(!apk.isFile() || !sha256(apk).equalsIgnoreCase(manifest.sha256)) {
                    partial=new File(apk.getPath()+".part");
                    stage="download";observer.event(source.id,"downloading");
                    transport.download(location.apkUrl,partial);
                    if(!sha256(partial).equalsIgnoreCase(manifest.sha256))throw new SecurityException("release_apk_hash");
                    stage="apk";
                    validator.validate(manifest,partial);
                    if(!partial.renameTo(apk))throw new IllegalStateException("release_atomic_rename");partial=null;
                }else {stage="apk";validator.validate(manifest,apk);}
                observer.event(source.id,"verified");return new Candidate(manifest,apk,source.id);
            }catch(Exception e){
                if(partial!=null)partial.delete();
                observer.event(source.id,"failed_"+stage+"_"+e.getClass().getSimpleName());
            }
        }
        // A stale but healthy mirror must not conceal a newer signed release.
        // Once a newer release is known, failed artifact delivery is unavailable,
        // even if an earlier mirror reported the installed version in this check.
        if(foundCurrent && !foundNewer)return null;
        throw new Unavailable();
    }
    private void authenticateMonotonic(ReleaseManifest manifest)throws Exception {
        JSONObject prior=trust.read();int seen=prior==null?0:prior.optInt("version_code",0),floor=Math.max(installedVersion,seen);
        if(manifest.versionCode<floor)throw new SecurityException("release_rollback");
        String identity=Operations.hex(MessageDigest.getInstance("SHA-256").digest(manifest.signedBytes()));
        if(seen==manifest.versionCode && prior.has("identity") && !identity.equals(prior.getString("identity")))throw new SecurityException("release_version_conflict");
        if(manifest.versionCode>seen || prior==null)trust.save(new JSONObject().put("version_code",manifest.versionCode).put("identity",identity));
    }
    static String sha256(File file)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(file)){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}return Operations.hex(digest.digest());}
}
