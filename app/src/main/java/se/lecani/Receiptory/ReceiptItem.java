package se.lecani.Receiptory;

import org.json.JSONException;
import org.json.JSONObject;

public class ReceiptItem {
    public final String name;
    public final double cost;

    public ReceiptItem(String name, double cost) {
        this.name = name;
        this.cost = cost;
    }

    public static ReceiptItem fromJson(JSONObject json) {
        return new ReceiptItem(
                json.optString("name", "Okand vara"),
                json.optDouble("cost", 0)
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("cost", cost);
        return json;
    }
}
