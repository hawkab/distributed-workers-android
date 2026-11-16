package local.hwak.fleetworker;

import org.json.JSONObject;

/** Durable small HTTP-response metadata; binary bytes are already on the gateway. */
final class EffectOutbox {
    interface Persistence {
        void put(String key,JSONObject record);
        JSONObject next();
        void acknowledge(String key);
        void quarantine(JSONObject record,String reason);
    }
    interface Sender { JSONObject send(String path,JSONObject body)throws Exception; }
    static final class DeliveryRejected extends IllegalStateException {DeliveryRejected(int status){super("effect_delivery_rejected_"+status);}}
    private final Persistence persistence;
    EffectOutbox(Persistence persistence){this.persistence=persistence;}
    JSONObject record(String path,JSONObject body)throws Exception {
        String key=body.getString("attempt_id")+":"+body.getString("execution_id");
        JSONObject record=new JSONObject().put("key",key).put("path",path).put("body",body);
        persistence.put(key,record);return record;
    }
    void deliver(JSONObject record,Sender sender)throws Exception {
        JSONObject body=record.getJSONObject("body"),ack;
        try{ack=sender.send(record.getString("path"),body);}
        catch(Api.Failure e){
            if(e.status==400||e.status==404||e.status==409||e.status==413||e.status==422){persistence.quarantine(record,"http_"+e.status);throw new DeliveryRejected(e.status);}
            throw e;
        }
        if(ack==null||!ack.optBoolean("recorded")||!body.getString("execution_id").equals(ack.optString("execution_id")))throw new IllegalStateException("effect_not_acknowledged");
        persistence.acknowledge(record.getString("key"));
    }
    boolean flushOne(Sender sender)throws Exception {JSONObject record=persistence.next();if(record==null)return false;try{deliver(record,sender);}catch(DeliveryRejected retained){}return true;}
}
