package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.io.IOException;
import org.json.JSONObject;
import org.junit.Test;

public class EffectOutboxTest {
    static final class Disk implements EffectOutbox.Persistence {
        String durable;int writes,acks;
        public void put(String key,JSONObject record){durable=record.toString();writes++;}
        public JSONObject next(){try{return durable==null?null:new JSONObject(durable);}catch(Exception e){throw new AssertionError(e);}}
        public void acknowledge(String key){assertNotNull(durable);durable=null;acks++;}
    }
    static JSONObject body()throws Exception{return new JSONObject().put("attempt_id","attempt").put("execution_id","nonce").put("response",new JSONObject().put("status",201).put("body_text","actual response"));}
    @Test public void lostAcknowledgementSurvivesNewOutboxAndReplaysExactResponseOnly()throws Exception {
        Disk disk=new Disk();EffectOutbox first=new EffectOutbox(disk);JSONObject record=first.record("/v1/jobs/job/effects/write/finish",body());
        try{first.deliver(record,(path,value)->{assertEquals(1,disk.writes);assertNotNull(disk.durable);throw new IOException("ack lost after server commit");});fail();}catch(IOException expected){}
        EffectOutbox restarted=new EffectOutbox(disk);
        assertTrue(restarted.flushOne((path,value)->{assertEquals("/v1/jobs/job/effects/write/finish",path);assertEquals(body().toString(),value.toString());return new JSONObject().put("recorded",true).put("execution_id","nonce");}));
        assertEquals(1,disk.acks);assertFalse(restarted.flushOne((p,b)->{throw new AssertionError("already acknowledged");}));
    }
    @Test public void MismatchedReceiptCannotDiscardResponseAndCandidateReceiptCan()throws Exception {
        Disk disk=new Disk();EffectOutbox box=new EffectOutbox(disk);JSONObject record=box.record("/finish",body());
        try{box.deliver(record,(p,b)->new JSONObject().put("recorded",true).put("execution_id","foreign"));fail();}catch(IllegalStateException expected){}
        assertNotNull(disk.durable);assertEquals(0,disk.acks);
        box.deliver(record,(p,b)->new JSONObject().put("recorded",true).put("candidate_saved",true).put("execution_id","nonce"));assertNull(disk.durable);
    }
}
