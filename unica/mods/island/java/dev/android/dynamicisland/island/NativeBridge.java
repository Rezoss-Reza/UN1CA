// Native integration copyright (c) 2026 UN1CA contributors.
// Renderer: The Cipher Project, https://t.me/thecipherproject
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.android.dynamicisland.island;

import android.content.Context;
import android.database.ContentObserver;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.XLog;
import java.lang.reflect.Method;
import java.util.*;
import dev.android.dynamicisland.settings.GlobalPrefs;
import dev.android.dynamicisland.xposed.hooks.HeadsUpHooks;
import android.service.notification.StatusBarNotification;

/** Process-local, event-driven adapter; no LSPosed, polling, or shell commands. */
public final class NativeBridge {
    private static final String KEY = "unica_dynamic_island";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String,Object> SOURCES = new HashMap<>();
    private static final Set<Object> LISTENING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<View,Integer> HIDDEN = new WeakHashMap<>();
    private static Context context;
    private static View statusBar;
    private static Object controller, boundOa, boundBundle, notificationFilter, listBuilder;
    private static boolean enabled, active, posted, mediaStarted;
    private static boolean restoring;
    private static boolean dataDirty=true;
    private static final Runnable SYNC = () -> { posted = false; sync(); };
    private NativeBridge() {}

    // Called after Object.<init>; defer inspecting fields until construction returns.
    public static void capture(Object source) {
        try { MAIN.post(() -> {
            if (source.getClass().getSimpleName().equals("StatusBarWindowStateController")
                    && Refl.getInt(source,"displayId",0) != 0) return;
            SOURCES.put(source.getClass().getSimpleName(),source);
            dataDirty=true;
            schedule();
        }); } catch (Throwable t) { XLog.e("capture",t); }
    }
    public static void attached(Object viewController) {
        try {
            Object value = Refl.get(viewController,"mView");
            if (!(value instanceof View)) return;
            MAIN.post(() -> {
                View view = (View)value;
                if (statusBar != null && statusBar != view) stop();
                statusBar = view;
                if (context == null) {
                    context = view.getContext().getApplicationContext();
                    GlobalPrefs.bind(context);
                    context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY),false,
                        new ContentObserver(MAIN) { @Override public void onChange(boolean self) { schedule(); } });
                }
                schedule();
            });
        } catch (Throwable t) { fail(t); }
    }
    public static void detached(Object viewController) {
        Object view = Refl.get(viewController,"mView");
        MAIN.post(() -> { if (view == statusBar) { stop(); statusBar = null; } });
    }
    public static void refresh() { if (enabled && !restoring) { dataDirty=true; schedule(); } }
    private static void schedule() {
        if (Looper.myLooper() != MAIN.getLooper()) { MAIN.post(NativeBridge::schedule); return; }
        if (!posted) { posted=true; MAIN.post(SYNC); }
    }
    public static boolean shouldRun() { return enabled; }
    public static boolean shouldReplace() { return active && !restoring; }
    private static Object source(String name) { return SOURCES.get(name); }
    private static Object invoke(Object o, String name, Object... args) throws Exception {
        if (o == null) throw new IllegalStateException("Missing receiver for "+name);
        Method m = Refl.method(o.getClass(),name,args.length);
        if (m == null) throw new NoSuchMethodException(o.getClass().getName()+"#"+name);
        return m.invoke(o,args);
    }
    private static void sync() {
        try {
            if (context == null || statusBar == null || !statusBar.isAttachedToWindow()) return;
            boolean requested = Settings.Global.getInt(context.getContentResolver(),KEY,0) == 1;
            if (!requested) { if (enabled || active) stop(); return; }
            enabled = true;
            ClassLoader cl = context.getClassLoader();
            if (controller == null) {
                Class<?> c = Class.forName("dev.android.dynamicisland.island.IslandController",true,cl);
                controller = c.getMethod("init",Context.class,ClassLoader.class).invoke(null,context,cl);
                // init() posts its one-time start before the follow-up sync below.
                MAIN.post(NativeBridge::finishEnable);
            } else finishEnable();
        } catch (Throwable t) { fail(t); }
    }
    private static void finishEnable() {
        if (!enabled || statusBar == null) return;
        try {
            Object oa=source("OngoingActivityController"), call=source("SamsungOngoingCallController");
            Object cast=source("SamsungOngoingCastController"), bundle=source("SamsungCastChipBundle");
            Object state=source("StatusBarStateControllerImpl"), shade=source("ShadeExpansionStateManager");
            Object windowState=source("StatusBarWindowStateController");
            if (oa == null || state == null || shade == null || windowState == null)
                throw new IllegalStateException("Required SystemUI controller unavailable");
            listen(state,"com.android.systemui.plugins.statusbar.StatusBarStateController$StateListener","addCallback");
            listen(shade,"com.android.systemui.shade.ShadeExpansionListener","addExpansionListener");
            listenWindow(windowState);
            boolean newActive=!active;
            if (newActive || boundOa!=oa) { invoke(controller,"setOngoingActivityController",oa); boundOa=oa; }
            invoke(controller,"setCallController",call);
            invoke(controller,"setCastController",cast);
            if (bundle != null && (newActive || boundBundle!=bundle)) {
                invoke(controller,"setCastBundle",bundle); boundBundle=bundle; dataDirty=true;
            }
            invoke(controller,"setBarState",Refl.getInt(state,"mState",1));
            invoke(controller,"setDozing",Refl.getBool(state,"mIsDozing",true));
            invoke(controller,"setStatusBarWindowState",Refl.getInt(windowState,"windowState",2));
            Object fraction=Refl.get(shade,"fraction");
            invoke(controller,"setShadeExpansion",fraction instanceof Float ? fraction : 0f);
            invoke(controller,"onStatusBarViewAttached",statusBar);
            Object placement=Refl.get(controller,"placement");
            Object window=Refl.get(placement,"window");
            if (window == null || !Boolean.TRUE.equals(invoke(window,"isAdded")))
                throw new IllegalStateException("Island window did not attach");
            if (!mediaStarted) {
                // The first init already starts media; subsequent enables restart it below.
                Object media=Refl.get(Refl.get(controller,"sources"),"media");
                invoke(media,"nativeResume");
                mediaStarted=true;
            }
            active=true;
            hideStock();
            if (dataDirty || newActive) { invoke(controller,"requestRefresh"); dataDirty=false; }
            if (newActive) Settings.Global.putInt(context.getContentResolver(),KEY+"_state",1);
        } catch (Throwable t) { fail(t); }
    }
    private static void listen(Object o, String iface, String add) throws Exception {
        if (LISTENING.contains(o)) return;
        Class<?> type=Class.forName(iface,false,context.getClassLoader());
        Object proxy=Refl.proxy(type,(p,m,a)-> {
            // State is read after the host finishes updating it; no callback-thread UI writes.
            if (enabled) schedule(); return null;
        });
        invoke(o,add,proxy); LISTENING.add(o);
    }
    @SuppressWarnings("unchecked") private static void listenWindow(Object o) throws Exception {
        if (LISTENING.contains(o)) return;
        Object listeners=Refl.get(o,"listeners");
        if (!(listeners instanceof Set)) throw new IllegalStateException("Window listeners changed");
        Class<?> type=Class.forName("com.android.systemui.statusbar.window.StatusBarWindowStateListener",false,context.getClassLoader());
        ((Set<Object>)listeners).add(Refl.proxy(type,(p,m,a)-> { if (enabled) schedule(); return null; }));
        LISTENING.add(o);
    }
    public static boolean hideView(View view, Object model) {
        if (!shouldReplace() || view == null || context == null) return false;
        String name;
        try { name=view.getResources().getResourceEntryName(view.getId()); } catch (Throwable t) { return false; }
        if (!name.equals("samsung_ongoing_activity_chip_container") && !name.equals("samsung_status_bar_chips_container")
            && !name.equals("samsung_ongoing_cast_chip") && !name.equals("ongoing_call_chip")
            && !name.equals("ongoing_activity_capsule")) return false;
        if (model != null) HIDDEN.put(view,Refl.getInt(model,"visibility",view.getVisibility()));
        hide(view); return true;
    }
    public static boolean hideCapsule(View view) { return hideView(view,null); }
    private static void hide(View v) {
        if (!HIDDEN.containsKey(v)) HIDDEN.put(v,v.getVisibility());
        v.animate().cancel(); v.setVisibility(View.GONE);
    }
    private static void hideStock() {
        if (statusBar == null) return;
        for (String n:new String[]{"ongoing_activity_capsule","samsung_ongoing_activity_chip_container",
                "samsung_status_bar_chips_container","samsung_ongoing_cast_chip","ongoing_call_chip"}) {
            int id=statusBar.getResources().getIdentifier(n,"id","com.android.systemui");
            View v=id==0?null:statusBar.findViewById(id); if (v!=null) hide(v);
        }
        Object parent=Refl.get(source("OngoingActivityController"),"mParentView");
        if (parent instanceof View) hide((View)parent);
    }
    public static boolean hideParent(Object oa) {
        if (!shouldReplace()) return false;
        try {
            Object v=Refl.get(oa,"mParentView"); if (v instanceof View) hide((View)v);
            Object flow=Refl.get(oa,"showingOAChip");
            if (flow!=null) invoke(flow,"updateState",null,Boolean.FALSE);
            return true;
        } catch (Throwable t) { fail(t); return false; }
    }
    public static boolean exitTargets(Object oa) {
        if (!shouldReplace()) return false;
        try { invoke(controller,"onExitTargetsRequested"); return true; }
        catch (Throwable t) { fail(t); return false; }
    }
    public static void captureListBuilder(Object builder) { listBuilder=builder; }
    public static void snapshot(Collection<?> entries,Object destination,Object filters) { notifications(listBuilder,entries,filters); }
    public static void notifications(Object builder, Collection<?> entries, Object filters) {
        if (!enabled || filters != Refl.get(builder,"mNotifPreGroupFilters")) return;
        try {
            if(filters instanceof List && !((List<?>)filters).isEmpty())notificationFilter=((List<?>)filters).get(0);
            List<StatusBarNotification> snapshot=new ArrayList<>();
            for(Object entry:entries) { Object sbn=Refl.get(entry,"mSbn"); if(sbn instanceof StatusBarNotification)snapshot.add((StatusBarNotification)sbn); }
            invoke(controller,"onNotificationsSnapshot",snapshot);
        } catch(Throwable t) { XLog.once("native notifications",t); }
    }
    private static boolean rebuildPosted;
    public static void rebuildNotifications() {
        if(notificationFilter==null || rebuildPosted)return; rebuildPosted=true;
        MAIN.post(() -> { rebuildPosted=false; Refl.call(notificationFilter,"invalidateList","native island update"); });
    }
    public static boolean hideNotification(Object entry) {
        if (!shouldReplace()) return false;
        Object sbn=Refl.get(entry,"mSbn");
        return sbn instanceof StatusBarNotification && Boolean.TRUE.equals(Refl.call(controller,"hidesOriginal",((StatusBarNotification)sbn).getKey()));
    }
    public static boolean headsUp(Object manager,Object entry) {
        if (!shouldReplace()) return false;
        HeadsUpHooks.manager=manager;
        Object sbn=Refl.get(entry,"mSbn");
        return sbn instanceof StatusBarNotification && Boolean.TRUE.equals(Refl.call(controller,"peekHeadsUp",sbn));
    }
    public static Object pinStatus(Object manager,Object head,Object status) {
        if (status!=null && "PinnedBySystem".equals(String.valueOf(status)) && headsUp(manager,Refl.get(head,"mEntry"))) {
            Object off=Refl.getStatic(status.getClass(),"NotPinned"); if(off!=null)return off;
        }
        return status;
    }
    private static void stop() {
        active=false; enabled=false;
        if (controller != null) {
            Object h=Refl.get(controller,"main");
            if (h instanceof Handler) ((Handler)h).removeCallbacksAndMessages(null);
            Refl.set(controller,"refreshPosted",false);
            Object placement=Refl.get(controller,"placement");
            Refl.set(placement,"revealPending",false);
            Refl.call(controller,"onStatusBarViewDetached",statusBar);
            Refl.call(Refl.get(controller,"placement"),"nativeStop");
            
            Refl.call(Refl.get(controller,"audio"),"setActive",false);
            Object media=Refl.get(Refl.get(controller,"sources"),"media");
            Refl.call(media,"nativePause"); mediaStarted=false;
        }
        restoring=true;
        try {
            for (Map.Entry<View,Integer> e:new ArrayList<>(HIDDEN.entrySet())) if (e.getKey()!=null) e.getKey().setVisibility(e.getValue());
            HIDDEN.clear();
            Object oa=source("OngoingActivityController");
            Refl.call(oa,"updateParentViewVisibility",false);
            Refl.call(oa,"sendChipInfoForAppExitAnimation");
            Refl.call(source("SamsungOngoingCallController"),"updateChip");
            rebuildNotifications();
        } finally { restoring=false; }
        if (context!=null) Settings.Global.putInt(context.getContentResolver(),KEY+"_state",0);
    }
    public static void fail(Throwable t) {
        XLog.e("Native island disabled after failure",t);
        // Stop interception immediately; defer view removal if currently drawing.
        active=false; enabled=false;
        MAIN.post(() -> {
            try { stop(); } catch (Throwable cleanup) { XLog.e("cleanup",cleanup); }
            if (context!=null) Settings.Global.putInt(context.getContentResolver(),KEY,0);
        });
    }
}
