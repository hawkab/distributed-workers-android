package local.hwak.fleetworker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import javax.net.ssl.SSLSocketFactory;

/** Preserve platform TLS validation, while bypassing implicit system SOCKS routing. */
final class DirectTlsSockets extends SSLSocketFactory {
    private final SSLSocketFactory tls;
    DirectTlsSockets(SSLSocketFactory tls){this.tls=tls;}
    @Override public String[] getDefaultCipherSuites(){return tls.getDefaultCipherSuites();}
    @Override public String[] getSupportedCipherSuites(){return tls.getSupportedCipherSuites();}
    // HttpsURLConnection connects this raw socket, then calls the layered overload.
    // Android's OkHttp implementation directly calls that layered overload instead.
    @Override public Socket createSocket(){return new Socket(Proxy.NO_PROXY);}
    @Override public Socket createSocket(Socket socket,String host,int port,boolean close)throws IOException{return tls.createSocket(socket,host,port,close);}
    @Override public Socket createSocket(String host,int port)throws IOException{return connect(host,port,null,0);}
    @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{return connect(host,port,local,localPort);}
    @Override public Socket createSocket(InetAddress host,int port)throws IOException{return connect(host.getHostAddress(),port,null,0);}
    @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{return connect(host.getHostAddress(),port,local,localPort);}
    private Socket connect(String host,int port,InetAddress local,int localPort)throws IOException {
        Socket raw=new Socket(Proxy.NO_PROXY);
        try{if(local!=null)raw.bind(new InetSocketAddress(local,localPort));raw.connect(new InetSocketAddress(host,port));return tls.createSocket(raw,host,port,true);}
        catch(IOException|RuntimeException error){raw.close();throw error;}
    }
}
