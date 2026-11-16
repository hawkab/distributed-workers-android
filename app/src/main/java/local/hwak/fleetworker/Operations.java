package local.hwak.fleetworker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrapFactory;

final class Operations {
    interface Checkpoints { void checkpoint(String json); void progress(String json); }
    static Object execute(JSONObject job,String resume,BooleanSupplier cancelled,Checkpoints checkpoints)throws Exception {
        JSONObject input=job.optJSONObject("input");if(input==null)input=new JSONObject();
        switch(job.getString("operation")) {
            case "sha256": return new JSONObject().put("sha256",hex(MessageDigest.getInstance("SHA-256").digest(input.getString("text").getBytes(StandardCharsets.UTF_8))));
            case "text.normalize": return new JSONObject().put("text",normalize(input.getString("text")));
            case "javascript": return javascript(job.getString("script"),input,resume,cancelled,checkpoints);
            default: throw new IllegalArgumentException("unsupported_operation");
        }
    }
    static String normalize(String s) {
        // Android's Pattern does not support Java's (?U) flag. Iterate Unicode
        // code points so all separators, including NBSP, work on Android too.
        String normalized=Normalizer.normalize(s,Normalizer.Form.NFC);
        StringBuilder result=new StringBuilder(normalized.length());
        boolean pendingSpace=false;
        for(int offset=0;offset<normalized.length();) {
            int point=normalized.codePointAt(offset);offset+=Character.charCount(point);
            if(Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                if(result.length()>0)pendingSpace=true;
            } else {
                if(pendingSpace)result.append(' ');
                result.appendCodePoint(point);pendingSpace=false;
            }
        }
        return result.toString();
    }
    static String hex(byte[] data) { StringBuilder s=new StringBuilder(data.length*2);for(byte b:data)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString(); }
    static Object javascript(String code,JSONObject input,String resume,BooleanSupplier cancelled,Checkpoints checkpoints)throws Exception {
        ContextFactory factory=new ContextFactory() {
            @Override protected Context makeContext() {
                Context cx=super.makeContext();cx.setOptimizationLevel(-1);cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setInstructionObserverThreshold(10000);cx.setClassShutter(name->false);
                cx.setWrapFactory(new WrapFactory(){@Override public Scriptable wrapAsJavaObject(Context c,Scriptable scope,Object object,Class<?> type){throw new SecurityException("Java access denied");}});
                return cx;
            }
            @Override protected void observeInstructionCount(Context cx,int count) { if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new CancellationException("job_cancelled"); }
        };
        return factory.call(cx->{
            if(cancelled.getAsBoolean())throw new CancellationException("job_cancelled");
            ScriptableObject scope=cx.initSafeStandardObjects(null,true);
            Scriptable json=(Scriptable)ScriptableObject.getProperty(scope,"JSON");
            Function parse=(Function)ScriptableObject.getProperty(json,"parse"),stringify=(Function)ScriptableObject.getProperty(json,"stringify");
            ScriptableObject.defineProperty(scope,"checkpoint",new BaseFunction(){@Override public Object call(Context c,Scriptable s,Scriptable self,Object[] args){
                if(args.length!=1)throw new IllegalArgumentException("checkpoint_requires_one_value");
                Object raw=stringify.call(c,scope,json,new Object[]{args[0]});if(Undefined.isUndefined(raw))throw new IllegalArgumentException("checkpoint_not_json");checkpoints.checkpoint(Context.toString(raw));return Undefined.instance;
            }},ScriptableObject.READONLY|ScriptableObject.PERMANENT);
            ScriptableObject.defineProperty(scope,"progress",new BaseFunction(){@Override public Object call(Context c,Scriptable s,Scriptable self,Object[] args){
                if(args.length!=1)throw new IllegalArgumentException("progress_requires_one_value");
                Object raw=stringify.call(c,scope,json,new Object[]{args[0]});if(!Undefined.isUndefined(raw))checkpoints.progress(Context.toString(raw));return Undefined.instance;
            }},ScriptableObject.READONLY|ScriptableObject.PERMANENT);
            Object jsInput=parse.call(cx,scope,json,new Object[]{input.toString()});
            Object jsResume=parse.call(cx,scope,json,new Object[]{resume});
            cx.evaluateString(scope,code,"job",1,null);
            Object function=ScriptableObject.getProperty(scope,"run");if(!(function instanceof Function))throw new IllegalArgumentException("run_function_required");
            Object result=((Function)function).call(cx,scope,scope,new Object[]{jsInput,jsResume});
            Object raw=stringify.call(cx,scope,json,new Object[]{result});if(Undefined.isUndefined(raw))throw new IllegalArgumentException("result_not_json");
            try{return new JSONTokener(Context.toString(raw)).nextValue();}catch(org.json.JSONException e){throw new IllegalArgumentException("result_not_json");}
        });
    }
}
