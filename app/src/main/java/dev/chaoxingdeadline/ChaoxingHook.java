package dev.chaoxingdeadline;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LSPosed hook module for Chaoxing (学习通).
 *
 * Architecture:
 * - Passive hooks (JSON/OkHttp/WebView) intercept Chaoxing app traffic to extract deadline data.
 * - Active refresh (fetchUrl) proactively pulls course/work/exam lists via Chaoxing HTTP APIs.
 * - Items flow: inspect() -> DeadlineParser -> enrichCourseName() -> emit() -> DeadlineReceiver -> DeadlineStore.
 * - COURSE_NAMES: in-memory cache populated from active course list fetch, used to resolve course names from IDs.
 *
 * Split from the original single-file hook: OverlayViews builds the in-host dialogs,
 * CourseRefs parses course references, CourseScanner runs the per-course deadline scan.
 */
import dev.chaoxingdeadline.CourseRefs.CourseRef;
import dev.chaoxingdeadline.OverlayViews.OverlayTodo;
import io.github.libxposed.api.XposedModule;

public final class ChaoxingHook extends XposedModule {
    private static final String TAG = "ChaoxingDeadline";
    private static final String HOOK_VERSION = "1.12";
    private static final String TARGET_PACKAGE = "com.chaoxing.mobile";
    static final String MODULE_PACKAGE = "dev.chaoxingdeadline";
    private static final long AUTO_REFRESH_MIN_GAP_MS = 3L * 60L * 1000L;
    private static final long OVERLAY_REALTIME_WAIT_MS = 2000L;
    // Opening Chaoxing used to stack several popups: one per captured item on the
    // intercept path, one after the active refresh, plus the retry-after-dismiss. The
    // fingerprint guard only collapses identical content, so consecutive different
    // popups still queued up. Throttle by wall-clock time as well — two minutes
    // between popups. Notifications are unaffected; this is only the in-app overlay.
    private static final long OVERLAY_MIN_INTERVAL_MS = 120_000L;
    private static final String HOME_BUTTON_TAG = "chaoxing_deadline_home_button";
    private static final int DEFAULT_OVERLAY_WINDOW_HOURS = 24;
    private static final int OVERLAY_WINDOW_ALL_HOURS = -1;
    private static final int[] OVERLAY_WINDOW_OPTIONS = {3, 8, 12, 24, 72, OVERLAY_WINDOW_ALL_HOURS};
    private static final String COURSE_SCAN_PREFS = CourseScanScores.PREFS;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static final ThreadLocal<Boolean> PARSING = ThreadLocal.withInitial(() -> false);
    private static final ArrayList<DeadlineItem> PENDING = new ArrayList<>();
    private static final Set<String> FETCHED_URLS = new HashSet<>();
    // Weekly timetable (课程表) captured from Chaoxing, kept in-process for the
    // in-class activity boost; the module process keeps its own copy for the widget.
    private static final List<Lesson> LESSONS = new ArrayList<>();
    private static volatile long firstWeekDate;
    private static volatile String sectionTimesJson = "";
    private static volatile String lastTimetableSignature;
    /** Shapes of timetable-ish payloads that failed to parse, logged once each. */
    private static final Set<String> UNKNOWN_TIMETABLE_SIGNATURES = new HashSet<>();
    /** curriculumUuid from the last timetable URL seen; identifies which timetable. */
    private static volatile String curriculumUuid = "";
    // Course name -> "courseId|classId", built whenever the timetable lands, so a class
    // session can be mapped onto the course whose activities should be polled.
    private static final Map<String, String> COURSE_KEYS_BY_NAME = new HashMap<>();
    private static final String TIMETABLE_URL =
            "https://kb.chaoxing.com/pc/curriculum/getMyLessons";
    // In-class boost: one activity-list refresh per course at most this often, only
    // during the first minutes of a session (where sign-ins actually happen).
    private static final long SESSION_TICK_MS = 60_000L;
    private static final long SESSION_BOOST_WINDOW_MS = 15L * 60L * 1000L;
    private static final long SESSION_REFRESH_GAP_MS = 2L * 60L * 1000L;
    private volatile long lastSessionRefreshAt;
    private volatile boolean sessionTickScheduled;
    // Item ids emitted during the current active-refresh round; the round snapshot sent
    // to the module process drives revoked-deadline detection (see CourseScanner).
    private static final Set<String> SEEN_ITEM_IDS = new HashSet<>();
    private static final int SEEN_ITEM_IDS_LIMIT = 4000;
    private static final Set<String> COURSE_FIELD_SIGNATURES = new HashSet<>();
    private static final Map<String, String> COURSE_NAMES = new HashMap<>();
    private static final Map<Object, ParseContext> RESPONSE_CONTEXTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ArrayList<DeadlineItem> RECENT_ITEMS = new ArrayList<>();
    @SuppressLint("StaticFieldLeak")
    private static volatile Context hostContext;
    private static volatile WeakReference<Activity> currentActivityRef;
    private static volatile long antiSpiderUntil;
    private static volatile long lastOverlayAt;
    private static volatile long lastHomeSeenAt;
    private static volatile long lastAutoRefreshAt;
    private static volatile String lastOverlayFingerprint;
    private static volatile int startedActivityCount;
    private static volatile boolean lifecycleCallbacksRegistered;
    private static volatile boolean overlayDialogShowing;
    private static volatile boolean homePanelShowing;
    private static volatile boolean overlayScheduled;
    private static volatile boolean overlayRetryAfterDialog;
    private static volatile int overlayRefreshGeneration;
    private static volatile int overlayRealtimeShownGeneration = -1;
    private static volatile long overlayRealtimeShownAt;
    private static volatile Boolean overlayEnabledOverride;
    private static volatile int overlayWindowHoursOverride = Integer.MIN_VALUE;
    static volatile int lastCourseScanThreads;
    static volatile int lastCourseScanRefs;
    static volatile int lastCourseScanScanned;
    private static volatile boolean activeRefreshRunning;
    private static volatile boolean foregroundRefreshStarted;


    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "hook v" + HOOK_VERSION + " loaded in " + param.getProcessName() + ", framework "
                + getFrameworkName() + " API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (TARGET_PACKAGE.equals(param.getPackageName())) {
            installApplicationHook();
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        log(Log.INFO, TAG, "install hooks for " + param.getPackageName());
        installJsonHooks();
        installOkHttpHooks(param.getClassLoader());
        installWebViewHooks();
        installActivityOverlayHook();
        emitStatus("hooks installed", "onPackageReady");
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        param.setSavedInstanceState("reload");
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        param.getOldHookHandles().forEach(HookHandle::unhook);
    }

    private void installApplicationHook() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach)
                    .setId("application_attach")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof Context) {
                            hostContext = ((Context) arg).getApplicationContext();
                            Object receiver = chain.getThisObject();
                            if (receiver instanceof Application) {
                                registerForegroundCallbacks((Application) receiver);
                            }
                            registerRefreshRequestReceiver();
                            flushPending();
                            emitStatus("active", "Application.attach");
                            log(Log.INFO, TAG, "host context ready");
                        }
                        return result;
                    });
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "failed to hook Application.attach", throwable);
        }
    }

    private void registerForegroundCallbacks(Application application) {
        synchronized (LIFECYCLE_LOCK) {
            if (lifecycleCallbacksRegistered) {
                return;
            }
            lifecycleCallbacksRegistered = true;
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity activity) {
                boolean enteredForeground;
                synchronized (LIFECYCLE_LOCK) {
                    enteredForeground = startedActivityCount == 0;
                    startedActivityCount++;
                }
                currentActivityRef = new WeakReference<>(activity);
                if (enteredForeground) {
                    foregroundRefreshStarted = false;
                }
                maybeStartForegroundRefresh(activity);
            }

            @Override
            public void onActivityStopped(Activity activity) {
                synchronized (LIFECYCLE_LOCK) {
                    if (startedActivityCount > 0) {
                        startedActivityCount--;
                    }
                    if (startedActivityCount == 0) {
                        foregroundRefreshStarted = false;
                    }
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityResumed(Activity activity) {
                currentActivityRef = new WeakReference<>(activity);
                maybeStartForegroundRefresh(activity);
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
        log(Log.INFO, TAG, "foreground lifecycle callbacks installed");
    }

    private void maybeStartForegroundRefresh(Activity activity) {
        if (!isRefreshEligibleActivity(activity)) {
            return;
        }
        synchronized (LIFECYCLE_LOCK) {
            if (foregroundRefreshStarted) {
                return;
            }
            foregroundRefreshStarted = true;
        }
        log(Log.INFO, TAG, "Chaoxing entered foreground; start automatic refresh");
        requestActiveRefresh("foregroundSession", false);
    }

    private void installJsonHooks() {
        hookStringConstructor("org.json.JSONObject", "JSONObject");
        hookStringConstructor("org.json.JSONArray", "JSONArray");
        hookStringConstructor("org.json.JSONTokener", "JSONTokener");
    }

    private void hookStringConstructor(String className, String source) {
        try {
            Constructor<?> constructor = Class.forName(className).getDeclaredConstructor(String.class);
            hook(constructor)
                    .setId(source + "_string_ctor")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof String) {
                            inspect((String) arg, source);
                        }
                        return result;
                    });
            log(Log.INFO, TAG, source + " hook installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, source + " hook skipped: " + throwable);
        }
    }

    private void installOkHttpHooks(ClassLoader loader) {
        try {
            Class<?> response = Class.forName("okhttp3.Response", false, loader);
            try {
                hook(response.getDeclaredMethod("body"))
                        .setId("okhttp_response_body_context")
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object receiver = chain.getThisObject();
                            rememberResponseContext(receiver, result);
                            return result;
                        });
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "OkHttp Response.body hook skipped: " + throwable);
            }
            Class<?> responseBuilder = Class.forName("okhttp3.Response$Builder", false, loader);
            try {
                hook(responseBuilder.getDeclaredMethod("build"))
                        .setId("okhttp_response_builder_build_context")
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            rememberResponseContext(result, null);
                            return result;
                        });
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "OkHttp Response.Builder hook skipped: " + throwable);
            }
            Class<?> responseBody = Class.forName("okhttp3.ResponseBody", false, loader);
            hook(responseBody.getDeclaredMethod("string"))
                    .setId("okhttp_response_body_string")
                    .intercept(chain -> {
                        Object body = chain.getThisObject();
                        Object result = chain.proceed();
                        if (result instanceof String) {
                            inspect((String) result, contextForBody(body, "OkHttp.string"));
                        }
                        return result;
                    });
            hook(responseBody.getDeclaredMethod("bytes"))
                    .setId("okhttp_response_body_bytes")
                    .intercept(chain -> {
                        Object body = chain.getThisObject();
                        Object result = chain.proceed();
                        if (result instanceof byte[]) {
                            inspectBytes((byte[]) result, contextForBody(body, "OkHttp.bytes"));
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "OkHttp hooks installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "OkHttp hook skipped: " + throwable);
        }
    }

    private void rememberResponseContext(Object response, Object knownBody) {
        if (response == null) {
            return;
        }
        try {
            Object request = response.getClass().getMethod("request").invoke(response);
            Object urlObject = request == null ? null : request.getClass().getMethod("url").invoke(request);
            String url = urlObject == null ? "" : urlObject.toString();
            Object body = knownBody;
            if (body == null) {
                body = response.getClass().getMethod("body").invoke(response);
            }
            if (body != null && !url.isEmpty()) {
                RESPONSE_CONTEXTS.put(body, contextFromUrl("OkHttp", url));
            }
        } catch (Throwable ignored) {
        }
    }

    private ParseContext contextForBody(Object body, String fallbackSource) {
        ParseContext context = body == null ? null : RESPONSE_CONTEXTS.get(body);
        return context == null ? ParseContext.simple(fallbackSource) : context;
    }

    private ParseContext contextFromUrl(String prefix, String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String source = prefix;
        if (lower.contains("getmylessons") || lower.contains("curriculum")) {
            source = prefix + ".timetable";
        } else if (lower.contains("taskactivelist") || lower.contains("activeapi")) {
            source = prefix + ".activity";
        } else if (lower.contains("work") || lower.contains("homework")) {
            source = prefix + ".work";
        } else if (lower.contains("exam") || lower.contains("ks_list")) {
            source = prefix + ".exam";
        } else if (lower.contains("backclazzdata") || lower.contains("mycourse") || lower.contains("clazz")) {
            source = prefix + ".course";
        } else if (lower.contains("knowledge") || lower.contains("chapter")) {
            source = prefix + ".chapter";
        }
        String courseId = queryParam(url, "courseId", "courseid", "courseIdStr", "courseidStr");
        String classId = queryParam(url, "classId", "classid", "clazzId", "clazzid", "id");
        String cpi = queryParam(url, "cpi", "cpiId");
        String uid = queryParam(url, "uid", "puid", "personid", "personId");
        String courseName = "";
        synchronized (COURSE_NAMES) {
            if (!courseId.isEmpty() && !classId.isEmpty()) {
                courseName = COURSE_NAMES.get(courseId + "|" + classId);
            }
            if ((courseName == null || courseName.isEmpty()) && !courseId.isEmpty()) {
                courseName = COURSE_NAMES.get(courseId);
            }
        }
        return ParseContext.forCourse(source, url, courseId, classId, cpi, uid, courseName);
    }

    private String queryParam(String url, String... names) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        for (String name : names) {
            Matcher matcher = Pattern.compile("(?i)(?:[?&]|&amp;)" + Pattern.quote(name) + "=([^&#]+)").matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private void installWebViewHooks() {
        try {
            Class<?> webView = Class.forName("android.webkit.WebView");
            hook(webView.getDeclaredMethod("loadUrl", String.class))
                    .setId("webview_load_url")
                    .intercept(chain -> {
                        Object url = chain.getArg(0);
                        if (url instanceof String) {
                            inspectUrl((String) url, "WebView.loadUrl");
                            markHomeUrl((String) url);
                        }
                        return chain.proceed();
                    });
            hook(webView.getDeclaredMethod("loadDataWithBaseURL", String.class, String.class, String.class, String.class, String.class))
                    .setId("webview_load_data_base")
                    .intercept(chain -> {
                        Object base = chain.getArg(0);
                        Object data = chain.getArg(1);
                        if (base instanceof String) {
                            inspectUrl((String) base, "WebView.loadDataWithBaseURL");
                            markHomeUrl((String) base);
                        }
                        if (data instanceof String) {
                            inspect((String) data, "WebView.loadDataWithBaseURL");
                        }
                        return chain.proceed();
                    });
            hook(webView.getDeclaredMethod("evaluateJavascript", String.class, android.webkit.ValueCallback.class))
                    .setId("webview_eval_js")
                    .intercept(chain -> {
                        Object script = chain.getArg(0);
                        if (script instanceof String) {
                            inspect((String) script, "WebView.evaluateJavascript");
                        }
                        return chain.proceed();
                    });
            installWebViewResourceHook();
            log(Log.INFO, TAG, "WebView hooks installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "WebView hook skipped: " + throwable);
        }
    }

    /**
     * WebView XHR/fetch responses — how the timetable H5 (schedule.html) actually
     * receives its data — only surface through shouldInterceptRequest. Two gaps
     * used to swallow every timetable payload:
     *
     * 1. Chaoxing ships its own WebViewClient subclass. When it overrides
     *    shouldInterceptRequest, a hook on the base class never fires, so we hook
     *    setWebViewClient and hang the intercept on the concrete class instead
     *    (both the WebResourceRequest and the deprecated String signature).
     * 2. When nobody intercepts, the default implementation returns null and the
     *    WebView core loads the body where no hook can ever see it. For
     *    timetable-ish URLs we then re-fetch the same URL ourselves (same cookies,
     *    same referer) so the payload still reaches the parser.
     */
    private void installWebViewResourceHook() throws Throwable {
        hookBaseShouldInterceptRequest(Class.forName("android.webkit.WebViewClient"));
        hookWebViewClientSetter("android.webkit");
        // Some builds ship the Tencent X5 kernel instead; its classes mirror the
        // framework's, and the reflection below is shape-compatible with both.
        try {
            Class.forName("com.tencent.smtt.sdk.WebView");
            hookWebViewClientSetter("com.tencent.smtt.sdk");
        } catch (Throwable ignored) {
        }
    }

    private void hookBaseShouldInterceptRequest(Class<?> clientClass) throws Throwable {
        for (Method method : clientClass.getDeclaredMethods()) {
            if (!"shouldInterceptRequest".equals(method.getName())
                    || method.getParameterTypes().length != 2
                    || !method.getParameterTypes()[1].getName().endsWith("WebResourceRequest")) {
                continue;
            }
            hook(method).setId("webview_should_intercept_request").intercept(chain -> {
                try {
                    Object response = chain.proceed();
                    return inspectWebRequest(chain.getArg(1), response);
                } catch (Throwable throwable) {
                    return null;
                }
            });
            return;
        }
    }

    /** Watches setWebViewClient so every concrete client class gets hooked once. */
    private void hookWebViewClientSetter(String pkg) throws Throwable {
        Class<?> webView = Class.forName(pkg + ".WebView");
        Class<?> client = Class.forName(pkg + ".WebViewClient");
        hook(webView.getDeclaredMethod("setWebViewClient", client))
                .setId("webview_set_client")
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object instance = chain.getArg(0);
                    if (instance != null) {
                        try {
                            hookWebViewClientClass(instance.getClass());
                        } catch (Throwable throwable) {
                            log(Log.WARN, TAG, "client hook skipped: " + throwable);
                        }
                    }
                    return result;
                });
    }

    private final Set<Class<?>> HOOKED_WEB_CLIENTS = new HashSet<>();

    /**
     * Hooks shouldInterceptRequest on the client's own class and every app class
     * above it up to the framework base — an override can sit on any link of the
     * chain, and each class is hooked at most once.
     */
    private void hookWebViewClientClass(Class<?> clientClass) throws Throwable {
        if (clientClass == null) {
            return;
        }
        int hooked = 0;
        Class<?> current = clientClass;
        while (current != null && !isBaseClientClass(current)) {
            synchronized (HOOKED_WEB_CLIENTS) {
                if (!HOOKED_WEB_CLIENTS.add(current)) {
                    break; // this class and its ancestors are already covered
                }
            }
            hooked += hookShouldInterceptRequest(current);
            current = current.getSuperclass();
        }
        if (hooked > 0) {
            log(Log.INFO, TAG, "hooked WebViewClient " + clientClass.getName() + " x" + hooked);
        }
    }

    private static boolean isBaseClientClass(Class<?> clientClass) {
        String name = clientClass.getName();
        return name.startsWith("android.webkit.") || name.startsWith("com.tencent.smtt.sdk.");
    }

    private int hookShouldInterceptRequest(Class<?> clientClass) throws Throwable {
        int hooked = 0;
        for (Method method : clientClass.getDeclaredMethods()) {
            if (!"shouldInterceptRequest".equals(method.getName())
                    || method.getParameterTypes().length != 2) {
                continue;
            }
            Class<?> second = method.getParameterTypes()[1];
            boolean requestSig = second.getName().endsWith("WebResourceRequest");
            boolean stringSig = second == String.class;
            if (!requestSig && !stringSig) {
                continue;
            }
            hook(method).setId("webview_client_should_intercept_request").intercept(chain -> {
                try {
                    Object response = chain.proceed();
                    return inspectWebRequest(chain.getArg(1), response);
                } catch (Throwable throwable) {
                    return null;
                }
            });
            hooked++;
        }
        return hooked;
    }

    /**
     * One shouldInterceptRequest call: learn the URL, hand the body to the parser
     * when somebody produced one (rebuilding it so the page still gets its bytes),
     * and self-fetch when the WebView core would have loaded it unseen.
     */
    private Object inspectWebRequest(Object request, Object response) {
        String url = urlFromWebRequest(request);
        if (url == null || url.isEmpty() || !shouldInspectWebResponse(url)) {
            return response;
        }
        rememberCurriculumUuid(url);
        byte[] bytes = readWebResponseBytes(response);
        if (bytes != null && bytes.length > 0) {
            try {
                inspect(new String(bytes, StandardCharsets.UTF_8), contextFromUrl("WebView", url));
            } catch (Throwable ignored) {
            }
            return rebuildWebResponse(response, bytes);
        }
        maybeSelfFetchTimetable(url);
        return response;
    }

    /** URL from either shouldInterceptRequest signature; reflection covers X5 too. */
    private static String urlFromWebRequest(Object request) {
        if (request instanceof String) {
            return (String) request;
        }
        try {
            Method getUrl = request.getClass().getMethod("getUrl");
            Object uri = getUrl.invoke(request);
            return uri == null ? "" : uri.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static byte[] readWebResponseBytes(Object response) {
        if (response == null) {
            return null;
        }
        try {
            Method getData = response.getClass().getMethod("getData");
            Object data = getData.invoke(response);
            if (data instanceof java.io.InputStream) {
                return readCapped((java.io.InputStream) data, 2_000_000);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Same response, same bytes — reading getData() consumed the original stream. */
    private static Object rebuildWebResponse(Object response, byte[] bytes) {
        try {
            Constructor<?> constructor = response.getClass().getConstructor(
                    String.class, String.class, java.io.InputStream.class);
            return constructor.newInstance(
                    stringMethod(response, "getMimeType"),
                    stringMethod(response, "getEncoding"),
                    new java.io.ByteArrayInputStream(bytes));
        } catch (Throwable throwable) {
            return response;
        }
    }

    private static String stringMethod(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            Object value = method.invoke(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static final String[] WEB_STATIC_EXTS = {
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp",
            ".css", ".js", ".woff", ".woff2", ".ttf", ".eot", ".otf",
            ".mp4", ".mp3", ".webm"
    };

    private static boolean shouldInspectWebResponse(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        String path = query > 0 ? lower.substring(0, query) : lower;
        for (String ext : WEB_STATIC_EXTS) {
            if (path.endsWith(ext)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The WebView core loads anything nobody intercepted — no hook can read that
     * body. For timetable-ish URLs we re-fetch the same URL ourselves (same
     * cookies, same referer) so a GET XHR still reaches the parser. Async: this
     * runs on a WebView network thread and blocking it would stall the page.
     */
    private void maybeSelfFetchTimetable(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("chaoxing")) {
            return;
        }
        boolean timetableish = lower.contains("curriculum")
                || lower.contains("schedule")
                || lower.contains("lesson")
                || !queryParam(url, "curriculumUuid", "curriculumuuid", "curriculum_uuid").isEmpty();
        if (!timetableish) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                int found = fetchUrl(url, ParseContext.fromSource("active.timetable", url), null);
                log(Log.INFO, TAG, "self-fetched timetable url, items=" + found + ": " + url);
            } catch (Throwable ignored) {
            }
        }, "ChaoxingDeadlineWebFetch");
        worker.setDaemon(true);
        worker.start();
    }

    private volatile boolean refreshReceiverRegistered;

    /**
     * The module app's refresh buttons have to reach into this process: the hook
     * drives all capture and nothing else can ask for a round. Registered exported
     * (the module app is a different uid) but token-authenticated — the token lives
     * in the module's remote prefs, which the module app keeps in sync.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRefreshRequestReceiver() {
        if (refreshReceiverRegistered) {
            return;
        }
        Context context = ensureHostContext();
        if (context == null) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleRefreshRequest(intent);
            }
        };
        IntentFilter filter = new IntentFilter(DeadlineReceiver.ACTION_REQUEST_REFRESH);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            refreshReceiverRegistered = true;
            log(Log.INFO, TAG, "refresh request receiver registered");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "refresh receiver registration failed: " + throwable);
        }
    }

    private void handleRefreshRequest(Intent intent) {
        String expected = "";
        try {
            SharedPreferences prefs = getRemotePreferences(BridgeAuth.PREFS_NAME);
            expected = prefs.getString(BridgeAuth.KEY_TOKEN, "");
        } catch (Throwable ignored) {
        }
        String actual = intent.getStringExtra(BridgeAuth.EXTRA_TOKEN);
        if (expected.isEmpty() || !expected.equals(actual)) {
            log(Log.WARN, TAG, "rejected unauthenticated refresh request");
            return;
        }
        int week = intent.getIntExtra("week", 0);
        if (week > 0) {
            log(Log.INFO, TAG, "timetable refresh requested for week " + week);
            Thread worker = new Thread(() -> {
                try {
                    fetchTimetable(week);
                    emitStatus("课表已更新", "moduleRequest");
                } catch (Throwable throwable) {
                    log(Log.WARN, TAG, "timetable refresh failed: " + throwable);
                }
            }, "ChaoxingDeadlineTimetableRefresh");
            worker.setDaemon(true);
            worker.start();
            return;
        }
        log(Log.INFO, TAG, "full refresh requested by the module app");
        requestActiveRefresh("moduleRequest", true);
    }

    private static byte[] readCapped(java.io.InputStream in, int cap) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            total += read;
            if (total > cap) {
                break;
            }
        }
        return out.toByteArray();
    }

    private void installActivityOverlayHook() {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume)
                    .setId("activity_on_resume_overlay")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object receiver = chain.getThisObject();
                        if (receiver instanceof Activity) {
                            Activity activity = (Activity) receiver;
                            currentActivityRef = new WeakReference<>(activity);
                            if (isLikelyHomeActivity(activity)) {
                                lastHomeSeenAt = System.currentTimeMillis();
                                ensureHomeButton(activity);
                                maybeShowOverlay(activity, true);
                            }
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "Activity overlay hook installed");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "Activity overlay hook skipped: " + throwable);
        }
    }

    private void ensureHomeButton(Activity activity) {
        if (!isActivityUsable(activity)) {
            return;
        }
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            FrameLayout content = decor instanceof FrameLayout
                    ? (FrameLayout) decor
                    : activity.findViewById(android.R.id.content);
            if (content == null || content.findViewWithTag(HOME_BUTTON_TAG) != null) {
                return;
            }
            TextView button = new TextView(activity);
            button.setTag(HOME_BUTTON_TAG);
            button.setText("Deadline");
            button.setTextSize(12);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextColor(Color.WHITE);
            button.setGravity(android.view.Gravity.CENTER);
            button.setAlpha(0.94f);
            button.setClickable(true);
            button.setFocusable(false);
            button.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 8));
            button.setBackground(UiTheme.fillOnly(activity, UiTheme.accent(activity), UiTheme.dp(activity, 999)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                button.setElevation(UiTheme.dp(activity, 24));
            }
            button.setOnClickListener(v -> showHomePanel(activity));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            params.setMargins(UiTheme.dp(activity, 12), UiTheme.dp(activity, 32), 0, 0);
            content.addView(button, params);
            log(Log.INFO, TAG, "home panel button attached");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "attach home panel button failed: " + throwable);
        }
    }

    private void showHomePanel(Activity activity) {
        if (!isActivityUsable(activity) || homePanelShowing) {
            return;
        }
        boolean shown = false;
        try {
            homePanelShowing = true;
            List<OverlayTodo> todos = homePanelTodos();
            long updatedAt = overlayDataUpdatedAt();
            final AlertDialog[] holder = new AlertDialog[1];
            View content = OverlayViews.homePanelView(activity, todos, updatedAt, homePanelScanStatus(),
                    () -> safeDismiss(holder[0]),
                    () -> switchHomePanel(holder[0], () -> showHomeSettingsPanel(activity)));
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(content)
                    .show();
            holder[0] = dialog;
            dialog.setOnDismissListener(d -> homePanelShowing = false);
            Window window = dialog.getWindow();
            if (window != null) {
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(width, -2);
            }
            shown = true;
            log(Log.INFO, TAG, "home panel shown: todos=" + todos.size());
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "show home panel failed: " + throwable);
        } finally {
            if (!shown) {
                homePanelShowing = false;
            }
        }
    }

    private void showHomeSettingsPanel(Activity activity) {
        if (!isActivityUsable(activity) || homePanelShowing) {
            return;
        }
        boolean shown = false;
        try {
            homePanelShowing = true;
            final AlertDialog[] holder = new AlertDialog[1];
            View content = OverlayViews.homeSettingsView(activity, overlayEnabled(), overlayWindowHours(),
                    () -> switchHomePanel(holder[0], () -> showHomePanel(activity)),
                    () -> safeDismiss(holder[0]),
                    () -> {
                        openModuleSettings(activity);
                        safeDismiss(holder[0]);
                    },
                    () -> {
                        setOverlayEnabledFromPanel(activity, !overlayEnabled());
                        switchHomePanel(holder[0], () -> showHomeSettingsPanel(activity));
                    },
                    hours -> {
                        setOverlayWindowFromPanel(activity, hours);
                        switchHomePanel(holder[0], () -> showHomeSettingsPanel(activity));
                    });
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(content)
                    .show();
            holder[0] = dialog;
            dialog.setOnDismissListener(d -> homePanelShowing = false);
            Window window = dialog.getWindow();
            if (window != null) {
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(width, -2);
            }
            shown = true;
            log(Log.INFO, TAG, "home settings panel shown");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "show home settings panel failed: " + throwable);
        } finally {
            if (!shown) {
                homePanelShowing = false;
            }
        }
    }

    private List<OverlayTodo> homePanelTodos() {
        ArrayList<OverlayTodo> todos = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            SharedPreferences prefs = getRemotePreferences(OverlayBridge.PREFS);
            String payload = prefs.getString(OverlayBridge.KEY_ITEMS, "[]");
            JSONArray array = new JSONArray(payload == null ? "[]" : payload);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null || json.optBoolean("submitted", false)) {
                    continue;
                }
                addPanelTodo(todos, new OverlayTodo(
                        json.optString("type", "事项"),
                        json.optString("title", "未命名"),
                        json.optString("course", ""),
                        json.optLong("dueAt", 0L)), now);
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "read home panel prefs failed: " + throwable);
        }
        synchronized (RECENT_ITEMS) {
            for (DeadlineItem item : RECENT_ITEMS) {
                if (item == null || item.submitted || item.submissionState == DeadlineItem.SUBMISSION_UNKNOWN) {
                    continue;
                }
                addPanelTodo(todos, new OverlayTodo(item.type, item.title, item.course, item.dueAt), now);
            }
        }
        todos.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
        return todos;
    }

    private void addPanelTodo(List<OverlayTodo> todos, OverlayTodo todo, long now) {
        if (todo == null || todo.dueAt <= now) {
            return;
        }
        if (!"作业".equals(todo.type) && !"考试".equals(todo.type)) {
            return;
        }
        for (int i = 0; i < todos.size(); i++) {
            OverlayTodo old = todos.get(i);
            if (OverlayViews.isSameOverlayTodo(old, todo)) {
                todos.set(i, OverlayViews.betterOverlayTodo(old, todo));
                return;
            }
        }
        todos.add(todo);
    }

    private long overlayDataUpdatedAt() {
        try {
            return getRemotePreferences(OverlayBridge.PREFS).getLong(OverlayBridge.KEY_UPDATED_AT, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private String homePanelScanStatus() {
        int refs = lastCourseScanRefs;
        if (refs > 0) {
            return "线程：" + Math.max(1, lastCourseScanThreads)
                    + "｜扫描：" + Math.min(Math.max(0, lastCourseScanScanned), refs)
                    + "/" + refs + " 门";
        }
        try {
            return CourseScanThreads.summary(courseScanPrefs());
        } catch (Throwable ignored) {
            return "线程：" + CourseScanThreads.DEFAULT + "｜暂无扫描记录";
        }
    }

    private void setOverlayEnabledFromPanel(Context context, boolean enabled) {
        overlayEnabledOverride = enabled;
        Intent intent = new Intent(DeadlineReceiver.ACTION_SETTINGS_UPDATE);
        intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
        intent.putExtra("overlay_enabled", enabled);
        sendAuthenticatedBroadcast(context, intent, "update overlay enabled");
    }

    private void setOverlayWindowFromPanel(Context context, int hours) {
        if (!isOverlayWindowOption(hours)) {
            return;
        }
        overlayWindowHoursOverride = hours;
        Intent intent = new Intent(DeadlineReceiver.ACTION_SETTINGS_UPDATE);
        intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
        intent.putExtra("overlay_window_hours", hours);
        sendAuthenticatedBroadcast(context, intent, "update overlay window");
    }

    private void sendAuthenticatedBroadcast(Context context, Intent intent, String label) {
        try {
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
            log(Log.INFO, TAG, label + " sent");
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, label + " failed: " + throwable);
        }
    }

    private void openModuleSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".MainActivity"));
            intent.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Throwable first) {
            try {
                Intent fallback = context.getPackageManager().getLaunchIntentForPackage(MODULE_PACKAGE);
                if (fallback != null) {
                    fallback.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    context.startActivity(fallback);
                }
            } catch (Throwable second) {
                log(Log.WARN, TAG, "open module settings failed: " + second);
            }
        }
    }

    private void maybeShowOverlay(Activity activity) {
        maybeShowOverlay(activity, true);
    }

    private void maybeShowOverlay(Activity activity, boolean requireHomeActivity) {
        if (!isActivityUsable(activity)) {
            return;
        }
        if (overlayDialogShowing || overlayScheduled) {
            return;
        }
        if (requireHomeActivity && !isLikelyHomeActivity(activity)) {
            log(Log.INFO, TAG, "overlay skipped: not on home activity");
            return;
        }
        long scheduledAt = System.currentTimeMillis();
        overlayScheduled = true;
        log(Log.INFO, TAG, "overlay waits for realtime data before cache fallback");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (overlayRealtimeShownAt >= scheduledAt) {
                    log(Log.INFO, TAG, "overlay cache fallback skipped: realtime shown");
                    return;
                }
                showOverlay(activity, requireHomeActivity, false);
            } finally {
                overlayScheduled = false;
            }
        }, OVERLAY_REALTIME_WAIT_MS);
    }

    private boolean showOverlay(Activity activity, boolean requireHomeActivity, boolean realtime) {
        boolean shown = false;
        try {
            if (!isActivityUsable(activity) || (requireHomeActivity && !isLikelyHomeActivity(activity))) {
                return false;
            }
            if (overlayDialogShowing) {
                if (realtime) {
                    overlayRetryAfterDialog = true;
                }
                return false;
            }
            if (!overlayEnabled()) {
                log(Log.INFO, TAG, "overlay skipped: disabled");
                return false;
            }
            List<OverlayTodo> todos = overlayTodos(realtime);
            log(Log.INFO, TAG, (realtime ? "realtime" : "cache") + " overlay todos=" + todos.size());
            if (todos.isEmpty()) {
                return false;
            }
            String fingerprint = overlayFingerprint(todos);
            long now = System.currentTimeMillis();
            if (fingerprint.equals(lastOverlayFingerprint)) {
                log(Log.INFO, TAG, "overlay skipped: unchanged");
                return false;
            }
            if (now - lastOverlayAt < OVERLAY_MIN_INTERVAL_MS) {
                log(Log.INFO, TAG, "overlay skipped: throttled "
                        + (now - lastOverlayAt) + "ms since last popup");
                return false;
            }
            overlayDialogShowing = true;
            lastOverlayAt = now;
            lastOverlayFingerprint = fingerprint;
            final AlertDialog[] holder = new AlertDialog[1];
            View content = OverlayViews.overlayView(activity, todos, realtime, overlayWindowHours(),
                    () -> safeDismiss(holder[0]));
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(content)
                    .show();
            holder[0] = dialog;
            shown = true;
            if (realtime) {
                overlayRealtimeShownGeneration = overlayRefreshGeneration;
                overlayRealtimeShownAt = System.currentTimeMillis();
            }
            dialog.setOnDismissListener(d -> {
                overlayDialogShowing = false;
                if (overlayRetryAfterDialog) {
                    overlayRetryAfterDialog = false;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> showOverlay(activity, false, true), 250L);
                }
            });
            Window window = dialog.getWindow();
            if (window != null) {
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.88f);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(width, -2);
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "show overlay failed: " + throwable);
        } finally {
            if (!shown) {
                overlayDialogShowing = false;
            }
        }
        return shown;
    }

    private static boolean isActivityUsable(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    private static void switchHomePanel(AlertDialog dialog, Runnable next) {
        try {
            if (dialog != null) {
                dialog.setOnDismissListener(null);
            }
        } catch (Throwable ignored) {
            // Some host windows disappear while Chaoxing switches pages.
        }
        safeDismiss(dialog);
        homePanelShowing = false;
        if (next != null) {
            next.run();
        }
    }

    private static void safeDismiss(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
            // Host Activity windows can disappear while Chaoxing switches pages.
        }
    }

    private void maybeShowOverlayAfterRefresh() {
        WeakReference<Activity> ref = currentActivityRef;
        Activity activity = ref == null ? null : ref.get();
        if (!isActivityUsable(activity)) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            if (overlayDialogShowing) {
                overlayRetryAfterDialog = true;
                return;
            }
            showOverlay(activity, false, true);
        });
    }

    private boolean overlayEnabled() {
        Boolean override = overlayEnabledOverride;
        if (override != null) {
            return override;
        }
        try {
            return getRemotePreferences("app_settings").getBoolean("overlay_enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private List<OverlayTodo> overlayTodos(boolean includeRecentItems) {
        ArrayList<OverlayTodo> todos = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<OverlayTodo> suppressed = new ArrayList<>();
        try {
            SharedPreferences prefs = getRemotePreferences(OverlayBridge.PREFS);
            String suppressedPayload = prefs.getString(OverlayBridge.KEY_SUPPRESSED, "[]");
            JSONArray suppressedArray = new JSONArray(suppressedPayload == null ? "[]" : suppressedPayload);
            for (int i = 0; i < suppressedArray.length(); i++) {
                JSONObject json = suppressedArray.optJSONObject(i);
                if (json == null) {
                    continue;
                }
                suppressed.add(new OverlayTodo(
                        json.optString("type", ""),
                        json.optString("title", ""),
                        "",
                        json.optLong("dueAt", 0L)));
            }

            String payload = prefs.getString(OverlayBridge.KEY_ITEMS, "[]");
            JSONArray array = new JSONArray(payload == null ? "[]" : payload);
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) {
                    continue;
                }
                if (json.optBoolean("submitted", false)) {
                    continue;
                }
                long dueAt = json.optLong("dueAt", 0L);
                String type = json.optString("type", "\u4e8b\u9879");
                OverlayTodo todo = new OverlayTodo(
                        type,
                        json.optString("title", "\u672a\u547d\u540d"),
                        json.optString("course", ""),
                        dueAt);
                if (!isSuppressedOverlayTodo(todo, suppressed)) {
                    addOverlayTodo(todos, todo, now);
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "read overlay prefs failed: " + throwable);
        }
        if (includeRecentItems) {
            synchronized (RECENT_ITEMS) {
                for (int i = RECENT_ITEMS.size() - 1; i >= 0; i--) {
                    DeadlineItem item = RECENT_ITEMS.get(i);
                    if (item == null || item.submitted) {
                        RECENT_ITEMS.remove(i);
                        continue;
                    }
                    OverlayTodo todo = new OverlayTodo(item.type, item.title, item.course, item.dueAt);
                    if (isSuppressedOverlayTodo(todo, suppressed)) {
                        RECENT_ITEMS.remove(i);
                        continue;
                    }
                    addOverlayTodo(todos, todo, now);
                }
            }
        }
        todos.sort((a, b) -> Long.compare(a.dueAt, b.dueAt));
        return todos;
    }

    private boolean isSuppressedOverlayTodo(OverlayTodo todo, List<OverlayTodo> suppressed) {
        if (todo == null || suppressed == null || suppressed.isEmpty()) {
            return false;
        }
        for (OverlayTodo old : suppressed) {
            if (isSameSuppressedOverlayTodo(old, todo)) {
                return true;
            }
        }
        return false;
    }

    // Suppressed identities are published by OverlayBridge with the exact stored dueAt, so
    // match exactly. The ±5-minute fuzzy matcher used here previously let one submitted
    // item hide a different, still-pending item whose deadline sat within five minutes.
    private boolean isSameSuppressedOverlayTodo(OverlayTodo a, OverlayTodo b) {
        if (a == null || b == null) {
            return false;
        }
        if (!safe(a.type).equals(safe(b.type))) {
            return false;
        }
        String titleA = OverlayViews.normalizeOverlayText(a.title);
        String titleB = OverlayViews.normalizeOverlayText(b.title);
        if (titleA.isEmpty() || !titleA.equals(titleB)) {
            return false;
        }
        return a.dueAt == b.dueAt;
    }

    /** Chapter tasks are opt-in; the overlay follows the notification switch. */
    private boolean chapterNotificationsEnabled() {
        try {
            return getRemotePreferences(AppSettings.PREFS).getBoolean("notify_chapter", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Classroom activities are opt-in for the same reason as chapter tasks. */
    private boolean activityNotificationsEnabled() {
        try {
            return getRemotePreferences(AppSettings.PREFS).getBoolean("notify_activity", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void addOverlayTodo(List<OverlayTodo> todos, OverlayTodo todo, long now) {
        if (todo == null || !isInOverlayWindow(todo.dueAt, now)) {
            return;
        }
        if (!"\u4f5c\u4e1a".equals(todo.type) && !"\u8003\u8bd5".equals(todo.type)) {
            // Chapter tasks and classroom activities reach the overlay only when their
            // notifications are on; both are opt-in because they tend to be numerous.
            boolean chapterOptIn = "\u7ae0\u8282".equals(todo.type) && chapterNotificationsEnabled();
            boolean activityOptIn = "\u8bfe\u5802\u6d3b\u52a8".equals(todo.type) && activityNotificationsEnabled();
            if (!chapterOptIn && !activityOptIn) {
                return;
            }
        }
        for (int i = 0; i < todos.size(); i++) {
            OverlayTodo old = todos.get(i);
            if (!OverlayViews.isSameOverlayTodo(old, todo)) {
                continue;
            }
            todos.set(i, OverlayViews.betterOverlayTodo(old, todo));
            return;
        }
        todos.add(todo);
    }

    private boolean isInOverlayWindow(long dueAt, long now) {
        if (dueAt <= now) {
            return false;
        }
        int hours = overlayWindowHours();
        if (hours == OVERLAY_WINDOW_ALL_HOURS) {
            return true;
        }
        return dueAt - now <= TimeUnit.HOURS.toMillis(hours);
    }

    private int overlayWindowHours() {
        int override = overlayWindowHoursOverride;
        if (isOverlayWindowOption(override)) {
            return override;
        }
        try {
            int value = getRemotePreferences("app_settings").getInt("overlay_window_hours", DEFAULT_OVERLAY_WINDOW_HOURS);
            return isOverlayWindowOption(value) ? value : DEFAULT_OVERLAY_WINDOW_HOURS;
        } catch (Throwable ignored) {
            return DEFAULT_OVERLAY_WINDOW_HOURS;
        }
    }

    private boolean isOverlayWindowOption(int value) {
        return value == 3 || value == 8 || value == 12 || value == 24 || value == 72
                || value == OVERLAY_WINDOW_ALL_HOURS;
    }

    private String overlayFingerprint(List<OverlayTodo> todos) {
        StringBuilder builder = new StringBuilder();
        for (OverlayTodo todo : todos) {
            if (todo == null) {
                continue;
            }
            builder.append(OverlayViews.overlayIdentity(todo)).append('|')
                    .append(todo.dueAt / (5L * 60L * 1000L)).append('\n');
        }
        return builder.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isRefreshEligibleActivity(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        String name = activity.getClass().getName().toLowerCase(Locale.ROOT);
        return !(name.contains("splash") || name.contains("ad") || name.contains("advert")
                || name.contains("welcome") || name.contains("guide") || name.contains("permission"));
    }

    private boolean isLikelyHomeActivity(Activity activity) {
        if (!isRefreshEligibleActivity(activity)) {
            return false;
        }
        String name = activity.getClass().getName().toLowerCase(Locale.ROOT);
        if (name.contains("splash") || name.contains("ad") || name.contains("advert")
                || name.contains("welcome") || name.contains("guide") || name.contains("permission")) {
            return false;
        }
        return name.contains("main") || name.contains("home") || name.contains("tab");
    }

    private void markHomeUrl(String url) {
        if (url == null) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if ((lower.contains("chaoxing") || lower.contains("fanya"))
                && (lower.contains("main") || lower.contains("home") || lower.contains("course"))) {
            lastHomeSeenAt = System.currentTimeMillis();
        }
    }

    private void rememberRecentItem(DeadlineItem item) {
        if (item == null || item.id == null || item.dueAt <= System.currentTimeMillis()) {
            return;
        }
        if (!"\u4f5c\u4e1a".equals(item.type) && !"\u8003\u8bd5".equals(item.type)) {
            return;
        }
        if (item.submitted || item.submissionState == DeadlineItem.SUBMISSION_UNKNOWN) {
            return;
        }
        OverlayTodo current = new OverlayTodo(item.type, item.title, item.course, item.dueAt);
        synchronized (RECENT_ITEMS) {
            long now = System.currentTimeMillis();
            for (int i = RECENT_ITEMS.size() - 1; i >= 0; i--) {
                DeadlineItem old = RECENT_ITEMS.get(i);
                if (old == null || item.id.equals(old.id) || old.dueAt <= now
                        || OverlayViews.isSameOverlayTodo(new OverlayTodo(old.type, old.title, old.course, old.dueAt), current)) {
                    RECENT_ITEMS.remove(i);
                }
            }
            RECENT_ITEMS.add(0, item);
            while (RECENT_ITEMS.size() > 12) {
                RECENT_ITEMS.remove(RECENT_ITEMS.size() - 1);
            }
        }
        WeakReference<Activity> ref = currentActivityRef;
        Activity activity = ref == null ? null : ref.get();
        if (activity != null) {
            if (isInOverlayWindow(item.dueAt, System.currentTimeMillis()) && (overlayDialogShowing || overlayScheduled)) {
                overlayRetryAfterDialog = true;
            }
            if (!activeRefreshRunning) {
                maybeShowOverlay(activity);
            }
        }
    }

    private int inspect(String text, String source) {
        return inspect(text, ParseContext.fromSource(source, ""));
    }

    private int inspect(String text, ParseContext context) {
        ParseContext ctx = context == null ? ParseContext.simple("") : context;
        if (Boolean.TRUE.equals(PARSING.get())) {
            return 0;
        }
        // Guard before collectAndFetchCourseTasks: its own JSONObject construction would
        // re-enter this hook and re-parse the same payload otherwise. Course fetches run
        // on executor threads with their own (fresh) ThreadLocal, so they are unaffected.
        PARSING.set(true);
        try {
            collectAndFetchCourseTasks(text, ctx);
            collectTimetable(text, ctx);
            List<DeadlineItem> items = DeadlineParser.parsePayload(text, ctx);
            if (items.isEmpty()) {
                return 0;
            }
            log(Log.INFO, TAG, String.format(Locale.ROOT, "hook v" + HOOK_VERSION + " found %d deadline items from %s", items.size(), ctx.source));
            for (DeadlineItem item : items) {
                enrichCourseName(item);
                rememberRecentItem(item);
                log(Log.INFO, TAG, "emit parsed item from " + ctx.source);
                emit(item);
            }
            emitStatus("captured " + items.size(), ctx.source);
            return items.size();
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "parse failed: " + throwable);
            return 0;
        } finally {
            PARSING.set(false);
        }
    }


    private void enrichCourseName(DeadlineItem item) {
        if (item == null) {
            return;
        }
        if (item.courseId == null || item.courseId.isEmpty()) {
            item.courseId = firstRegex(item.raw == null ? "" : item.raw, "(?:courseId|courseid)[\"']?\\s*[:=]\\s*[\"']?([0-9]+)[\"']?");
        }
        if (item.classId == null || item.classId.isEmpty()) {
            item.classId = firstRegex(item.raw == null ? "" : item.raw, "(?:classId|classid|clazzId|clazzid)[\"']?\\s*[:=]\\s*[\"']?([0-9]+)[\"']?");
        }
        synchronized (COURSE_NAMES) {
            String name = "";
            if (item.courseId != null && !item.courseId.isEmpty() && item.classId != null && !item.classId.isEmpty()) {
                name = COURSE_NAMES.get(item.courseId + "|" + item.classId);
            }
            if ((name == null || name.isEmpty()) && item.courseId != null && !item.courseId.isEmpty()) {
                name = COURSE_NAMES.get(item.courseId);
            }
            if (name != null && !name.isEmpty() && (item.course == null || item.course.isEmpty() || item.courseConfidence < 90)) {
                item.course = name;
                item.courseConfidence = 90;
            }
        }
    }

    private String firstRegex(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void inspectBytes(byte[] bytes, String source) {
        inspectBytes(bytes, ParseContext.simple(source));
    }

    private void inspectBytes(byte[] bytes, ParseContext context) {
        if (bytes == null || bytes.length < 8 || bytes.length > 2_000_000) {
            return;
        }
        inspect(new String(bytes, StandardCharsets.UTF_8), context);
    }

    private void inspectUrl(String url, String source) {
        if (url == null) {
            return;
        }
        rememberCurriculumUuid(url);
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("course") || lower.contains("work") || lower.contains("exam")
                || lower.contains("mooc") || lower.contains("fanya") || lower.contains("chaoxing")
                || lower.contains("章节") || lower.contains("作业") || lower.contains("考试")) {
            log(Log.INFO, TAG, source + ": matched deadline-related url");
            emitStatus("url", source);
        }
        inspect(url, ParseContext.fromSource(source, url));
    }

    private void requestActiveRefresh(String reason, boolean force) {
        long now = System.currentTimeMillis();
        synchronized (LIFECYCLE_LOCK) {
            if (!force && now - lastAutoRefreshAt < AUTO_REFRESH_MIN_GAP_MS) {
                log(Log.INFO, TAG, "active refresh skipped by cooldown, remaining="
                        + (AUTO_REFRESH_MIN_GAP_MS - (now - lastAutoRefreshAt)) + "ms reason=" + reason);
                return;
            }
            if (activeRefreshRunning) {
                log(Log.INFO, TAG, "active refresh skipped because another refresh is running, reason=" + reason);
                return;
            }
            lastAutoRefreshAt = now;
            activeRefreshRunning = true;
            overlayRefreshGeneration++;
        }
        activeRefresh(reason);
    }

    private void activeRefresh(String reason) {
        log(Log.INFO, TAG, "active refresh start, reason=" + reason);
        emitStatus("自动更新中", reason);
        Thread worker = new Thread(() -> {
            boolean completed = false;
            try {
                synchronized (FETCHED_URLS) {
                    FETCHED_URLS.clear();
                }
                synchronized (SEEN_ITEM_IDS) {
                    SEEN_ITEM_IDS.clear();
                }
                fetchUrl("https://mooc1-api.chaoxing.com/work/stu-work", "active.workPage");
                fetchUrl("https://mooc1-api.chaoxing.com/exam-ans/exam/phone/examcode", "active.examPage");
                fetchUrl("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1", "active.courseList");
                // Timetable last: COURSE_NAMES is fresh by then, which the in-class
                // boost needs to map a lesson onto its course.
                fetchTimetable();
                completed = true;
                emitStatus("自动更新完成", reason);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "active refresh failed: " + throwable);
                emitStatus("自动更新失败：" + throwable.getClass().getSimpleName(), reason);
            } finally {
                synchronized (LIFECYCLE_LOCK) {
                    activeRefreshRunning = false;
                }
                if (completed) {
                    maybeShowOverlayAfterRefresh();
                }
            }
        }, "ChaoxingDeadlineRefresh");
        worker.setDaemon(true);
        worker.start();
    }

    private int fetchUrl(String url, String source) {
        return fetchUrl(url, ParseContext.fromSource(source, url), null);
    }

    int fetchUrl(String url, ParseContext context) {
        return fetchUrl(url, context, null);
    }

    /**
     * @param formBody when non-null the request becomes a POST carrying this form body
     *                 (the timetable endpoint needs {@code POST week=N}; empty body =
     *                 current week).
     */
    int fetchUrl(String url, ParseContext context, String formBody) {
        ParseContext ctx = context == null ? ParseContext.fromSource("", url) : context.withUrl(url);
        String source = ctx.source;
        // The verification-code cooldown must cover every active.* fetch, not just the
        // course list: the per-course task-list / taskactivelist / exam endpoints are the
        // ones that actually trip anti-spider, and leaving them unguarded meant the next
        // refresh hammered them anyway. The 900ms pacing stays course-list-only.
        boolean activeFetch = source != null && source.startsWith("active.");
        boolean pacedUrl = source != null && source.startsWith("active.courseList");
        if (activeFetch && System.currentTimeMillis() < antiSpiderUntil) {
            log(Log.WARN, TAG, "skip active fetch while antispider cooldown is active, source=" + source);
            return -1;
        }
        synchronized (FETCHED_URLS) {
            if (!FETCHED_URLS.add(url)) {
                return 0;
            }
            if (FETCHED_URLS.size() > 800) {
                FETCHED_URLS.clear();
                FETCHED_URLS.add(url);
            }
        }
        HttpURLConnection connection = null;
        try {
            if (pacedUrl) {
                Thread.sleep(900L);
            }
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_6.4_android_phone");
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            connection.setRequestProperty("Accept-Language", "zh_CN");
            connection.setRequestProperty("Connection", "Keep-Alive");
            if (url.contains("mooc1-api.chaoxing.com/gas/clazz")) {
                connection.setRequestProperty("Referer", "https://mooc1-api.chaoxing.com/");
                connection.setRequestProperty("Host", "mooc1-api.chaoxing.com");
            }
            if (url.contains("kb.chaoxing.com")) {
                // The PC timetable endpoint answers 403/redirect without a same-origin
                // referer; the app process never sends one on its own.
                connection.setRequestProperty("Referer", "https://kb.chaoxing.com/");
            }
            if (formBody != null) {
                byte[] body = formBody.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            String cookie = cookieFor(url);
            if (!cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }
            int code = connection.getResponseCode();
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && builder.length() < 2_000_000) {
                    builder.append(line).append('\n');
                }
            }
            String body = builder.toString();
            log(Log.INFO, TAG, "active fetched " + code + " " + source + " len=" + body.length());
            if (body.contains("invalid_verify") || body.contains("请输入验证码")) {
                antiSpiderUntil = System.currentTimeMillis() + 30L * 60L * 1000L;
                log(Log.WARN, TAG, "active fetch paused because Chaoxing requested verification, source=" + source);
                emitStatus("接口触发验证码，已暂停一会儿", source);
                return -1;
            }
            return inspect(body, ctx);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "fetch failed " + source + ": " + throwable.getClass().getSimpleName());
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    String cookieFor(String url) {
        try {
            CookieManager manager = CookieManager.getInstance();
            String cookie = manager.getCookie(url);
            if (cookie == null || cookie.isEmpty()) {
                cookie = manager.getCookie("https://chaoxing.com/");
            }
            if (cookie == null || cookie.isEmpty()) {
                cookie = manager.getCookie("https://mooc1-api.chaoxing.com/");
            }
            return cookie == null ? "" : cookie;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void collectAndFetchCourseTasks(String text, ParseContext context) {
        String source = context == null ? "" : context.source;
        if (!source.startsWith("active.courseList")) {
            return;
        }
        try {
            String trimmed = text.trim();
            Object root = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
            ArrayList<CourseRef> refs = new ArrayList<>();
            CourseRefs.collectCourseRefs(root, refs, 0);
            for (CourseRef ref : refs) {
                logCourseFieldSummary(ref);
                rememberCourse(ref);
            }
            log(Log.INFO, TAG, "course refs cached from " + source + ": " + refs.size());
            new CourseScanner(this).fetchCourseDeadlines(refs);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "collect course refs failed: " + throwable);
        }
    }

    SharedPreferences courseScanPrefs() {
        try {
            return getRemotePreferences(COURSE_SCAN_PREFS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void logCourseFieldSummary(CourseRef ref) {
        if (ref == null || ref.raw.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject(ref.raw);
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext() && keys.size() < 80) {
                keys.add(iterator.next());
            }
            Collections.sort(keys);
            String signature = keys.toString();
            synchronized (COURSE_FIELD_SIGNATURES) {
                if (COURSE_FIELD_SIGNATURES.size() >= 8 || COURSE_FIELD_SIGNATURES.contains(signature)) {
                    return;
                }
                COURSE_FIELD_SIGNATURES.add(signature);
            }
            ArrayList<String> timeLike = new ArrayList<>();
            for (String key : keys) {
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.contains("time") || lower.contains("date") || lower.contains("start")
                        || lower.contains("begin") || lower.contains("end") || lower.contains("create")
                        || lower.contains("update")) {
                    timeLike.add(key);
                }
            }
            log(Log.INFO, TAG, "course raw fields: keys=" + signature + ", timeLike=" + timeLike);
        } catch (Throwable ignored) {
        }
    }

    private void rememberCourse(CourseRef ref) {
        if (ref == null || ref.courseName.isEmpty()) {
            return;
        }
        synchronized (COURSE_NAMES) {
            COURSE_NAMES.put(ref.courseId + "|" + ref.classId, ref.courseName);
            COURSE_NAMES.put(ref.courseId, ref.courseName);
        }
        emitCourse(ref);
    }

    private void emitCourse(CourseRef ref) {
        Context context = ensureHostContext();
        if (context == null || ref == null || ref.courseName == null || ref.courseName.trim().isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_COURSE);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("course", ref.courseName.trim());
            intent.putExtra("course_id", ref.courseId);
            intent.putExtra("class_id", ref.classId);
            intent.putExtra("cpi", ref.cpi);
            intent.putExtra("uid", ref.uid);
            intent.putExtra("raw", ref.raw);
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "emit course failed: " + throwable.getClass().getSimpleName());
        }
    }

    private void emit(DeadlineItem item) {
        rememberSeenItem(item);
        Context context = ensureHostContext();
        if (context == null) {
            synchronized (PENDING) {
                if (PENDING.size() < 100) {
                    PENDING.add(item);
                }
            }
            log(Log.WARN, TAG, "host context missing, queued deadline item");
            return;
        }
        log(Log.INFO, TAG, "emit deadline item type=" + item.type);
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_ITEM);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(DeadlineReceiver.EXTRA_ITEM_B64, Base64.getEncoder()
                    .encodeToString(item.toJson().toString().getBytes(StandardCharsets.UTF_8)));
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "broadcast item failed: " + throwable);
        }
    }

    /** Snapshot of the ids seen during the current round, for the revoked-item check. */
    String[] snapshotSeenItemIds() {
        synchronized (SEEN_ITEM_IDS) {
            return SEEN_ITEM_IDS.toArray(new String[0]);
        }
    }

    // -- weekly timetable (课程表) --

    /**
     * Pull the current teaching week's timetable. No {@code week} param: the endpoint
     * then answers with the current week (plus firstWeekDate and the school's section
     * table), which is exactly what the today/this-week surfaces need. A full-semester
     * view would need one request per week — the module app requests those on demand
     * (see {@link #fetchTimetable(int)}), one week at a time.
     */
    private void fetchTimetable() {
        fetchTimetable(0);
    }

    /**
     * @param week 1-based teaching week; 0 = whatever the endpoint calls current.
     *             schedule.html carries a curriculumUuid that identifies which
     *             timetable the user is looking at; forward it when known so
     *             multi-timetable accounts get the right one.
     */
    private void fetchTimetable(int week) {
        StringBuilder body = new StringBuilder();
        if (!curriculumUuid.isEmpty()) {
            body.append("curriculumUuid=").append(android.net.Uri.encode(curriculumUuid));
        }
        if (week > 0) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append("week=").append(week);
        }
        fetchUrl(TIMETABLE_URL, ParseContext.fromSource("active.timetable", TIMETABLE_URL),
                body.toString());
    }

    /** Remembers the curriculumUuid seen on any timetable URL (schedule.html, data calls). */
    private void rememberCurriculumUuid(String url) {
        if (url == null) {
            return;
        }
        String uuid = queryParam(url, "curriculumUuid", "curriculumuuid", "curriculum_uuid");
        if (!uuid.isEmpty()) {
            curriculumUuid = uuid;
            log(Log.INFO, TAG, "timetable uuid captured: " + uuid);
        }
    }

    /**
     * Recognise timetable payloads on the intercept path. The active fetch above is the
     * primary source; whatever endpoint the app's own 课表 page uses gets parsed here
     * for free. Identical payloads are skipped — the same JSON is seen by several hooks.
     */
    private void collectTimetable(String text, ParseContext context) {
        if (!TimetableParser.looksLikeTimetable(text)) {
            logUnknownTimetableShape(text, context);
            return;
        }
        String signature = Integer.toHexString(text.hashCode()) + ":" + text.length();
        if (signature.equals(lastTimetableSignature)) {
            return;
        }
        TimetableParser.Result result = TimetableParser.parse(text);
        if (result == null || result.lessons.isEmpty()) {
            return;
        }
        lastTimetableSignature = signature;
        rememberTimetable(result);
    }

    /**
     * A timetable endpoint answering with an unknown shape would otherwise fail
     * silently. Log one sample per payload shape so the parser can be adapted
     * (the log line carries the first 400 characters of the response).
     */
    private void logUnknownTimetableShape(String text, ParseContext context) {
        if (text == null || text.length() > 2_000_000) {
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String url = context == null ? "" : context.url;
        String urlLower = url.toLowerCase(Locale.ROOT);
        boolean suspicious = lower.contains("dayofweek") || lower.contains("curriculum")
                || urlLower.contains("curriculum") || urlLower.contains("lesson")
                || urlLower.contains("schedule");
        if (!suspicious) {
            return;
        }
        String signature = Integer.toHexString(text.hashCode()) + ":" + text.length();
        synchronized (UNKNOWN_TIMETABLE_SIGNATURES) {
            if (!UNKNOWN_TIMETABLE_SIGNATURES.add(signature)) {
                return;
            }
            if (UNKNOWN_TIMETABLE_SIGNATURES.size() > 20) {
                UNKNOWN_TIMETABLE_SIGNATURES.clear();
            }
        }
        String sample = text.substring(0, Math.min(400, text.length())).replace('\n', ' ');
        log(Log.WARN, TAG, "unparsed timetable-ish payload from " + url + ": " + sample);
    }

    private void rememberTimetable(TimetableParser.Result result) {
        synchronized (LESSONS) {
            LESSONS.clear();
            LESSONS.addAll(result.lessons);
        }
        if (result.firstWeekDate > 0L) {
            firstWeekDate = result.firstWeekDate;
        }
        if (result.sectionTimesJson != null && !result.sectionTimesJson.isEmpty()) {
            sectionTimesJson = result.sectionTimesJson;
        }
        buildCourseKeyIndex();
        emitTimetable(result);
        scheduleSessionTick();
        log(Log.INFO, TAG, "timetable stored: lessons=" + result.lessons.size()
                + " firstWeekDate=" + firstWeekDate);
    }

    /** name -> "courseId|classId" for the in-class boost; first class of a course wins. */
    private void buildCourseKeyIndex() {
        synchronized (COURSE_KEYS_BY_NAME) {
            COURSE_KEYS_BY_NAME.clear();
            synchronized (COURSE_NAMES) {
                for (Map.Entry<String, String> entry : COURSE_NAMES.entrySet()) {
                    String key = entry.getKey();
                    int bar = key.indexOf('|');
                    if (bar <= 0 || entry.getValue() == null || entry.getValue().isEmpty()) {
                        continue;
                    }
                    COURSE_KEYS_BY_NAME.putIfAbsent(entry.getValue(), key);
                }
            }
        }
    }

    private void emitTimetable(TimetableParser.Result result) {
        Context context = ensureHostContext();
        if (context == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (Lesson lesson : result.lessons) {
                array.put(lesson.toJson());
            }
            long firstWeek = result.firstWeekDate > 0L ? result.firstWeekDate : firstWeekDate;
            String times = result.sectionTimesJson != null && !result.sectionTimesJson.isEmpty()
                    ? result.sectionTimesJson : sectionTimesJson;
            Intent intent = new Intent(DeadlineReceiver.ACTION_TIMETABLE);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra("lessons", array.toString());
            intent.putExtra("first_week_date", firstWeek);
            intent.putExtra("section_times", times == null ? "" : times);
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "emit timetable failed: " + throwable.getClass().getSimpleName());
        }
    }

    private List<Lesson> lessonsSnapshot() {
        synchronized (LESSONS) {
            return new ArrayList<>(LESSONS);
        }
    }

    private String courseKeyForLesson(String courseName) {
        if (courseName == null || courseName.isEmpty()) {
            return null;
        }
        synchronized (COURSE_KEYS_BY_NAME) {
            return COURSE_KEYS_BY_NAME.get(courseName.trim());
        }
    }

    // -- in-class activity boost --

    private void scheduleSessionTick() {
        if (sessionTickScheduled) {
            return;
        }
        sessionTickScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(this::sessionTick, SESSION_TICK_MS);
    }

    private void sessionTick() {
        sessionTickScheduled = false;
        try {
            maybeBoostForClassSession();
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "session tick failed: " + throwable.getClass().getSimpleName());
        } finally {
            scheduleSessionTick();
        }
    }

    /**
     * While a class is in its first minutes, poll that course's classroom-activity list
     * — sign-ins and polls open exactly there. Paced by SESSION_REFRESH_GAP_MS and only
     * for the session's own course, so this costs one request per two minutes per class,
     * never a full refresh.
     */
    private void maybeBoostForClassSession() {
        try {
            if (!getRemotePreferences(AppSettings.PREFS).getBoolean("class_session_boost", true)) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        List<Lesson> lessons = lessonsSnapshot();
        if (lessons.isEmpty() || firstWeekDate <= 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        Lesson current = ScheduleMath.currentLesson(lessons, now, firstWeekDate, sectionTimesJson);
        if (current == null) {
            return;
        }
        long start = ScheduleMath.lessonStartAt(firstWeekDate,
                ScheduleMath.currentWeek(now, firstWeekDate), current.dayOfWeek,
                current.beginSection, sectionTimesJson);
        if (start <= 0L || now - start > SESSION_BOOST_WINDOW_MS) {
            return;
        }
        if (now - lastSessionRefreshAt < SESSION_REFRESH_GAP_MS) {
            return;
        }
        String courseKey = courseKeyForLesson(current.name);
        if (courseKey == null) {
            log(Log.INFO, TAG, "class session boost skipped, course key unknown: " + current.name);
            return;
        }
        lastSessionRefreshAt = now;
        refreshCourseActivities(courseKey, current.name);
    }

    private void refreshCourseActivities(String courseKey, String courseName) {
        int bar = courseKey.indexOf('|');
        if (bar <= 0) {
            return;
        }
        final String courseId = courseKey.substring(0, bar);
        final String classId = courseKey.substring(bar + 1);
        final String uid = uidFromCookies();
        String url = "https://mobilelearn.chaoxing.com/ppt/activeAPI/taskactivelist?courseId="
                + Uri.encode(courseId) + "&classId=" + Uri.encode(classId)
                + (uid.isEmpty() ? "" : "&uid=" + Uri.encode(uid));
        Thread worker = new Thread(() -> {
            try {
                fetchUrl(url, ParseContext.forCourse("active.taskList", url, courseId, classId, "", uid, courseName), null);
                emitStatus("上课期间已刷新课堂活动", "classSession:" + courseName);
            } catch (Throwable throwable) {
                log(Log.WARN, TAG, "class session activity refresh failed: "
                        + throwable.getClass().getSimpleName());
            }
        }, "ChaoxingDeadlineSessionBoost");
        worker.setDaemon(true);
        worker.start();
    }

    /** uid used by the activity endpoints; cookie-derived, no storage. */
    String uidFromCookies() {
        String[] urls = {
                "https://chaoxing.com/",
                "https://mooc1-api.chaoxing.com/",
                "https://mobilelearn.chaoxing.com/"
        };
        for (String url : urls) {
            String cookie = cookieFor(url);
            String uid = cookieValue(cookie, "UID");
            if (!uid.isEmpty()) {
                return uid;
            }
            uid = cookieValue(cookie, "_uid");
            if (!uid.isEmpty()) {
                return uid;
            }
            uid = cookieValue(cookie, "puid");
            if (!uid.isEmpty()) {
                return uid;
            }
        }
        return "";
    }

    private String cookieValue(String cookie, String name) {
        if (cookie == null || cookie.isEmpty()) {
            return "";
        }
        String[] parts = cookie.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (name.equalsIgnoreCase(trimmed.substring(0, equals).trim())) {
                return trimmed.substring(equals + 1).trim();
            }
        }
        return "";
    }

    private void rememberSeenItem(DeadlineItem item) {
        if (item == null || item.id == null || item.id.isEmpty()) {
            return;
        }
        synchronized (SEEN_ITEM_IDS) {
            if (SEEN_ITEM_IDS.size() < SEEN_ITEM_IDS_LIMIT) {
                SEEN_ITEM_IDS.add(item.id);
            }
        }
    }

    private void emitStatus(String status, String source) {
        Context context = ensureHostContext();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(DeadlineReceiver.ACTION_STATUS);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".DeadlineReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtra(DeadlineReceiver.EXTRA_STATUS, status);
            intent.putExtra(DeadlineReceiver.EXTRA_SOURCE, source);
            attachBridgeToken(intent);
            context.sendBroadcast(intent);
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "status emit failed: " + throwable);
        }
    }

    void attachBridgeToken(Intent intent) {
        try {
            SharedPreferences prefs = getRemotePreferences(BridgeAuth.PREFS_NAME);
            String token = prefs.getString(BridgeAuth.KEY_TOKEN, "");
            if (token != null && !token.isEmpty()) {
                intent.putExtra(BridgeAuth.EXTRA_TOKEN, token);
            } else {
                log(Log.WARN, TAG, "bridge token missing; open module app once to initialize bridge");
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "bridge token unavailable: " + throwable.getClass().getSimpleName());
        }
    }

    Context ensureHostContext() {
        Context context = hostContext;
        if (context != null) {
            return context;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            Object app = currentApplication.invoke(null);
            if (app instanceof Application) {
                hostContext = ((Application) app).getApplicationContext();
                log(Log.INFO, TAG, "host context recovered from currentApplication");
                flushPending();
                return hostContext;
            }
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            Object thread = currentActivityThread.invoke(null);
            if (thread != null) {
                Field initialApplication = activityThread.getDeclaredField("mInitialApplication");
                initialApplication.setAccessible(true);
                app = initialApplication.get(thread);
                if (app instanceof Application) {
                    hostContext = ((Application) app).getApplicationContext();
                    log(Log.INFO, TAG, "host context recovered from ActivityThread");
                    flushPending();
                    return hostContext;
                }
            }
        } catch (Throwable throwable) {
            log(Log.WARN, TAG, "recover host context failed: " + throwable.getClass().getSimpleName());
        }
        return null;
    }

    private void flushPending() {
        ArrayList<DeadlineItem> copy;
        synchronized (PENDING) {
            copy = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (DeadlineItem item : copy) {
            emit(item);
        }
    }

}
