package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReleaseResolverTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static KeyPair key;
    private static final byte[] APK="test APK bytes, validated separately by Android PackageManager".getBytes(StandardCharsets.UTF_8);
    private static final String CERT="b".repeat(64),CANONICAL="https://gitea.example/releases/v0.2.0/fleet-worker-android.apk";
    @BeforeClass public static void keys()throws Exception {KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();}
    private static JSONObject manifest(int version,byte[] apk)throws Exception {
        return signed(version,apk,CERT,CANONICAL);
    }
    private static JSONObject signed(int version,byte[] apk,String certificate,String canonical)throws Exception {
        String hash=Operations.hex(MessageDigest.getInstance("SHA-256").digest(apk));
        String name="0.2."+version;
        JSONObject object=new JSONObject().put("package_name","local.hwak.fleetworker").put("version_code",version).put("version_name",name).put("url",canonical).put("sha256",hash).put("apk_certificate_sha256",certificate).put("signature_alg","SHA256withRSA");
        String canonicalBytes="local.hwak.fleetworker\n"+version+"\n"+name+"\n"+canonical+"\n"+hash+"\n"+certificate+"\n";
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(key.getPrivate());signature.update(canonicalBytes.getBytes(StandardCharsets.UTF_8));
        return object.put("signature",Base64.getEncoder().encodeToString(signature.sign()));
    }
    private static final class Trust implements ReleaseResolver.TrustStore {
        JSONObject value;
        @Override public JSONObject read()throws Exception {return value==null?null:new JSONObject(value.toString());}
        @Override public void save(JSONObject input)throws Exception {value=new JSONObject(input.toString());}
    }
    private static final class Transport implements ReleaseResolver.Transport {
        final Map<String,String> text=new HashMap<>();final Map<String,byte[]> bytes=new HashMap<>();final List<String> reads=new ArrayList<>();
        @Override public String text(String url)throws Exception {reads.add(url);if(!text.containsKey(url))throw new IOException("unavailable");return text.get(url);}
        @Override public void download(String url,File target)throws Exception {reads.add(url);if(!bytes.containsKey(url))throw new IOException("unavailable");Files.write(target.toPath(),bytes.get(url));}
    }
    private static List<ReleaseSource> chain(){return Arrays.asList(new ReleaseSource("gitea","gitea","https://gitea.example/latest","android-manifest.json","fleet-worker-android.apk"),new ReleaseSource("gitflic","channel","https://gitflic.example/channel","android-manifest.json","fleet-worker-android.apk"),new ReleaseSource("github","github","https://github.example/latest","android-manifest.json","fleet-worker-android.apk"));}
    private static Transport transport(JSONObject manifest)throws Exception {
        Transport transport=new Transport();
        for(String host:Arrays.asList("gitea","gitflic","github")) {
            String root="https://"+host+".example/";
            JSONObject metadata=host.equals("gitflic")?new JSONObject().put("manifest_url",root+"manifest").put("apk_url",root+"apk"):new JSONObject().put("assets",new JSONArray().put(new JSONObject().put("name","android-manifest.json").put("browser_download_url",root+"manifest")).put(new JSONObject().put("name","fleet-worker-android.apk").put("browser_download_url",root+"apk")));
            transport.text.put(root+(host.equals("gitflic")?"channel":"latest"),metadata.toString());transport.text.put(root+"manifest",manifest.toString());transport.bytes.put(root+"apk",APK);
        }
        return transport;
    }
    private ReleaseResolver resolver(Trust trust,List<String> events)throws Exception {return new ReleaseResolver(key.getPublic().getEncoded(),3,temporary.getRoot(),trust,(source,state)->events.add(source+":"+state));}
    private static void validate(ReleaseManifest manifest,File file)throws Exception {if(!manifest.certificate.equals(CERT))throw new SecurityException("foreign_certificate");assertArrayEquals(APK,Files.readAllBytes(file.toPath()));}
    @Test public void healthyPrimaryDoesNotContactFallbacks()throws Exception {
        Transport transport=transport(manifest(4,APK));List<String> events=new ArrayList<>();Trust trust=new Trust();
        ReleaseResolver.Candidate candidate=resolver(trust,events).resolve(chain(),transport,ReleaseResolverTest::validate);
        assertEquals("gitea",candidate.source);assertEquals(4,trust.value.getInt("version_code"));assertFalse(transport.reads.stream().anyMatch(url->url.contains("gitflic")||url.contains("github")));
    }
    @Test public void unsetInfrastructureSourceIsSkippedWithoutAnyNetworkRequest()throws Exception {
        List<ReleaseSource> sources=new ArrayList<>(chain());sources.set(0,new ReleaseSource("gitea","gitea","https://gitea.invalid/releases/latest","android-manifest.json","fleet-worker-android.apk"));
        Transport transport=transport(manifest(4,APK));List<String> events=new ArrayList<>();
        ReleaseResolver.Candidate candidate=resolver(new Trust(),events).resolve(sources,transport,ReleaseResolverTest::validate);
        assertEquals("gitflic",candidate.source);assertTrue(events.contains("gitea:not_configured"));assertFalse(transport.reads.stream().anyMatch(url->url.contains(".invalid")));
    }
    @Test public void operatorConfiguredChainRestoresPrimaryPriority()throws Exception {
        JSONArray configured=new JSONArray();for(ReleaseSource source:chain())configured.put(new JSONObject().put("id",source.id).put("type",source.type).put("url",source.url));
        Transport transport=transport(manifest(4,APK));ReleaseResolver.Candidate candidate=resolver(new Trust(),new ArrayList<>()).resolve(ReleaseSource.parse(configured.toString()),transport,ReleaseResolverTest::validate);
        assertEquals("gitea",candidate.source);assertFalse(transport.reads.stream().anyMatch(url->url.contains("gitflic")||url.contains("github")));
    }
    @Test public void metadataNetworkApiJsonAndMissingAssetFailuresFallBack()throws Exception {
        for(String failure:Arrays.asList("network","json","missing_asset","prerelease")) {
            Transport transport=transport(manifest(4,APK));String address="https://gitea.example/latest";
            if(failure.equals("network"))transport.text.remove(address);
            if(failure.equals("json"))transport.text.put(address,"not JSON");
            if(failure.equals("missing_asset"))transport.text.put(address,"{\"assets\":[]}");
            if(failure.equals("prerelease"))transport.text.put(address,new JSONObject(transport.text.get(address)).put("prerelease",true).toString());
            ReleaseResolver.Candidate candidate=resolver(new Trust(),new ArrayList<>()).resolve(chain(),transport,ReleaseResolverTest::validate);
            assertEquals(failure,"gitflic",candidate.source);Files.delete(candidate.apk.toPath());
        }
    }
    @Test public void tamperedSignedManifestDoesNotRaiseTrustFloor()throws Exception {
        Transport transport=transport(manifest(4,APK));JSONObject tampered=new JSONObject(transport.text.get("https://gitea.example/manifest")).put("version_code",999);
        transport.text.put("https://gitea.example/manifest",tampered.toString());Trust trust=new Trust();
        assertEquals("gitflic",resolver(trust,new ArrayList<>()).resolve(chain(),transport,ReleaseResolverTest::validate).source);assertEquals(4,trust.value.getInt("version_code"));
    }
    @Test public void failedOrCorruptApkTransferFallsBackAndRemovesPartial()throws Exception {
        for(boolean corrupt:Arrays.asList(false,true)) {
            Transport transport=transport(manifest(4,APK));if(corrupt)transport.bytes.put("https://gitea.example/apk",new byte[]{1,2,3});else transport.bytes.remove("https://gitea.example/apk");
            List<String> events=new ArrayList<>();ReleaseResolver.Candidate candidate=resolver(new Trust(),events).resolve(chain(),transport,ReleaseResolverTest::validate);
            assertEquals("gitflic",candidate.source);assertTrue(events.stream().anyMatch(event->event.startsWith("gitea:failed_download_")));assertFalse(new File(candidate.apk.getPath()+".part").exists());Files.delete(candidate.apk.toPath());
        }
    }
    @Test public void apkCertificateValidationFailureCanUseNewerValidMirror()throws Exception {
        Transport transport=transport(manifest(5,APK));transport.text.put("https://gitea.example/manifest",signed(4,APK,"c".repeat(64),CANONICAL).toString());
        List<String> events=new ArrayList<>();ReleaseResolver.Candidate candidate=resolver(new Trust(),events).resolve(chain(),transport,ReleaseResolverTest::validate);
        assertEquals("gitflic",candidate.source);assertEquals(5,candidate.manifest.versionCode);assertTrue(events.contains("gitea:failed_apk_SecurityException"));
    }
    @Test public void gitflicFailureContinuesToGithubAndUsesMirrorUrl()throws Exception {
        Transport transport=transport(manifest(4,APK));transport.text.remove("https://gitea.example/latest");transport.text.remove("https://gitflic.example/manifest");
        ReleaseResolver.Candidate candidate=resolver(new Trust(),new ArrayList<>()).resolve(chain(),transport,ReleaseResolverTest::validate);
        assertEquals("github",candidate.source);assertEquals(CANONICAL,candidate.manifest.url);assertTrue(transport.reads.contains("https://github.example/apk"));assertFalse(transport.reads.contains(CANONICAL));
    }
    @Test public void directGithubLatestAssetsAvoidRestApiAndKeepSignedCanonicalUrl()throws Exception {
        String manifestUrl="https://github.com/hawkab/distributed-workers-android/releases/latest/download/android-manifest.json",apkUrl="https://github.com/hawkab/distributed-workers-android/releases/latest/download/fleet-worker-android.apk";
        JSONArray config=new JSONArray();for(ReleaseSource source:chain())config.put(new JSONObject().put("id",source.id).put("type",source.type).put("url",source.url));
        config.getJSONObject(2).put("type","manifest").put("url",manifestUrl).put("apk_url",apkUrl);
        List<ReleaseSource> sources=ReleaseSource.parse(config.toString());Transport transport=new Transport();transport.text.put(manifestUrl,manifest(4,APK).toString());transport.bytes.put(apkUrl,APK);
        ReleaseResolver.Candidate candidate=resolver(new Trust(),new ArrayList<>()).resolve(sources,transport,ReleaseResolverTest::validate);
        assertEquals("github",candidate.source);assertEquals(CANONICAL,candidate.manifest.url);assertTrue(transport.reads.contains(manifestUrl));assertTrue(transport.reads.contains(apkUrl));assertFalse(transport.reads.stream().anyMatch(url->url.contains("api.github.com")));assertFalse(transport.reads.contains(CANONICAL));
        config.getJSONObject(2).put("apk_url","http://github.example/insecure.apk");try{ReleaseSource.parse(config.toString());fail();}catch(SecurityException expected){ }
    }
    @Test public void allFailuresProduceNoCandidateAndAreRetryable()throws Exception {
        Transport transport=new Transport();Trust trust=new Trust();List<String> events=new ArrayList<>();
        try{resolver(trust,events).resolve(chain(),transport,ReleaseResolverTest::validate);fail();}catch(ReleaseResolver.Unavailable expected){ }
        assertNull(trust.value);assertEquals(3,events.stream().filter(event->event.contains(":failed_")).count());
        assertEquals("gitea",resolver(trust,events).resolve(chain(),transport(manifest(4,APK)),ReleaseResolverTest::validate).source);
    }
    @Test public void authenticatedNewerVersionCannotFallBackToOlderRelease()throws Exception {
        Transport transport=transport(manifest(4,APK));transport.text.put("https://gitea.example/manifest",manifest(6,APK).toString());transport.bytes.remove("https://gitea.example/apk");Trust trust=new Trust();
        try{resolver(trust,new ArrayList<>()).resolve(chain(),transport,ReleaseResolverTest::validate);fail();}catch(ReleaseResolver.Unavailable expected){ }
        assertEquals(6,trust.value.getInt("version_code"));
        // The durable record is also consulted by the next polling attempt.
        try{resolver(trust,new ArrayList<>()).resolve(chain(),transport(manifest(4,APK)),ReleaseResolverTest::validate);fail();}catch(ReleaseResolver.Unavailable expected){ }
    }
    @Test public void signedSameVersionDifferentIdentityIsRejectedAcrossMirrors()throws Exception {
        Transport transport=transport(manifest(4,APK));transport.bytes.remove("https://gitea.example/apk");
        transport.text.put("https://gitflic.example/manifest",signed(4,APK,CERT,"https://different.example/canonical.apk").toString());transport.text.remove("https://github.example/latest");
        try{resolver(new Trust(),new ArrayList<>()).resolve(chain(),transport,ReleaseResolverTest::validate);fail();}catch(ReleaseResolver.Unavailable expected){ }
        assertFalse(transport.reads.contains("https://gitflic.example/apk"));
    }
    @Test public void noUpgradeDoesNotDownloadApk()throws Exception {
        Transport transport=transport(manifest(3,APK));assertNull(resolver(new Trust(),new ArrayList<>()).resolve(chain(),transport,ReleaseResolverTest::validate));
        for(String host:Arrays.asList("gitea","gitflic","github")){assertTrue(transport.reads.contains("https://"+host+".example/manifest"));assertFalse(transport.reads.contains("https://"+host+".example/apk"));}
    }
    @Test public void installedVersionOnPrimaryDoesNotConcealNewerSecondary()throws Exception {
        Transport transport=transport(manifest(5,APK));transport.text.put("https://gitea.example/manifest",manifest(4,APK).toString());Trust trust=new Trust();List<String> events=new ArrayList<>();
        ReleaseResolver resolver=new ReleaseResolver(key.getPublic().getEncoded(),4,temporary.getRoot(),trust,(source,state)->events.add(source+":"+state));
        ReleaseResolver.Candidate candidate=resolver.resolve(chain(),transport,ReleaseResolverTest::validate);
        assertEquals("gitflic",candidate.source);assertEquals(5,candidate.manifest.versionCode);assertEquals(5,trust.value.getInt("version_code"));assertTrue(events.contains("gitea:current"));assertFalse(transport.reads.contains("https://gitea.example/apk"));
    }
    @Test public void currentPrimaryThenNewerBrokenArtifactIsUnavailableRatherThanCurrent()throws Exception {
        Transport transport=transport(manifest(3,APK));transport.text.put("https://gitflic.example/manifest",manifest(4,APK).toString());transport.bytes.remove("https://gitflic.example/apk");Trust trust=new Trust();List<String> events=new ArrayList<>();
        try{resolver(trust,events).resolve(chain(),transport,ReleaseResolverTest::validate);fail();}catch(ReleaseResolver.Unavailable expected){ }
        assertTrue(events.contains("gitea:current"));assertEquals(4,trust.value.getInt("version_code"));assertTrue(events.contains("github:failed_manifest_SecurityException"));assertFalse(transport.reads.contains("https://github.example/apk"));
    }
    @Test public void publicConfigurationRefusesCredentialsHttpAndWrongChain()throws Exception {
        JSONArray config=new JSONArray();for(ReleaseSource source:chain())config.put(new JSONObject().put("id",source.id).put("type",source.type).put("url",source.url));
        assertEquals(3,ReleaseSource.parse(config.toString()).size());
        for(String address:Arrays.asList("http://example.org/a","https://token@example.org/a","https://example.org/a#x"))try{ReleaseSource.publicHttps(address);fail(address);}catch(SecurityException expected){ }
        config.getJSONObject(0).put("token","forbidden");try{ReleaseSource.parse(config.toString());fail();}catch(SecurityException expected){ }
        config.getJSONObject(0).remove("token");config.getJSONObject(0).put("url","https://example.org/latest?access_token=forbidden");try{ReleaseSource.parse(config.toString());fail();}catch(SecurityException expected){ }
        config.getJSONObject(0).put("url","https://example.org/latest");config.getJSONObject(0).put("id","github");try{ReleaseSource.parse(config.toString());fail();}catch(IllegalArgumentException expected){ }
    }
    @Test public void ambiguousAssetsAndCleartextChannelTargetsAreRefused()throws Exception {
        Transport transport=transport(manifest(4,APK));JSONObject primary=new JSONObject(transport.text.get("https://gitea.example/latest"));primary.getJSONArray("assets").put(primary.getJSONArray("assets").getJSONObject(0));transport.text.put("https://gitea.example/latest",primary.toString());
        transport.text.put("https://gitflic.example/channel","{\"manifest_url\":\"http://insecure.example/m\",\"apk_url\":\"https://gitflic.example/apk\"}");
        assertEquals("github",resolver(new Trust(),new ArrayList<>()).resolve(chain(),transport,ReleaseResolverTest::validate).source);
    }
}
