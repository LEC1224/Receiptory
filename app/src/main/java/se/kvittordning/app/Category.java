package se.kvittordning.app;

import org.json.JSONException;
import org.json.JSONObject;

public class Category {
    public final String id;
    public String name;
    public String icon;
    public boolean deleted;

    public Category(String id, String name, String icon) {
        this(id, name, icon, false);
    }

    public Category(String id, String name, String icon, boolean deleted) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.deleted = deleted;
    }

    public static Category fromJson(JSONObject json) {
        return new Category(
                json.optString("id"),
                json.optString("name"),
                json.optString("icon", "🧾"),
                json.optBoolean("deleted", false)
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
        return json;
    }
}
