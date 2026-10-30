package org.telegram.messenger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.ui.LaunchActivity;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TikConfigManager — central dynamic configuration engine for Tikgram.
 *
 * - SharedPreferences ("tikconfig") for persistence.
 * - ConcurrentHashMap in-memory cache for zero-latency main-thread lookups.
 * - Every flag is registered once in REGISTRY with type, category, factory
 *   default, and (for INT) slider bounds or (for STRING) picker options.
 *   Unknown keys are rejected on import; no silent junk is ever applied.
 * - Export serializes ONLY overridden flags to a .tikcfg JSON payload.
 * - Restart uses Intent.makeRestartActivityTask() so native singletons
 *   (MessagesController, ConnectionsManager, ...) are re-created fresh.
 *
 * Drop-in path:
 *   TMessagesProj/src/main/java/org/telegram/messenger/TikConfigManager.java
 * Init (ApplicationLoader.onCreate, right after super.onCreate()):
 *   TikConfigManager.getInstance().init(this);
 */
public class TikConfigManager {

    // ------------------------------------------------------------------
    // Annotation + categories (the five TikConfig sections)
    // ------------------------------------------------------------------

    @Retention(RetentionPolicy.RUNTIME)
    public @interface TikFlag {
        String key();
        Category category();
    }

    public enum Category {
        UI("UI & iOS Experience"),
        CHAT("Chat & Messaging"),
        MEDIA("Media & Streaming"),
        PRIVACY("Privacy & Guard"),
        NETWORK("Network & Auth");

        public final String title;
        Category(String title) {
            this.title = title;
        }
    }

    public enum Type {
        BOOLEAN, INT, STRING
    }

    public static class FlagDef {
        public final String key;
        public final Category category;
        public final Type type;
        public final Object defValue;
        public final String title;
        // INT slider bounds (ignored for other types)
        public final int min, max, step;
        // STRING picker options (null = free text)
        public final String[] options;

        public FlagDef(String key, Category category, Type type, Object defValue,
                       String title, int min, int max, int step, String[] options) {
            this.key = key;
            this.category = category;
            this.type = type;
            this.defValue = defValue;
            this.title = title;
            this.min = min;
            this.max = max;
            this.step = step;
            this.options = options;
        }
    }

    // ------------------------------------------------------------------
    // Registry — single source of truth. UI, export and import all read
    // from here; add a new flag HERE only.
    // ------------------------------------------------------------------

    private static final LinkedHashMap<String, FlagDef> REGISTRY = new LinkedHashMap<>();

    static {
        // ---- Chat & Messaging ----
        regBool("ghost_mode", Category.CHAT, false,
                "Ghost Mode (no read receipts)");
        regBool("ghost_typing", Category.CHAT, false,
                "Suppress typing & recording indicators");
        regBool("anti_revoke", Category.CHAT, false,
                "Anti-Revoke (keep deleted messages locally)");
        regOptions("bubble_style", Category.CHAT, "default",
                "Chat bubble style",
                new String[]{"default", "rounded", "square"});
        regOptions("double_tap_action", Category.CHAT, "react",
                "Double-tap message action",
                new String[]{"react", "reply", "none"});

        // ---- Media & Streaming ----
        regBool("video_note_from_gallery", Category.MEDIA, false,
                "Send gallery video as round video note");
        regOptions("video_note_resolution", Category.MEDIA, "720p",
                "Round video note resolution",
                new String[]{"480p", "720p", "1080p"});
        regSlider("video_note_fps", Category.MEDIA, 60, 30, 60, 30,
                "Round video note FPS");
        regSlider("video_note_bitrate", Category.MEDIA, 8, 2, 16, 2,
                "Round video note bitrate (Mbps)");
        regBool("incall_share_loop", Category.MEDIA, true,
                "Loop shared video in call");
        regOptions("incall_share_audio", Category.MEDIA, "mic",
                "In-call shared video audio",
                new String[]{"mic", "video"});

        // ---- Privacy & Guard ----
        regBool("privacy_slit", Category.PRIVACY, false,
                "Privacy Slit overlay");
        regSlider("privacy_slit_opacity", Category.PRIVACY, 100, 0, 100, 5,
                "Privacy Slit blackout opacity (%)");
        regSlider("privacy_slit_size", Category.PRIVACY, 40, 20, 60, 10,
                "Privacy Slit clear area size (%)");
        regBool("duress_pin_enabled", Category.PRIVACY, false,
                "Duress PIN (clean decoy profile)");
        regText("duress_pin", Category.PRIVACY, "",
                "Duress PIN value (local only)");

        // ---- UI & iOS Experience ----
        regBool("liquid_glass", Category.UI, false,
                "Liquid Glass blur surfaces");
        regSlider("liquid_glass_radius", Category.UI, 24, 0, 48, 8,
                "Glass blur radius");
        regSlider("glass_border_opacity", Category.UI, 35, 0, 100, 5,
                "Glass border opacity");
        regOptions("theme_accent", Category.UI, "default",
                "Theme accent color",
                new String[]{"default", "violet", "teal", "amber"});
        regBool("custom_font_enabled", Category.UI, false,
                "Use imported custom font");
        regText("custom_font_path", Category.UI, "",
                "Imported font file path");
        regBool("custom_emoji_enabled", Category.UI, false,
                "Use imported custom emoji pack");
        regText("custom_emoji_path", Category.UI, "",
                "Imported emoji pack path");

        // ---- Network & Auth ----
        regBool("prefer_email_code", Category.NETWORK, false,
                "Prefer email code at login (server decides)");
        regBool("code_flashcall", Category.NETWORK, true,
                "Allow flash-call code delivery");
        regBool("telemetry_suppress", Category.NETWORK, false,
                "Suppress optional telemetry");
    }

    private static void regBool(String key, Category c, boolean def, String title) {
        REGISTRY.put(key, new FlagDef(key, c, Type.BOOLEAN, def, title, 0, 0, 0, null));
    }

    private static void regSlider(String key, Category c, int def, int min, int max, int step, String title) {
        REGISTRY.put(key, new FlagDef(key, c, Type.INT, def, title, min, max, step, null));
    }

    private static void regOptions(String key, Category c, String def, String title, String[] options) {
        REGISTRY.put(key, new FlagDef(key, c, Type.STRING, def, title, 0, 0, 0, options));
    }

    private static void regText(String key, Category c, String def, String title) {
        REGISTRY.put(key, new FlagDef(key, c, Type.STRING, def, title, 0, 0, 0, null));
    }

    // ------------------------------------------------------------------
    // Singleton + storage
    // ------------------------------------------------------------------

    private static volatile TikConfigManager instance;

    private SharedPreferences prefs;
    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    public static TikConfigManager getInstance() {
        if (instance == null) {
            synchronized (TikConfigManager.class) {
                if (instance == null) {
                    instance = new TikConfigManager();
                }
            }
        }
        return instance;
    }

    /** Call once from ApplicationLoader.onCreate(). Safe to call twice. */
    public synchronized void init(Context context) {
        if (prefs != null) {
            return;
        }
        prefs = context.getApplicationContext()
                .getSharedPreferences("tikconfig", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (REGISTRY.containsKey(e.getKey())) {
                cache.put(e.getKey(), e.getValue());
            }
        }
    }

    private void ensureInit() {
        if (prefs == null) {
            init(ApplicationLoader.applicationContext);
        }
    }

    // ------------------------------------------------------------------
    // Typed accessors (cache-first, main-thread safe)
    // ------------------------------------------------------------------

    public boolean getBoolean(String key) {
        FlagDef def = require(key);
        Object v = cache.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return (Boolean) def.defValue;
    }

    public int getInt(String key) {
        FlagDef def = require(key);
        Object v = cache.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return (Integer) def.defValue;
    }

    public String getString(String key) {
        FlagDef def = require(key);
        Object v = cache.get(key);
        if (v instanceof String) {
            return (String) v;
        }
        return (String) def.defValue;
    }

    public void setBoolean(String key, boolean value) {
        requireType(key, Type.BOOLEAN);
        put(key, value);
    }

    public void setInt(String key, int value) {
        requireType(key, Type.INT);
        put(key, value);
    }

    public void setString(String key, String value) {
        requireType(key, Type.STRING);
        put(key, value == null ? "" : value);
    }

    private synchronized void put(String key, Object value) {
        ensureInit();
        cache.put(key, value);
        SharedPreferences.Editor ed = prefs.edit();
        if (value instanceof Boolean) {
            ed.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            ed.putInt(key, (Integer) value);
        } else {
            ed.putString(key, String.valueOf(value));
        }
        ed.apply();
    }

    /** True when the stored value differs from the factory default. */
    public boolean isOverridden(String key) {
        FlagDef def = require(key);
        Object v = cache.get(key);
        return v != null && !v.equals(def.defValue);
    }

    public List<FlagDef> allFlags() {
        return new ArrayList<>(REGISTRY.values());
    }

    public List<FlagDef> flagsFor(Category category) {
        List<FlagDef> out = new ArrayList<>();
        for (FlagDef def : REGISTRY.values()) {
            if (def.category == category) {
                out.add(def);
            }
        }
        return out;
    }

    public FlagDef getDef(String key) {
        return require(key);
    }

    // ------------------------------------------------------------------
    // Telegram API identity override — typed by the user on-device only.
    //
    // The debug build ships Telegram's published api_id/api_hash, and
    // Telegram's server flood-limits that published identity
    // (API_ID_PUBLISHED_FLOOD), which blocks login. These two values let
    // the user type his OWN api_id/api_hash inside the app (TikConfig
    // settings, or the login screen when login itself is blocked) and
    // have the connection/login layer use them instead.
    //
    // Storage: the same private "tikconfig" SharedPreferences, but under
    // keys that are deliberately NOT in REGISTRY, so they are never
    // exported into a .tikcfg file and never leave the device. Nothing
    // here is ever baked into the code or the repo.
    //
    // Semantics: empty/unset override == use the shipped built-in
    // values (BuildVars.APP_ID / APP_HASH). Changing the override
    // requires an app restart AND a fresh login: the native transport
    // fixes its api_id at process start (ConnectionsManager native
    // initConnection), and the stored session is tied to the old
    // identity, so a running session cannot switch identities.
    // ------------------------------------------------------------------

    private static final String KEY_API_ID_OVERRIDE = "api_id_override";
    private static final String KEY_API_HASH_OVERRIDE = "api_hash_override";

    /** Lazy prefs load for callers that run before ApplicationLoader.onCreate's init. */
    private void ensurePrefs() {
        if (prefs == null && ApplicationLoader.applicationContext != null) {
            init(ApplicationLoader.applicationContext);
        }
    }

    /** Resolved api_id: user override when set (> 0), else the shipped built-in. */
    public int getApiId() {
        ensurePrefs();
        int id = prefs != null ? prefs.getInt(KEY_API_ID_OVERRIDE, 0) : 0;
        return id > 0 ? id : BuildVars.APP_ID;
    }

    /** Resolved api_hash: user override when set (non-empty), else the shipped built-in. */
    public String getApiHash() {
        ensurePrefs();
        String hash = prefs != null ? prefs.getString(KEY_API_HASH_OVERRIDE, "") : "";
        return hash != null && !hash.isEmpty() ? hash : BuildVars.APP_HASH;
    }

    /** Raw stored api_id override, or 0 when unset (for UI prefill). */
    public int getApiIdOverride() {
        ensurePrefs();
        return prefs != null ? prefs.getInt(KEY_API_ID_OVERRIDE, 0) : 0;
    }

    /** Raw stored api_hash override, or "" when unset (for UI prefill). */
    public String getApiHashOverride() {
        ensurePrefs();
        String hash = prefs != null ? prefs.getString(KEY_API_HASH_OVERRIDE, "") : "";
        return hash != null ? hash : "";
    }

    /** True when either override value is set. */
    public boolean hasApiOverride() {
        return getApiIdOverride() > 0 || !getApiHashOverride().isEmpty();
    }

    /** Store the pair typed by the user. Non-positive id / empty hash clears that side.
     *  Uses commit() (synchronous) on purpose: callers restart the app right
     *  after saving (restartApp kills the process with exit(0)), and an async
     *  apply() can be lost when the process dies before the write flushes —
     *  which made saved values vanish after restart. */
    public void setApiCredentials(int apiId, String apiHash) {
        ensurePrefs();
        if (prefs == null) {
            return;
        }
        SharedPreferences.Editor ed = prefs.edit();
        if (apiId > 0) {
            ed.putInt(KEY_API_ID_OVERRIDE, apiId);
        } else {
            ed.remove(KEY_API_ID_OVERRIDE);
        }
        if (apiHash != null && !apiHash.isEmpty()) {
            ed.putString(KEY_API_HASH_OVERRIDE, apiHash);
        } else {
            ed.remove(KEY_API_HASH_OVERRIDE);
        }
        ed.commit();
    }

    /** Clear the override so the shipped built-in values are used again.
     *  Synchronous commit, same reason as setApiCredentials: a restart
     *  may follow immediately. */
    public void resetApiCredentials() {
        ensurePrefs();
        if (prefs == null) {
            return;
        }
        prefs.edit().remove(KEY_API_ID_OVERRIDE).remove(KEY_API_HASH_OVERRIDE).commit();
    }

    // ------------------------------------------------------------------
    // Export / import (.tikcfg JSON) — only overridden flags exported.
    // Import validates every key and type before applying anything.
    // ------------------------------------------------------------------

    public String exportJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "tikcfg");
        root.put("version", 1);
        JSONObject flags = new JSONObject();
        for (Map.Entry<String, FlagDef> e : REGISTRY.entrySet()) {
            if (isOverridden(e.getKey())) {
                flags.put(e.getKey(), cache.get(e.getKey()));
            }
        }
        root.put("flags", flags);
        return root.toString();
    }

    public boolean importJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!"tikcfg".equals(root.optString("format"))) {
                return false;
            }
            JSONObject flags = root.getJSONObject("flags");
            Map<String, Object> staged = new LinkedHashMap<>();
            Iterator<String> keys = flags.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                FlagDef def = REGISTRY.get(key);
                if (def == null) {
                    return false; // unknown key
                }
                Object v = flags.get(key);
                switch (def.type) {
                    case BOOLEAN:
                        if (!(v instanceof Boolean)) return false;
                        break;
                    case INT:
                        if (!(v instanceof Number)) return false;
                        v = ((Number) v).intValue();
                        break;
                    case STRING:
                        if (!(v instanceof String)) return false;
                        break;
                }
                staged.put(key, v);
            }
            for (Map.Entry<String, Object> e : staged.entrySet()) {
                put(e.getKey(), e.getValue());
            }
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /** Master reset: clears every override back to factory defaults. */
    public synchronized void resetAll() {
        ensureInit();
        prefs.edit().clear().apply();
        cache.clear();
    }

    /** Instant process restart so hooks and native singletons reload. */
    public static void restartApp(Context context) {
        Intent intent = Intent.makeRestartActivityTask(
                new ComponentName(context, LaunchActivity.class));
        context.startActivity(intent);
        Runtime.getRuntime().exit(0);
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    private static FlagDef require(String key) {
        FlagDef def = REGISTRY.get(key);
        if (def == null) {
            throw new IllegalArgumentException("Unknown TikConfig key: " + key);
        }
        return def;
    }

    private static void requireType(String key, Type type) {
        if (require(key).type != type) {
            throw new IllegalArgumentException("Key " + key + " is not " + type);
        }
    }
}
