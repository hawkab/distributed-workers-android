package local.hwak.fleetworker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.net.SocketFactory;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private task egress only. No system ProxySelector or global Authenticator is changed. */
final class TaskProxies {
    static final class Endpoint {
        final String scheme,host,url,username,password,hash;final int port;
        Endpoint(JSONObject value)throws Exception {
            URI u=URI.create(value.getString("url"));scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);
            if(!scheme.equals("http")&&!scheme.equals("socks5")&&!scheme.equals("socks5h"))throw new IllegalArgumentException("proxy_scheme_not_supported");
            if(u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||(u.getPath()!=null&&!u.getPath().isEmpty()&&!u.getPath().equals("/")))throw new IllegalArgumentException("invalid_proxy_url");
            host=u.getHost().replaceAll("^\\[|\\]$","").toLowerCase(Locale.ROOT);port=u.getPort()==-1?(scheme.equals("http")?80:1080):u.getPort();
            if(port<1||port>65535)throw new IllegalArgumentException("invalid_proxy_port");
            username=value.optString("username","");password=value.optString("password","");
            if(username.contains("\r")||username.contains("\n")||password.contains("\r")||password.contains("\n"))throw new IllegalArgumentException("invalid_proxy_credentials");
            if(!scheme.equals("http")&&(username.getBytes(StandardCharsets.UTF_8).length>255||password.getBytes(StandardCharsets.UTF_8).length>255))throw new IllegalArgumentException("socks_credentials_too_long");
            url=scheme+"://"+(host.contains(":")?"["+host+"]":host)+":"+port;
            hash=Operations.hex(MessageDigest.getInstance("SHA-256").digest((url+"\n"+username+"\n"+password).getBytes(StandardCharsets.UTF_8)));
        }
        JSONObject json()throws Exception{return new JSONObject().put("url",url).put("username",username).put("password",password);}
        void apply(OkHttpClient.Builder client) {
            if(scheme.equals("http")) {
                client.proxy(new Proxy(Proxy.Type.HTTP,new InetSocketAddress(host,port)));
                if(!username.isEmpty()||!password.isEmpty())client.proxyAuthenticator((route,response)-> {
                    if(response.request().header("Proxy-Authorization")!=null)return null;
                    return response.request().newBuilder().header("Proxy-Authorization",Credentials.basic(username,password,StandardCharsets.UTF_8)).build();
                });
            } else {
                // Carry the original DNS name through OkHttp without resolving it locally.
                client.proxy(Proxy.NO_PROXY).dns(name->java.util.Collections.singletonList(InetAddress.getByAddress(name,new byte[]{0,0,0,0}))).socketFactory(new SocksFactory(this));
            }
        }
    }
    private final String mode;private final List<Endpoint> endpoints;private final JSONObject health;private final Consumer<JSONObject> persist;
    TaskProxies(JSONObject config,JSONObject cache,JSONObject health,Consumer<JSONObject> persist)throws Exception {
        mode=config.optString("mode","off");if(!mode.equals("off")&&!mode.equals("prefer")&&!mode.equals("required"))throw new IllegalArgumentException("invalid_proxy_mode");
        this.health=health;this.persist=persist;JSONObject sharedHealth=cache.optJSONObject("health");
        if(sharedHealth!=null){java.util.Iterator<String> names=sharedHealth.keys();while(names.hasNext()){String key=names.next();if(!health.has(key))health.put(key,sharedHealth.get(key));}}
        LinkedHashMap<String,Endpoint> available=new LinkedHashMap<>();
        JSONArray servers=config.optJSONArray("servers");if(servers!=null)for(int i=0;i<servers.length();i++){Endpoint e=new Endpoint(servers.getJSONObject(i));available.put(e.hash,e);}
        String country=config.optString("required_country","").toUpperCase(Locale.ROOT);
        if(!country.isEmpty()&&!country.matches("[A-Z]{2}"))throw new IllegalArgumentException("invalid_proxy_country");
        JSONArray verified=cache.optJSONArray("verified");if(verified!=null)for(int i=0;i<verified.length();i++) {
            JSONObject v=verified.optJSONObject(i);if(v==null)continue;
            if(v.optString("verified_at","").isEmpty()||v.optString("exit_ip","").isEmpty()||v.optString("exit_country","").isEmpty()||(!country.isEmpty()&&!country.equalsIgnoreCase(v.optString("exit_country"))))continue;
            try{java.time.Instant.parse(v.getString("verified_at"));Endpoint e=new Endpoint(v);available.put(e.hash,e);}catch(Exception ignored){/* malformed metadata or unsupported protocols are not active routes */}
        }
        endpoints=new ArrayList<>(available.values());
    }
    static TaskProxies direct(){try{return new TaskProxies(new JSONObject(),new JSONObject(),new JSONObject(),v->{});}catch(Exception e){throw new AssertionError(e);}}
    boolean required(){return mode.equals("required");}
    Endpoint select(long now)throws IOException {
        if(mode.equals("off"))return null;
        for(Endpoint e:endpoints){JSONObject h=health.optJSONObject(e.hash);if(h==null||h.optLong("retry_after_ms",0)<=now)return e;}
        if(mode.equals("prefer"))return null;
        throw new IOException("proxy_required_unavailable");
    }
    void succeeded(Endpoint endpoint){if(endpoint==null)return;update(endpoint,false);}
    void failed(Endpoint endpoint){if(endpoint==null)return;update(endpoint,true);}
    private synchronized void update(Endpoint endpoint,boolean failed) {
        try {
            JSONObject old=health.optJSONObject(endpoint.hash),h=old==null?new JSONObject():new JSONObject(old.toString());
            long now=System.currentTimeMillis();int failures=failed?Math.min(30,h.optInt("failures",0)+1):0;
            h.put("failures",failures).put("retry_after_ms",failed?now+Math.min(60000L,1000L<<(Math.min(failures-1,6))):0);
            if(!failed)h.put("last_ok",now);health.put(endpoint.hash,h);persist.accept(new JSONObject(health.toString()));
        }catch(Exception ignored){/* advisory health cannot invalidate a durably recorded task result */}
    }
    /** Feed labels are candidates, never proof of usable egress or country. */
    static JSONArray candidates(String payload,String scheme)throws Exception {
        JSONArray output=new JSONArray();String value=payload.trim();
        if(value.startsWith("[")) {
            JSONArray rows=new JSONArray(value);for(int i=0;i<rows.length();i++) {
                Object row=rows.get(i);String url;
                if(row instanceof String)url=(String)row;
                else {JSONObject r=(JSONObject)row;url=r.optString("proxy","");if(url.isEmpty()&&r.has("host"))url=r.optString("protocol",scheme)+"://"+r.getString("host")+":"+r.getInt("port");}
                if(!url.isEmpty())output.put(new JSONObject().put("url",url));
            }
        } else for(String line:value.split("\\r?\\n")){line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;if(!line.contains("://")){if(scheme==null||scheme.isEmpty())continue;line=scheme+"://"+line;}output.put(new JSONObject().put("url",line));}
        return output;
    }
    static SocketFactory directSockets(){return new DirectFactory();}
    private static class DirectFactory extends SocketFactory {
        @Override public Socket createSocket(){return new Socket(Proxy.NO_PROXY);}
        @Override public Socket createSocket(String h,int p)throws IOException{Socket s=createSocket();s.connect(new InetSocketAddress(h,p));return s;}
        @Override public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{Socket s=createSocket();s.bind(new InetSocketAddress(l,lp));s.connect(new InetSocketAddress(h,p));return s;}
        @Override public Socket createSocket(InetAddress h,int p)throws IOException{return createSocket(h.getHostAddress(),p);}
        @Override public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return createSocket(h.getHostAddress(),p,l,lp);}
    }
    private static final class SocksFactory extends DirectFactory {
        final Endpoint endpoint;SocksFactory(Endpoint e){endpoint=e;}
        @Override public Socket createSocket(){return new SocksSocket(endpoint);}
        @Override public Socket createSocket(String h,int p)throws IOException{Socket s=createSocket();s.connect(InetSocketAddress.createUnresolved(h,p));return s;}
        @Override public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{Socket s=createSocket();s.bind(new InetSocketAddress(l,lp));s.connect(InetSocketAddress.createUnresolved(h,p));return s;}
        @Override public Socket createSocket(InetAddress h,int p)throws IOException{return createSocket(h.getHostName(),p);}
        @Override public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return createSocket(h.getHostName(),p,l,lp);}
    }
    private static final class SocksSocket extends Socket {
        final Endpoint endpoint;SocksSocket(Endpoint e){super(Proxy.NO_PROXY);endpoint=e;}
        @Override public void connect(SocketAddress target)throws IOException{connect(target,0);}
        @Override public void connect(SocketAddress target,int timeout)throws IOException {
            if(!(target instanceof InetSocketAddress))throw new IOException("invalid_socks_target");
            InetSocketAddress destination=(InetSocketAddress)target;super.connect(new InetSocketAddress(endpoint.host,endpoint.port),timeout);
            int previous=getSoTimeout();setSoTimeout(timeout);
            try {
                InputStream in=getInputStream();OutputStream out=getOutputStream();boolean auth=!endpoint.username.isEmpty()||!endpoint.password.isEmpty();
                out.write(new byte[]{5,1,(byte)(auth?2:0)});out.flush();
                if(read(in)!=5||read(in)!=(auth?2:0))throw new IOException("proxy_authentication_failed");
                if(auth){byte[] u=endpoint.username.getBytes(StandardCharsets.UTF_8),p=endpoint.password.getBytes(StandardCharsets.UTF_8);out.write(1);out.write(u.length);out.write(u);out.write(p.length);out.write(p);out.flush();if(read(in)!=1||read(in)!=0)throw new IOException("proxy_authentication_failed");}
                String targetHost=destination.getHostString().replaceAll("^\\[|\\]$","");
                if(targetHost.contains(":")){byte[] address=InetAddress.getByName(targetHost).getAddress();if(address.length!=16)throw new IOException("invalid_socks_target");out.write(new byte[]{5,1,0,4});out.write(address);}
                else {byte[] name=IDN.toASCII(targetHost).getBytes(StandardCharsets.US_ASCII);if(name.length==0||name.length>255)throw new IOException("invalid_socks_target");out.write(new byte[]{5,1,0,3,(byte)name.length});out.write(name);}
                out.write(destination.getPort()>>>8);out.write(destination.getPort());out.flush();
                if(read(in)!=5||read(in)!=0||read(in)!=0)throw new IOException("proxy_connect_failed");
                int type=read(in),length=type==1?4:type==4?16:type==3?read(in):-1;if(length<0)throw new IOException("invalid_socks_response");for(int i=0;i<length+2;i++)read(in);
            }catch(IOException|RuntimeException e){close();throw e;}finally{if(!isClosed())setSoTimeout(previous);}
        }
        private static int read(InputStream in)throws IOException{int n=in.read();if(n<0)throw new IOException("proxy_connection_closed");return n;}
    }
}
