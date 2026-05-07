package se.kvittordning.app;

import org.json.JSONException;
import org.json.JSONObject;

public class Category {
    public final String id;
    public String name;
    public String icon;

    public Category(String id, String name, String icon) {
        this.id = id;
        this.name = name;
        this.icon = icon;
    }

    public static Category fromJson(JSONObject json) {
        return new Category(
                json.optString("id"),
                json.optString("name"),
                json.optString("icon", "🧾")
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("icon", icon);
        return json;
    }
}
