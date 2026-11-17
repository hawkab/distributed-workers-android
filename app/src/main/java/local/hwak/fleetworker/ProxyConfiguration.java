package local.hwak.fleetworker;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Operator-only private configuration. Downloaded feed entries are never auto-verified. */
final class ProxyConfiguration {
    private static final AtomicBoolean refreshing=new AtomicBoolean();
    private static volatile long nextRefresh;
    static TaskProxies load(Context c)throws Exception {
        android.content.SharedPreferences p=c.getSharedPreferences("task_proxy",Context.MODE_PRIVATE);
        String encoded=p.getString("configuration","");if(encoded.isEmpty())return TaskProxies.direct();
        JSONObject config=new JSONObject(encoded),cache,health;
        try{cache=readObject(config.optString("cache_file",""));}catch(Exception ignored){cache=new JSONObject();}
        try{health=new JSONObject(p.getString("health","{}"));}catch(Exception ignored){health=new JSONObject();}
        TaskProxies proxies=new TaskProxies(config,cache,health,value->{if(!p.edit().putString("health",value.toString()).commit())throw new IllegalStateException("proxy_health_write_failed");});
        if(!config.optString("sources_file","").isEmpty())refresh(c,config);
        return proxies;
    }
    static void save(Context c,String encoded,String sources,String cache)throws Exception {
        JSONObject config=new JSONObject(encoded);File dir=c.getFilesDir();
        if(sources!=null){parseSources(sources);File file=new File(dir,"proxy-sources.txt");atomic(file,sources);config.put("sources_file",file.getAbsolutePath());}
        if(cache!=null){new JSONObject(cache);File file=new File(dir,"proxy-cache.json");atomic(file,cache);config.put("cache_file",file.getAbsolutePath());}
        JSONObject known;try{known=readObject(config.optString("cache_file",""));}catch(Exception ignored){known=new JSONObject();}
        new TaskProxies(config,known,new JSONObject(),v->{});
        if(!c.getSharedPreferences("task_proxy",Context.MODE_PRIVATE).edit().putString("configuration",config.toString()).commit())throw new IllegalStateException("proxy_config_write_failed");
        nextRefresh=0;
    }
    static JSONArray parseSources(String text)throws Exception {
        JSONArray result=new JSONArray();String value=text.trim();
        if(value.startsWith("[")){JSONArray source=new JSONArray(value);for(int i=0;i<source.length();i++){Object row=source.get(i);result.put(row instanceof String?new JSONObject().put("url",row):new JSONObject(((JSONObject)row).toString()));}}
        else for(String line:value.split("\\r?\\n")){line=line.trim();if(!line.isEmpty()&&!line.startsWith("#"))result.put(new JSONObject().put("url",line));}
        for(int i=0;i<result.length();i++)ReleaseSource.publicHttps(result.getJSONObject(i).getString("url"));return result;
    }
    private static JSONObject readObject(String path)throws Exception {if(path.isEmpty())return new JSONObject();try(InputStream in=new FileInputStream(path)){return new JSONObject(Api.read(in));}}
    private static void refresh(Context context,JSONObject config) {
        long now=System.currentTimeMillis();if(now<nextRefresh||!refreshing.compareAndSet(false,true))return;
        // Feed refresh does not gate jobs. The verified private cache is the activation authority.
        Thread thread=new Thread(()-> {
            try {
                JSONArray sources;try(InputStream in=new FileInputStream(config.getString("sources_file"))){sources=parseSources(Api.read(in));}
                JSONArray candidates=new JSONArray();
                for(int i=0;i<sources.length();i++)try{JSONObject source=sources.getJSONObject(i);JSONArray found=TaskProxies.candidates(download(source.getString("url")),source.optString("scheme",""));for(int j=0;j<found.length();j++)candidates.put(found.get(j));}catch(Exception ignored){/* preserve usable verified routes if an anonymous source is down */}
                atomic(new File(context.getFilesDir(),"proxy-candidates.json"),new JSONObject().put("candidates",candidates).toString());
            }catch(Exception ignored){/* diagnostics cannot disable ordinary task execution */}
            finally{nextRefresh=System.currentTimeMillis()+3600000;refreshing.set(false);}
        },"proxy-sources");thread.setDaemon(true);thread.start();
    }
    static String download(String address)throws Exception {
        Set<String> visited=new HashSet<>();String current=address;
        while(true) {
            ReleaseSource.publicHttps(current);if(!visited.add(current))throw new java.io.IOException("proxy_source_redirect_cycle");
            HttpsURLConnection connection=Api.openDirect(current);
            connection.setConnectTimeout(15000);connection.setReadTimeout(60000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","DistributedWorker/1");connection.setRequestProperty("Accept","application/json, text/plain, */*");
            try {
                int status=connection.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){String location=connection.getHeaderField("Location");if(location==null)throw new java.io.IOException("proxy_source_redirect_missing");current=URI.create(current).resolve(location).toString();continue;}
                if(status!=200)throw new java.io.IOException("proxy_source_unavailable");try(InputStream in=connection.getInputStream()){return Api.read(in);}
            }finally{connection.disconnect();}
        }
    }
    private static void atomic(File file,String text)throws Exception {
        File staging=new File(file.getParentFile(),file.getName()+".pending");
        try(FileOutputStream out=new FileOutputStream(staging)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!staging.renameTo(file))throw new java.io.IOException("proxy_private_write_failed");
    }
}
