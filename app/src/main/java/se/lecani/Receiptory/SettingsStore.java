package se.lecani.Receiptory;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Currency;
import java.util.Locale;
import java.util.UUID;

public class SettingsStore {
    public static final String DEFAULT_BACKEND_URL = "http://lecani.se:8787";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4.1-mini";
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private static final String PREFS = "kvittordning_settings";
    private static final String KEY_ALLOW_AI_NEW_CATEGORIES = "allow_ai_new_categories";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_INSTALLATION_ID = "installation_id";
    private static final String KEY_OPENAI_API_KEY = "openai_api_key";
    private static final String KEY_OPENAI_MODEL = "openai_model";
    private static final String KEY_THEME = "theme";
    private static final String KEY_USE_OWN_OPENAI_KEY = "use_own_openai_key";

    private final SharedPreferences preferences;

    public SettingsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getBackendUrl() {
        return DEFAULT_BACKEND_URL;
    }

    public void setBackendUrl(String backendUrl) {
        // Backend URL is fixed for regular app builds.
    }

    public boolean useOwnOpenAiKey() {
        return preferences.getBoolean(KEY_USE_OWN_OPENAI_KEY, false);
    }

    public void setUseOwnOpenAiKey(boolean useOwnOpenAiKey) {
        preferences.edit().putBoolean(KEY_USE_OWN_OPENAI_KEY, useOwnOpenAiKey).apply();
    }

    public String getOpenAiApiKey() {
        return preferences.getString(KEY_OPENAI_API_KEY, "");
    }

    public void setOpenAiApiKey(String apiKey) {
        preferences.edit().putString(KEY_OPENAI_API_KEY, apiKey == null ? "" : apiKey.trim()).apply();
    }

    public String getOpenAiModel() {
        String model = preferences.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL);
        if (model == null || model.trim().isEmpty()) {
            return DEFAULT_OPENAI_MODEL;
        }
        return model.trim();
    }

    public void setOpenAiModel(String model) {
        String cleanModel = model == null ? "" : model.trim();
        preferences.edit().putString(KEY_OPENAI_MODEL, cleanModel.isEmpty() ? DEFAULT_OPENAI_MODEL : cleanModel).apply();
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
