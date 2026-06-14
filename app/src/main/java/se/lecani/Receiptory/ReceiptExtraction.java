package se.lecani.Receiptory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ReceiptExtraction {
    public final String merchant;
    public final String date;
    public final double total;
    public final String rawText;
    public final List<ReceiptItem> items;
    public final String categoryAction;
    public final String existingCategoryId;
    public final String newCategoryName;

    public ReceiptExtraction(
            String merchant,
            String date,
            double total,
            String rawText,
            List<ReceiptItem> items,
            String categoryAction,
            String existingCategoryId,
            String newCategoryName
    ) {
        this.merchant = merchant;
        this.date = date;
        this.total = total;
        this.rawText = rawText;
        this.items = items;
        this.categoryAction = categoryAction;
        this.existingCategoryId = existingCategoryId;
        this.newCategoryName = newCategoryName;
    }

    public static ReceiptExtraction fromJson(JSONObject json) {
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

        JSONObject category = json.optJSONObject("category_decision");
        if (category == null) {
            category = new JSONObject();
        }

        return new ReceiptExtraction(
                json.optString("merchant", "Unknown merchant"),
                json.optString("date", ""),
                json.optDouble("total", 0),
                json.optString("raw_text", ""),
                items,
                category.optString("action", "create_new"),
                category.optString("existing_category_id", ""),
                category.optString("new_category_name", "Other")
        );
    }
}
