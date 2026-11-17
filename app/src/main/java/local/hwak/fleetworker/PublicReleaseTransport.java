package local.hwak.fleetworker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;

/** Anonymous HTTPS only. In particular, the worker API bearer is never attached. */
final class PublicReleaseTransport implements ReleaseResolver.Transport {
    private static final class HttpFailure extends java.io.IOException {HttpFailure(int code){super("release_http_"+code);}}
    private HttpsURLConnection response(String address)throws Exception {
        Set<String> visited=new HashSet<>();String current=address;
        while(true) {
            ReleaseSource.publicHttps(current);
            if(!visited.add(current))throw new SecurityException("release_redirect_cycle");
            HttpsURLConnection connection=Api.openDirect(current);
            connection.setConnectTimeout(15000);connection.setReadTimeout(60000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","FleetWorker/"+BuildConfig.VERSION_NAME);connection.setRequestProperty("Accept","application/json, application/octet-stream, */*");
            int status;
            try{status=connection.getResponseCode();}catch(Exception e){connection.disconnect();throw e;}
            if(status==301 || status==302 || status==303 || status==307 || status==308) {
                String location=connection.getHeaderField("Location");connection.disconnect();
                if(location==null)throw new java.io.IOException("release_redirect_without_location");
                current=URI.create(current).resolve(location).toString();continue;
            }
            if(status!=200){connection.disconnect();throw new HttpFailure(status);}
            return connection;
        }
    }
    @Override public String text(String address)throws Exception {
        HttpsURLConnection connection=response(address);
        try(InputStream in=connection.getInputStream()){return Api.read(in);}finally{connection.disconnect();}
    }
    @Override public void download(String address,File target)throws Exception {
        HttpsURLConnection connection=response(address);
        try {
            // The downloaded archive and PackageInstaller's session copy coexist.
            long length=connection.getContentLengthLong();if(length>0 && target.getParentFile().getUsableSpace()/2<length)throw new IllegalStateException("release_low_storage");
            try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(target)){byte[] buffer=new byte[32768];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);out.getFD().sync();}
        }finally{connection.disconnect();}
    }
}
