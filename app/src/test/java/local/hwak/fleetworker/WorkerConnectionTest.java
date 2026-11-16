package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class WorkerConnectionTest {
    private static final String SERVER_ID="e1b5adc8-69ba-4da0-8f03-7c16a943808f";
    private static final class Memory implements WorkerConnection.Persistence {
        Configuration current=new Configuration("https://example.invalid/fleet",null,"enrollment-fixture");
        boolean pending,failWrite;final List<String> events=new ArrayList<>();
        public Configuration load(){return current;}
        public Configuration assign(Configuration expected,String id){
            if(!expected.sameIdentity(current))throw new IllegalStateException("configuration_changed_during_connect");
            Configuration next=Configuration.confirmed(current,id,pending);
            if(failWrite)throw new IllegalStateException("identity_write_failed");
            events.add("persist");current=next;return next;
        }
        public void policy(JSONObject response){events.add("policy");}
    }
    private static JSONObject ack(String id)throws Exception{return new JSONObject().put("worker_id",id).put("profile_schema",1).put("update_poll_seconds",900);}
    @Test public void tokenOnlyConnectPersistsServerIdBeforeCallerCanClaim()throws Exception {
        Memory state=new Memory();WorkerConnection connection=new WorkerConnection();
        Configuration result=connection.ensure(state,config->{assertEquals("",config.workerId);state.events.add("connect");return ack(SERVER_ID);});
        state.events.add("claim");assertEquals(SERVER_ID,result.workerId);assertEquals(List.of("connect","persist","policy","claim"),state.events);
        assertEquals(SERVER_ID,state.current.workerId);
    }
    @Test public void failedDurableWriteNeverReturnsConnectedIdentityAndCanRecover()throws Exception {
        Memory state=new Memory();state.failWrite=true;WorkerConnection connection=new WorkerConnection();
        try{connection.ensure(state,config->ack(SERVER_ID));fail();}catch(IllegalStateException expected){assertEquals("identity_write_failed",expected.getMessage());}
        assertEquals("",state.current.workerId);assertTrue(state.events.isEmpty());
        state.failWrite=false;assertEquals(SERVER_ID,connection.ensure(state,config->ack(SERVER_ID)).workerId);
    }
    @Test public void stableIdentityIsCachedButCredentialRotationReconnects()throws Exception {
        Memory state=new Memory();WorkerConnection connection=new WorkerConnection();int[] calls={0};
        WorkerConnection.Transport transport=config->{calls[0]++;return ack(SERVER_ID);};
        connection.ensure(state,transport);connection.ensure(state,transport);assertEquals(1,calls[0]);
        state.current=new Configuration(state.current.baseUrl,state.current.workerId,"rotated-fixture");
        connection.ensure(state,transport);assertEquals(2,calls[0]);assertEquals(SERVER_ID,state.current.workerId);
    }
    @Test public void rejectedAuthenticationInvalidatesTheCacheForReconciliation()throws Exception {
        Memory state=new Memory();WorkerConnection connection=new WorkerConnection();int[] calls={0};
        WorkerConnection.Transport remote=c->{calls[0]++;return ack(SERVER_ID);};
        connection.ensure(state,remote);connection.invalidate();connection.ensure(state,remote);
        assertEquals(2,calls[0]);assertEquals(SERVER_ID,state.current.workerId);
    }
    @Test public void legacyIdAndPendingWorkRemainBoundToTheirExistingCredential()throws Exception {
        Memory state=new Memory();state.current=new Configuration("https://example.invalid/fleet","legacy-worker","legacy-fixture");state.pending=true;
        assertEquals("legacy-worker",new WorkerConnection().ensure(state,config->ack("legacy-worker")).workerId);
        try{new WorkerConnection().ensure(state,config->ack(SERVER_ID));fail();}catch(IllegalStateException expected){assertEquals("pending_work_identity",expected.getMessage());}
        assertEquals("legacy-worker",state.current.workerId);
    }
    @Test public void responseForReplacedConfigurationCannotOverwriteNewCredential()throws Exception {
        Memory state=new Memory();WorkerConnection connection=new WorkerConnection();
        try{connection.ensure(state,config->{state.current=new Configuration(config.baseUrl,"","new-token");return ack(SERVER_ID);});fail();}
        catch(IllegalStateException expected){assertEquals("configuration_changed_during_connect",expected.getMessage());}
        assertEquals("new-token",state.current.token);assertEquals("",state.current.workerId);
    }
    @Test public void unsupportedSchemaAndMissingIdNeverPersist()throws Exception {
        for(JSONObject response:new JSONObject[]{new JSONObject().put("profile_schema",2).put("worker_id",SERVER_ID),new JSONObject().put("profile_schema",1).put("worker_id","")}){
            Memory state=new Memory();try{new WorkerConnection().ensure(state,c->response);fail();}catch(Exception expected){}
            assertEquals("",state.current.workerId);assertTrue(state.events.isEmpty());
        }
    }
    @Test public void absentConfigurationDoesNotConnect()throws Exception {
        Memory state=new Memory();state.current=null;
        assertNull(new WorkerConnection().ensure(state,c->{throw new AssertionError("must not connect");}));
    }
}
