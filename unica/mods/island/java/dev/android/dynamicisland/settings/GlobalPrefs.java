// Native ROM integration, copyright (c) 2026 UN1CA contributors.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.android.dynamicisland.settings;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import org.json.*;
import java.util.*;

/** Shared ROM configuration. Writes require WRITE_SECURE_SETTINGS, never su or world-readable files. */
public final class GlobalPrefs implements SharedPreferences {
    public static final String KEY="unica_dynamic_island_config";
    private static Context context;
    private static final Object LOCK=new Object();
    private final Context ctx;
    public static void bind(Context c) { context=c.getApplicationContext(); }
    public static Context context() { if(context==null) throw new IllegalStateException("Island context not bound"); return context; }
    public GlobalPrefs(Context c) { ctx=c.getApplicationContext(); }
    public static int resource(String name) {
        Context c=context(); int id=c.getResources().getIdentifier("unica_island_"+name,"string","com.android.settings");
        if(id==0) throw new IllegalArgumentException("Missing island string: "+name); return id;
    }
    private Map<String,Object> read() {
        Map<String,Object> out=new HashMap<>();
        String raw=Settings.Global.getString(ctx.getContentResolver(),KEY);
        if(raw==null || raw.isEmpty()) return out;
        try { JSONObject json=new JSONObject(raw);
            Iterator<String> keys=json.keys(); while(keys.hasNext()) {
                String key=keys.next(); Object v=json.opt(key);
                if(v instanceof JSONArray) {
                    Set<String> set=new HashSet<>(); JSONArray a=(JSONArray)v;
                    for(int i=0;i<a.length();i++)set.add(a.getString(i)); v=set;
                }
                if(v!=JSONObject.NULL)out.put(key,v);
            }
        } catch(JSONException e) { android.util.Log.e("DynamicIsland","Invalid saved configuration",e); }
        return out;
    }
    public Map<String,?> getAll(){ return read(); }
    public boolean contains(String k){return read().containsKey(k);}
    public String getString(String k,String d){Object v=read().get(k);return v instanceof String?(String)v:d;}
    public int getInt(String k,int d){Object v=read().get(k);return v instanceof Number?((Number)v).intValue():d;}
    public long getLong(String k,long d){Object v=read().get(k);return v instanceof Number?((Number)v).longValue():d;}
    public float getFloat(String k,float d){Object v=read().get(k);return v instanceof Number?((Number)v).floatValue():d;}
    public boolean getBoolean(String k,boolean d){Object v=read().get(k);return v instanceof Boolean?(Boolean)v:d;}
    @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){Object v=read().get(k);return v instanceof Set?new HashSet<>((Set<String>)v):d;}
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
    public Editor edit(){return new Edit();}
    private final class Edit implements Editor {
        private final Map<String,Object> pending=new HashMap<>(); private boolean clear;
        public Editor putString(String k,String v){pending.put(k,v);return this;}
        public Editor putStringSet(String k,Set<String>v){pending.put(k,v==null?null:new HashSet<>(v));return this;}
        public Editor putInt(String k,int v){pending.put(k,v);return this;}
        public Editor putLong(String k,long v){pending.put(k,v);return this;}
        public Editor putFloat(String k,float v){pending.put(k,v);return this;}
        public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
        public Editor remove(String k){pending.put(k,null);return this;}
        public Editor clear(){clear=true;return this;}
        public boolean commit(){synchronized(LOCK){
            Map<String,Object> map=clear?new HashMap<>():read();
            for(Map.Entry<String,Object> e:pending.entrySet())if(e.getValue()==null)map.remove(e.getKey());else map.put(e.getKey(),e.getValue());
            JSONObject json=new JSONObject();
            try {for(Map.Entry<String,Object> e:map.entrySet())json.put(e.getKey(),e.getValue() instanceof Set?new JSONArray((Set<?>)e.getValue()):e.getValue());}
            catch(JSONException e){throw new IllegalArgumentException(e);}
            String raw=json.toString(); if(raw.length()>131072)return false;
            return Settings.Global.putString(ctx.getContentResolver(),KEY,raw);
        }}
        public void apply(){
            if(!commit())new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(ctx,"Cannot save island settings. The rules or app list may be too large.",android.widget.Toast.LENGTH_LONG).show());
        }
    }
}
