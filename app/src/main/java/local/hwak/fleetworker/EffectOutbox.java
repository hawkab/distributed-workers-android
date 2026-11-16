package local.hwak.fleetworker;

import org.json.JSONObject;

/** Durable small HTTP-response metadata; binary bytes are already on the gateway. */
final class EffectOutbox {
    interface Persistence {
        void put(String key,JSONObject record);
        JSONObject next();
        void acknowledge(String key);
    }
    interface Sender { JSONObject send(String path,JSONObject body)throws Exception; }
    private final Persistence persistence;
    EffectOutbox(Persistence persistence){this.persistence=persistence;}
    JSONObject record(String path,JSONObject body)throws Exception {
        String key=body.getString("attempt_id")+":"+body.getString("execution_id");
        JSONObject record=new JSONObject().put("key",key).put("path",path).put("body",body);
        persistence.put(key,record);return record;
    }
    void deliver(JSONObject record,Sender sender)throws Exception {
        JSONObject body=record.getJSONObject("body"),ack=sender.send(record.getString("path"),body);
        if(ack==null||!ack.optBoolean("recorded")||!body.getString("execution_id").equals(ack.optString("execution_id")))throw new IllegalStateException("effect_not_acknowledged");
        persistence.acknowledge(record.getString("key"));
    }
    boolean flushOne(Sender sender)throws Exception {JSONObject record=persistence.next();if(record==null)return false;deliver(record,sender);return true;}
}
