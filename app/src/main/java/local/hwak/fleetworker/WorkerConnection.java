package local.hwak.fleetworker;

import org.json.JSONObject;

/** Serializes initial authenticated connection; no caller receives an unpersisted ID. */
final class WorkerConnection {
    interface Persistence {
        Configuration load();
        Configuration assign(Configuration expected,String workerId)throws Exception;
        void policy(JSONObject response)throws Exception;
    }
    interface Transport { JSONObject connect(Configuration configuration)throws Exception; }
    private Configuration connected;
    synchronized void invalidate(){connected=null;}
    synchronized Configuration ensure(Persistence persistence,Transport transport)throws Exception {
        Configuration configured=persistence.load();
        if(configured==null)return null;
        if(!configured.workerId.isEmpty()&&configured.sameIdentity(connected))return configured;
        JSONObject response=transport.connect(configured);
        if(response==null||response.optInt("profile_schema",0)!=1)throw new IllegalStateException("connect_profile_schema_invalid");
        Configuration assigned=persistence.assign(configured,response.getString("worker_id"));
        persistence.policy(response);
        connected=assigned;
        return assigned;
    }
}
