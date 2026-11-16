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
        if (workerId == null || !workerId.matches("[A-Za-z0-9._-]+") || token == null || token.length() < 24 || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0)
            throw new IllegalArgumentException("invalid_identity");
        this.baseUrl = baseUrl.replaceAll("/+$", ""); this.workerId = workerId; this.token = token;
    }
    static Configuration load(Context c) {
        SharedPreferences p = c.getSharedPreferences("identity", Context.MODE_PRIVATE);
        String base = p.getString("base_url", ""), id = p.getString("worker_id", ""), token = p.getString("token", "");
        if (base.isEmpty() || id.isEmpty() || token.isEmpty()) return null;
        return new Configuration(base, id, token);
    }
    void save(Context c) {
        Configuration old = load(c);
        if (old != null && (!old.workerId.equals(workerId) || !old.baseUrl.equals(baseUrl)) && Store.get(c).hasWork()) throw new IllegalStateException("pending_work_identity");
        if (!c.getSharedPreferences("identity", Context.MODE_PRIVATE).edit().putString("base_url", baseUrl).putString("worker_id", workerId).putString("token", token).commit())
            throw new IllegalStateException("identity_write_failed");
    }
}
