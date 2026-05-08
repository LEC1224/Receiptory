package se.kvittordning.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Currency;
import java.util.Locale;

public class SettingsStore {
    public static final String DEFAULT_MODEL = "gpt-4.1-mini";
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private static final String PREFS = "kvittordning_settings";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_ALLOW_AI_NEW_CATEGORIES = "allow_ai_new_categories";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_MODEL = "model";
    private static final String KEY_THEME = "theme";

    private final SharedPreferences preferences;

    public SettingsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getApiKey() {
        return preferences.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String apiKey) {
        preferences.edit().putString(KEY_API_KEY, apiKey == null ? "" : apiKey.trim()).apply();
    }

    public String getModel() {
        String model = preferences.getString(KEY_MODEL, DEFAULT_MODEL);
        if (model == null || model.trim().isEmpty()) {
            return DEFAULT_MODEL;
        }
        return model.trim();
    }

    public void setModel(String model) {
        String cleanModel = model == null ? "" : model.trim();
        preferences.edit().putString(KEY_MODEL, cleanModel.isEmpty() ? DEFAULT_MODEL : cleanModel).apply();
    }

    public boolean allowAiNewCategories() {
        return preferences.getBoolean(KEY_ALLOW_AI_NEW_CATEGORIES, true);
    }

    public void setAllowAiNewCategories(boolean allow) {
        preferences.edit().putBoolean(KEY_ALLOW_AI_NEW_CATEGORIES, allow).apply();
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
