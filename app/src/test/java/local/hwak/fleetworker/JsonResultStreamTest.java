package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;
import org.junit.Test;

public class JsonResultStreamTest {
    @Test public void UTF8LengthAndChunksPreserveSurrogateBoundaryAndJsonEscaping()throws Exception {
        String raw="я".repeat(16382)+"😀\u0000\n";String json=JsonResultStream.stringify(new JSONObject().put("value",raw));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(JsonResultStream stream=new JsonResultStream(json)){byte[] b=new byte[997];for(int n;(n=stream.read(b))!=-1;)bytes.write(b,0,n);}
        byte[] expected=json.getBytes(StandardCharsets.UTF_8);assertArrayEquals(expected,bytes.toByteArray());assertEquals(expected.length,JsonResultStream.utf8Length(json));assertEquals(Operations.hex(MessageDigest.getInstance("SHA-256").digest(expected)),JsonResultStream.sha256(json));
    }
    @Test public void genericResultStreamsBeyondControlBudgetToExactArtifact()throws Exception {
        String json=JsonResultStream.stringify(new JSONObject().put("text","\u0001".repeat(3*1024*1024)));assertTrue(JsonResultStream.utf8Length(json)>Api.CONTROL_JSON_BYTES);
        NetworkOperationsTest.Sink sink=new NetworkOperationsTest.Sink();
        Operations.Checkpoints none=new Operations.Checkpoints(){public void checkpoint(String s){}public void progress(String s){}};
        try(NetworkOperations host=new NetworkOperations(sink,()->false,none,null)) {
            JSONObject compact=host.resultArtifact(json,"attempt");assertEquals("json",compact.getString("result_format"));assertEquals("control_budget_exceeded",compact.getJSONObject("body_error").getString("code"));assertEquals(JsonResultStream.utf8Length(json),compact.getJSONObject("artifact").getLong("length"));assertEquals(JsonResultStream.sha256(json),compact.getJSONObject("artifact").getString("sha256"));assertEquals(1,host.artifactIds().length());
        }
    }
}
