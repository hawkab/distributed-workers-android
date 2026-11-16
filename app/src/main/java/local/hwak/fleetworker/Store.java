package local.hwak.fleetworker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;

final class Store extends SQLiteOpenHelper {
    private static Store instance;
    static synchronized Store get(Context c) { if (instance == null) instance = new Store(c.getApplicationContext()); return instance; }
    private Store(Context c) { super(c, "worker.db", null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { db.execSQL("PRAGMA synchronous=FULL"); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE current_job(singleton INTEGER PRIMARY KEY CHECK(singleton=1),payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE outbox(job_id TEXT NOT NULL,attempt_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(job_id,attempt_id))");
        db.execSQL("CREATE TABLE checkpoints(job_id TEXT PRIMARY KEY,payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE state(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("unsupported_schema"); }
    synchronized void claimed(JSONObject job) {
        ContentValues v = new ContentValues(); v.put("singleton",1); v.put("payload",job.toString());
        if(getWritableDatabase().insertWithOnConflict("current_job",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1) throw new IllegalStateException("claim_write_failed");
    }
    synchronized JSONObject current() { return queryJson("SELECT payload FROM current_job WHERE singleton=1",null); }
    synchronized JSONObject nextCompletion() { return queryJson("SELECT payload FROM outbox ORDER BY rowid LIMIT 1",null); }
    synchronized boolean hasWork() { return current()!=null || nextCompletion()!=null; }
    synchronized void completion(JSONObject job, JSONObject completion) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            ContentValues v=new ContentValues();v.put("job_id",job.optString("id"));v.put("attempt_id",job.optString("attempt_id"));v.put("payload",completion.toString());
            if(db.insertWithOnConflict("outbox",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1) throw new IllegalStateException("outbox_write_failed");
            db.delete("current_job","singleton=1",null);db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized void acknowledged(String id,String attempt) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {db.delete("outbox","job_id=? AND attempt_id=?",new String[]{id,attempt});db.delete("checkpoints","job_id=?",new String[]{id});db.setTransactionSuccessful();}
        finally {db.endTransaction();}
    }
    synchronized void abandonCurrent(String id) { getWritableDatabase().delete("current_job","singleton=1",null); setState("last_stale_job",id); }
    synchronized void checkpoint(String id,String value) {
        ContentValues v=new ContentValues();v.put("job_id",id);v.put("payload",value);
        if(getWritableDatabase().insertWithOnConflict("checkpoints",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1) throw new IllegalStateException("checkpoint_write_failed");
    }
    synchronized String checkpoint(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM checkpoints WHERE job_id=?",new String[]{id})){return c.moveToFirst()?c.getString(0):"null";}
    }
    synchronized void setState(String name,String value) {
        ContentValues v=new ContentValues();v.put("name",name);v.put("value",value);
        if(getWritableDatabase().insertWithOnConflict("state",null,v,SQLiteDatabase.CONFLICT_REPLACE)==-1) throw new IllegalStateException("state_write_failed");
    }
    synchronized String getState(String name,String fallback) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM state WHERE name=?",new String[]{name})){return c.moveToFirst()?c.getString(0):fallback;}
    }
    synchronized JSONObject status() {
        JSONObject o=new JSONObject();
        try {
            o.put("version_code",BuildConfig.VERSION_CODE).put("version_name",BuildConfig.VERSION_NAME);
            JSONObject j=current();if(j!=null)o.put("active_job_id",j.optString("id"));
            try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM outbox",null)){if(c.moveToFirst())o.put("outbox",c.getInt(0));}
            for(String key:new String[]{"worker_state","last_error","last_job_id","last_job_status","update_state","update_version","update_session","update_source","update_source_state","update_poll_seconds","last_update_check","last_heartbeat_at"})o.put(key,getState(key,""));
        }catch(Exception ignored){ }
        return o;
    }
    private JSONObject queryJson(String sql,String[] args) {
        try(Cursor c=getReadableDatabase().rawQuery(sql,args)){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}catch(org.json.JSONException e){throw new IllegalStateException("stored_json_invalid",e);}
    }
}
