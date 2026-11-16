package local.hwak.fleetworker;

import android.content.Context;
import java.io.InputStream;
import java.util.List;

/** Public endpoints only; changed by the DUMP-protected operator receiver. */
final class ReleaseConfiguration {
    static List<ReleaseSource> load(Context context)throws Exception {
        String saved=context.getSharedPreferences("identity",Context.MODE_PRIVATE).getString("release_sources","");
        if(saved.isEmpty())try(InputStream in=context.getAssets().open("release-sources.json")){saved=Api.read(in);}
        return ReleaseSource.parse(saved);
    }
    static void save(Context context,String encoded)throws Exception {
        ReleaseSource.parse(encoded);
        if(!context.getSharedPreferences("identity",Context.MODE_PRIVATE).edit().putString("release_sources",encoded).commit())throw new IllegalStateException("release_config_write_failed");
    }
}
