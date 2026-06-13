package se.kvittordning.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

public class SettingsStore {
    public static final String DEFAULT_BACKEND_URL = "http://10.0.2.2:8787";
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private static final String PREFS = "kvittordning_settings";
    private static final String KEY_ALLOW_AI_NEW_CATEGORIES = "allow_ai_new_categories";
    private static final String KEY_BACKEND_URL = "backend_url";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_INSTALLATION_ID = "installation_id";
    private static final String KEY_THEME = "theme";

    private final SharedPreferences preferences;

    public SettingsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getBackendUrl() {
        String backendUrl = preferences.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL);
        if (backendUrl == null || backendUrl.trim().isEmpty()) {
            return DEFAULT_BACKEND_URL;
        }
        return backendUrl.trim();
    }

    public void setBackendUrl(String backendUrl) {
        String cleanUrl = backendUrl == null ? "" : backendUrl.trim();
        preferences.edit().putString(KEY_BACKEND_URL, cleanUrl.isEmpty() ? DEFAULT_BACKEND_URL : cleanUrl).apply();
    }

    public boolean allowAiNewCategories() {
        return preferences.getBoolean(KEY_ALLOW_AI_NEW_CATEGORIES, true);
    }

    public void setAllowAiNewCategories(boolean allow) {
        preferences.edit().putBoolean(KEY_ALLOW_AI_NEW_CATEGORIES, allow).apply();
    }

    public String getInstallationId() {
        String installationId = preferences.getString(KEY_INSTALLATION_ID, "");
        if (installationId == null || installationId.trim().isEmpty()) {
            installationId = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_INSTALLATION_ID, installationId).apply();
        }
        return installationId;
    }

    public String getCurrencyCode() {
        String fallback = "USD";
        try {
            fallback = Currency.getInstance(Locale.getDefault()).getCurrencyCode();
        } catch (IllegalArgumentException ignored) {
        }
        String code = preferences.getString(KEY_CURRENCY, fallback);
        if (code == null || code.trim().isEmpty()) {
            return fallback;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    public void setCurrencyCode(String currencyCode) {
        String cleanCode = currencyCode == null ? "" : currencyCode.trim().toUpperCase(Locale.ROOT);
        preferences.edit().putString(KEY_CURRENCY, cleanCode).apply();
    }

    public String getTheme() {
        return preferences.getString(KEY_THEME, THEME_SYSTEM);
    }

    public void setTheme(String theme) {
        preferences.edit().putString(KEY_THEME, theme).apply();
    }
}
