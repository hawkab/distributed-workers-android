package local.hwak.fleetworker;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded UTF-8 transfer view of an already computed JSON value; never a file. */
final class JsonResultStream extends InputStream {
    private final String value;private int position,index;private byte[] buffer=new byte[0];
    JsonResultStream(String value){this.value=value;}
    static String stringify(Object value)throws Exception {
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject||value instanceof JSONArray)return value.toString();
        if(value instanceof String)return JSONObject.quote((String)value);
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number)return JSONObject.numberToString((Number)value);
        throw new IllegalArgumentException("result_not_json");
    }
    static long utf8Length(String value){long n=0;for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<128)n++;else if(c<2048)n+=2;else if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1))){n+=4;i++;}else n+=Character.isSurrogate(c)?1:3;}return n;}
    static String sha256(String value)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(JsonResultStream stream=new JsonResultStream(value)){byte[] b=new byte[16384];for(int n;(n=stream.read(b))!=-1;)digest.update(b,0,n);}return Operations.hex(digest.digest());}
    private boolean refill(){if(index<buffer.length)return true;if(position>=value.length())return false;int end=Math.min(value.length(),position+16384);if(end<value.length()&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;buffer=value.substring(position,end).getBytes(StandardCharsets.UTF_8);position=end;index=0;return true;}
    @Override public int read(){return refill()?buffer[index++]&255:-1;}
    @Override public int read(byte[] target,int start,int length){if(length==0)return 0;if(!refill())return -1;int n=Math.min(length,buffer.length-index);System.arraycopy(buffer,index,target,start,n);index+=n;return n;}
}
