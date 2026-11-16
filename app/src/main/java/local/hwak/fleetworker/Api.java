package local.hwak.fleetworker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class Api {
    static final class Failure extends IOException { final int status; Failure(int s){super("api_http_"+s);status=s;} }
    private final Configuration config;
    Api(Configuration c){config=c;}
    JSONObject post(String path,JSONObject body)throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL(config.baseUrl+path).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Bearer "+config.token);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
        byte[] raw=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(raw.length);
        try {try(java.io.OutputStream out=c.getOutputStream()){out.write(raw);}int status=c.getResponseCode();if(status==204)return null;if(status!=200)throw new Failure(status);try(InputStream in=c.getInputStream()){return new JSONObject(read(in));}}
        finally {c.disconnect();}
    }
    static String read(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}
}
