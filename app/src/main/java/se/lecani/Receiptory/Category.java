package se.lecani.Receiptory;

import org.json.JSONException;
import org.json.JSONObject;

public class Category {
    public final String id;
    public String name;
    public String icon;
    public boolean deleted;
    public boolean aiSuggested;

    public Category(String id, String name, String icon) {
        this(id, name, icon, false, false);
    }

    public Category(String id, String name, String icon, boolean deleted, boolean aiSuggested) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.deleted = deleted;
        this.aiSuggested = aiSuggested;
    }

    public static Category fromJson(JSONObject json) {
        return new Category(
                json.optString("id"),
                json.optString("name"),
                json.optString("icon", "🧾"),
                json.optBoolean("deleted", false),
                json.optBoolean("aiSuggested", false)
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("icon", icon);
        if (deleted) {
            json.put("deleted", true);
        }
        if (aiSuggested) {
            json.put("aiSuggested", true);
        }
        return json;
    }
}
