package local.hwak.fleetworker;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;

public class OperationsTest {
    private static final Operations.Checkpoints NOOP=new Operations.Checkpoints(){public void checkpoint(String s){}public void progress(String s){}};
    @Test public void realHashAndUnicodeNormalization()throws Exception {
        JSONObject j=new JSONObject().put("operation","sha256").put("input",new JSONObject().put("text","abc"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",((JSONObject)Operations.execute(j,"null",()->false,NOOP)).getString("sha256"));
        assertEquals("é Привет мир",Operations.normalize("  e\u0301 \tПривет\nмир  "));
    }
    @Test public void unicodeWhitespaceFromTheActualAndroidFailureIsCollapsed() {
        assertEquals("Отчёт готов Версия 2",Operations.normalize("  Отчёт\u00a0\u00a0готов\n  Версия 2  "));
        assertEquals("é 😀 текст",Operations.normalize("\u3000e\u0301\u202f\u2007😀\u2028\tтекст\u00a0"));
        assertEquals("",Operations.normalize("\u00a0\u202f\u2007\u3000\t\r\n"));
        assertEquals("а\u200bб",Operations.normalize(" а\u200bб "));
    }
    @Test public void javascriptUsesOnlyJsonAndDurableCheckpointCallbacks()throws Exception {
        List<String> writes=new ArrayList<>();
        Object result=Operations.javascript("function run(input,resume){checkpoint({next:resume.next+1});progress({stage:'sum'});return {sum:input.a+resume.next};}",new JSONObject().put("a",7),"{\"next\":4}",()->false,new Operations.Checkpoints(){public void checkpoint(String s){writes.add(s);}public void progress(String s){writes.add(s);}});
        assertEquals(11,((JSONObject)result).getInt("sum"));assertEquals(5,new JSONObject(writes.get(0)).getInt("next"));assertEquals("sum",new JSONObject(writes.get(1)).getString("stage"));
    }
    @Test public void sandboxExposesNoJavaPackagesOrCredentials()throws Exception {
        JSONObject result=(JSONObject)Operations.javascript("function run(input){return {java:typeof java,packages:typeof Packages,bridge:typeof JavaAdapter,clazz:typeof getClass,reflection:typeof input.getClass,token:typeof token,process:typeof process};}",new JSONObject(),"null",()->false,NOOP);
        for(String key:new String[]{"java","packages","bridge","clazz","reflection","token","process"})assertEquals(key,"undefined",result.getString(key));
        try{Operations.javascript("function run(input){return java.lang.System.getenv();}",new JSONObject(),"null",()->false,NOOP);fail("Java escaped");}catch(RuntimeException expected){ }
    }
    @Test public void longScriptHasExplicitCancellationWithoutWallDeadline()throws Exception {
        AtomicBoolean cancel=new AtomicBoolean();CountDownLatch started=new CountDownLatch(1);
        CompletableFuture<Object> future=CompletableFuture.supplyAsync(()->{
            try{return Operations.javascript("function run(input){progress({started:true});var i=0;while(true){i++;}}",new JSONObject(),"null",cancel::get,new Operations.Checkpoints(){public void checkpoint(String s){}public void progress(String s){started.countDown();}});}
            catch(Exception e){return e;}
        });
        try{assertTrue(started.await(10,TimeUnit.SECONDS));assertFalse(future.isDone());cancel.set(true);assertTrue(future.get(10,TimeUnit.SECONDS) instanceof CancellationException);}
        finally{cancel.set(true);}
    }
}
