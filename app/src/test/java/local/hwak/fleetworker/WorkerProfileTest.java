package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class WorkerProfileTest {
    @Test public void quantitiesEncodeAsUnpaddedBase64UrlWithNoHardwareIdentity()throws Exception {
        JSONObject profile=WorkerProfile.document(new JSONArray().put("sha256").put("http.request"),8,4.0,2147483648L,33554432L,64000000000L,123456789L);
        String encoded=WorkerProfile.encode(profile);assertFalse(encoded.contains("="));assertTrue(encoded.matches("[A-Za-z0-9_-]+"));
        JSONObject decoded=new JSONObject(new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8));
        assertEquals(1,decoded.getInt("schema"));assertEquals("http.request",decoded.getJSONArray("capabilities").getString(1));
        JSONObject resources=decoded.getJSONObject("resources");assertEquals(8,resources.getInt("cpu_logical_cores"));assertEquals(4.0,resources.getDouble("cpu_available_cores"),0);
        assertEquals(33554432L,resources.getLong("ram_available_bytes"));assertEquals("unknown",resources.getString("gpu_status"));assertEquals(0,resources.getJSONArray("gpus").length());
        for(String forbidden:new String[]{"hostname","model","serial","device","fingerprint","unified_memory"})assertFalse(decoded.toString().contains('"'+forbidden+'"'));
        assertEquals(8,resources.length());
    }
    @Test public void unknownValuesRemainNullAndNeverBecomeZeroGpuCounts()throws Exception {
        JSONObject resources=WorkerProfile.document(new JSONArray(),null,null,null,null,null,null).getJSONObject("resources");
        for(String key:new String[]{"cpu_logical_cores","cpu_available_cores","ram_total_bytes","ram_available_bytes","disk_total_bytes","disk_available_bytes"})assertTrue(resources.isNull(key));
        assertEquals("unknown",resources.getString("gpu_status"));assertFalse(resources.has("gpu_count"));
    }
    @Test public void availableRamHonorsBothOsMemoryAndTheActualAppHeapBudget() {
        assertEquals(Long.valueOf(48),WorkerProfile.availableMemory(1024L,128,100,20));
        assertEquals(Long.valueOf(32),WorkerProfile.availableMemory(32L,128,100,20));
        assertEquals(Long.valueOf(0),WorkerProfile.availableMemory(1024L,128,150,0));
        assertEquals(Long.valueOf(48),WorkerProfile.availableMemory(null,128,100,20));
        assertNull(WorkerProfile.availableMemory(null,0,0,0));
    }
    @Test public void processAffinityIsCountedWithoutDisclosingCpuIdentifiers() {
        assertEquals(Integer.valueOf(6),WorkerProfile.affinityCount("0-3,6,8"));assertEquals(Integer.valueOf(1),WorkerProfile.affinityCount("7"));
        for(String invalid:new String[]{"","3-1","0-2,2-3","4,1","0,","not-known","0-9223372036854775807"})assertNull(invalid,WorkerProfile.affinityCount(invalid));
    }
    @Test public void headerIsAttachedOnlyToConnectionWorkerHeartbeatAndClaim()throws Exception {
        JSONArray defaults=new JSONArray().put("default"),advertised=new JSONArray().put("claim-capability");int[] calls={0};
        Api.Profiles profiles=caps->{calls[0]++;return caps.toString();};
        assertEquals(defaults.toString(),Api.profileHeader("/v1/workers/connect",new JSONObject(),defaults,profiles));
        assertEquals(advertised.toString(),Api.profileHeader("/v1/workers/heartbeat",new JSONObject().put("capabilities",advertised),defaults,profiles));
        assertEquals(advertised.toString(),Api.profileHeader("/v1/jobs/claim",new JSONObject().put("capabilities",advertised),defaults,profiles));
        for(String route:new String[]{"/v1/artifacts/id/chunks/0","/v1/artifacts/id/finish","/v1/jobs/id/heartbeat","/v1/jobs/id/complete","/v1/jobs/id/effects/e/begin"})assertNull(Api.profileHeader(route,new JSONObject(),defaults,profiles));
        assertEquals(3,calls[0]);
    }
}
