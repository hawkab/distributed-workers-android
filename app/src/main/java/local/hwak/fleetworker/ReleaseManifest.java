package local.hwak.fleetworker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.json.JSONObject;

final class ReleaseManifest {
    final String packageName,versionName,url,sha256,certificate;
    final int versionCode;
    private ReleaseManifest(JSONObject o)throws Exception {
        packageName=o.getString("package_name");versionCode=o.getInt("version_code");versionName=o.getString("version_name");url=o.getString("url");sha256=o.getString("sha256");certificate=o.getString("apk_certificate_sha256");
        URI u=URI.create(url);
        if(!packageName.equals("local.hwak.fleetworker") || versionCode<=0 || versionName.indexOf('\n')>=0 || versionName.indexOf('\r')>=0 || !sha256.matches("[0-9a-fA-F]{64}") || !certificate.matches("[0-9a-fA-F]{64}") || !"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getFragment()!=null)throw new SecurityException("invalid_release");
    }
    static ReleaseManifest verify(JSONObject o,byte[] publicKey)throws Exception {
        ReleaseManifest m=new ReleaseManifest(o);if(!"SHA256withRSA".equals(o.getString("signature_alg")))throw new SecurityException("signature_algorithm");
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey)));verifier.update(m.signedBytes());
        if(!verifier.verify(Base64.getDecoder().decode(o.getString("signature"))))throw new SecurityException("release_signature");return m;
    }
    byte[] signedBytes(){return (packageName+"\n"+versionCode+"\n"+versionName+"\n"+url+"\n"+sha256+"\n"+certificate+"\n").getBytes(StandardCharsets.UTF_8);}
}
