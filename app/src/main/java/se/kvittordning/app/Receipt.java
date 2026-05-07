package se.kvittordning.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Receipt {
    public final String id;
    public String categoryId;
    public String merchant;
    public String date;
    public double total;
    public final String photoPath;
    public List<ReceiptItem> items;
    public String rawText;
    public final long createdAt;

    public Receipt(
            String id,
            String categoryId,
            String merchant,
            String date,
            double total,
            String photoPath,
            List<ReceiptItem> items,
            String rawText,
            long createdAt
    ) {
        this.id = id;
        this.categoryId = categoryId;
        this.merchant = merchant;
        this.date = date;
        this.total = total;
        this.photoPath = photoPath;
        this.items = items;
        this.rawText = rawText;
        this.createdAt = createdAt;
    }

    public static Receipt fromJson(JSONObject json) {
        List<ReceiptItem> items = new ArrayList<>();
        JSONArray itemArray = json.optJSONArray("items");
        if (itemArray != null) {
            for (int index = 0; index < itemArray.length(); index++) {
                JSONObject itemJson = itemArray.optJSONObject(index);
                if (itemJson != null) {
                    items.add(ReceiptItem.fromJson(itemJson));
                }
            }
        }

        return new Receipt(
                json.optString("id"),
                json.optString("categoryId"),
                json.optString("merchant"),
                json.optString("date"),
                json.optDouble("total", 0),
                json.optString("photoPath"),
                items,
                json.optString("rawText"),
                json.optLong("createdAt", 0)
        );
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        JSONArray itemArray = new JSONArray();
        for (ReceiptItem item : items) {
            itemArray.put(item.toJson());
        }

        json.put("id", id);
        json.put("categoryId", categoryId);
        json.put("merchant", merchant);
        json.put("date", date);
        json.put("total", total);
        json.put("photoPath", photoPath);
        json.put("items", itemArray);
        json.put("rawText", rawText);
        json.put("createdAt", createdAt);
        return json;
    }
}
