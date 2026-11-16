package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

public class ReleaseManifestTest {
    private static KeyPair key;
    @BeforeClass public static void key()throws Exception {KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);key=g.generateKeyPair();}
    private static JSONObject manifest()throws Exception {
        JSONObject m=new JSONObject().put("package_name","local.hwak.fleetworker").put("version_code",2).put("version_name","0.1.1").put("url","https://example.org/worker.apk").put("sha256","a".repeat(64)).put("apk_certificate_sha256","b".repeat(64)).put("signature_alg","SHA256withRSA");
        byte[] data=("local.hwak.fleetworker\n2\n0.1.1\nhttps://example.org/worker.apk\n"+"a".repeat(64)+"\n"+"b".repeat(64)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature s=Signature.getInstance("SHA256withRSA");s.initSign(key.getPrivate());s.update(data);return m.put("signature",Base64.getEncoder().encodeToString(s.sign()));
    }
    @Test public void acceptsExactPublishedSignature()throws Exception {ReleaseManifest m=ReleaseManifest.verify(manifest(),key.getPublic().getEncoded());assertEquals(2,m.versionCode);assertEquals("0.1.1",m.versionName);}
    @Test public void refusesChangedManifestAndForeignPackage()throws Exception {
        for(String field:new String[]{"version_name","sha256","package_name","signature_alg"}){
            JSONObject m=manifest();m.put(field,field.equals("sha256")?"c".repeat(64):"altered");
            try{ReleaseManifest.verify(m,key.getPublic().getEncoded());fail(field);}catch(SecurityException expected){ }
        }
    }
    @Test public void refusesCleartextOrUnpinnedSigningKey()throws Exception {
        JSONObject m=manifest();m.put("url","http://example.org/worker.apk");try{ReleaseManifest.verify(m,key.getPublic().getEncoded());fail();}catch(SecurityException expected){ }
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);try{ReleaseManifest.verify(manifest(),g.generateKeyPair().getPublic().getEncoded());fail();}catch(SecurityException expected){ }
    }
}
