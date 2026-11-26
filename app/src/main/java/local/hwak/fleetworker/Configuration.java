package local.hwak.fleetworker;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;

final class Configuration {
    final String baseUrl, workerId, token;
    Configuration(String baseUrl, String workerId, String token) {
        URI uri = URI.create(baseUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("invalid_base_url");
        String id=workerId==null?"":workerId;
        if ((!id.isEmpty()&&!id.matches("[A-Za-z0-9._-]+")) || token == null || token.isEmpty() || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0)
            throw new IllegalArgumentException("invalid_identity");
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.workerId = id; this.token = token;
    }
    boolean sameIdentity(Configuration other) {
        return other!=null && baseUrl.equals(other.baseUrl) && workerId.equals(other.workerId) && token.equals(other.token);
    }
    static synchronized Configuration load(Context c) {
        SharedPreferences p = c.getSharedPreferences("identity", Context.MODE_PRIVATE);
        String base = p.getString("base_url", ""), id = p.getString("worker_id", ""), token = p.getString("token", "");
        if (base.isEmpty() || token.isEmpty()) return null;
        return new Configuration(base, id, token);
    }
    void save(Context c) {
        synchronized(Configuration.class) {
            Configuration old=load(c);
            String id=workerId.isEmpty()&&old!=null&&old.baseUrl.equals(baseUrl)?old.workerId:workerId;
            Configuration next=new Configuration(baseUrl,id,token);
            ensurePendingIdentity(old,next,Store.hasAnyWork(c));
            persist(c,next);
        }
    }
    static synchronized Configuration acceptServerId(Context c,Configuration expected,String assigned) {
        Configuration current=load(c);
        if(!expected.sameIdentity(current))throw new IllegalStateException("configuration_changed_during_connect");
        Configuration confirmed=confirmed(expected,assigned,Store.hasAnyWork(c));
        persist(c,confirmed);
        return confirmed;
    }
    static Configuration confirmed(Configuration current,String assigned,boolean pending) {
        if(assigned==null||assigned.isEmpty())throw new IllegalArgumentException("server_worker_id_required");
        Configuration next=new Configuration(current.baseUrl,assigned,current.token);
        ensurePendingIdentity(current,next,pending);
        return next;
    }
    private static void ensurePendingIdentity(Configuration old,Configuration next,boolean pending) {
        if(pending&&old!=null&&(!old.workerId.equals(next.workerId)||!old.baseUrl.equals(next.baseUrl)))throw new IllegalStateException("pending_work_identity");
    }
    private static void persist(Context c,Configuration next) {
        if(!c.getSharedPreferences("identity",Context.MODE_PRIVATE).edit().putString("base_url",next.baseUrl).putString("worker_id",next.workerId).putString("token",next.token).commit())
            throw new IllegalStateException("identity_write_failed");
    }
}
