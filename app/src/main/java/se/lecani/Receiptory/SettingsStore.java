package se.lecani.Receiptory;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Currency;
import java.util.Locale;

public class SettingsStore {
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private static final String PREFS = "kvittordning_settings";
    private static final String KEY_ALLOW_AI_NEW_CATEGORIES = "allow_ai_new_categories";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_THEME = "theme";
    private static final String MERCHANT_CATEGORY_PREFIX = "category_for_merchant:";

    private final SharedPreferences preferences;

    public SettingsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (preferences.contains("openai_api_key") || preferences.contains("installation_id")
                || preferences.contains("openai_model") || preferences.contains("use_own_openai_key")) {
            preferences.edit().remove("openai_api_key").remove("installation_id")
                    .remove("openai_model").remove("use_own_openai_key").apply();
        }
    }

    public boolean allowSuggestedCategories() {
        return preferences.getBoolean(KEY_ALLOW_AI_NEW_CATEGORIES, true);
    }

    public void setAllowSuggestedCategories(boolean allow) {
        preferences.edit().putBoolean(KEY_ALLOW_AI_NEW_CATEGORIES, allow).apply();
    }

    String categoryForMerchant(String merchant) {
        return preferences.getString(MERCHANT_CATEGORY_PREFIX + ReceiptParser.key(merchant), "");
    }

    void rememberCategory(String merchant, String categoryId) {
        String key = ReceiptParser.key(merchant);
        if (!key.isEmpty() && !"receipt".equals(key) && !"unknown merchant".equals(key)) {
            preferences.edit().putString(MERCHANT_CATEGORY_PREFIX + key, categoryId).apply();
        }
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
