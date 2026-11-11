package se.lecani.Receiptory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Receipt {
    public static final String AI_SCAN_MANUAL = "manual";
    public static final String AI_SCAN_EXTRACTED = "extracted";
    public static final String AI_SCAN_FAILED = "failed";

    public final String id;
    public String categoryId;
    public String merchant;
    public String date;
    public Double total;
    public final String photoPath;
    public List<ReceiptItem> items;
    public String rawText;
    public final long createdAt;
    public boolean archived;
    public String aiScanStatus;
    public long aiScannedAt;
    public String aiScanError;
    public boolean itemsNeedReview;

    public Receipt(
            String id,
            String categoryId,
            String merchant,
            String date,
            Double total,
            String photoPath,
            List<ReceiptItem> items,
            String rawText,
            long createdAt
    ) {
        this(id, categoryId, merchant, date, total, photoPath, items, rawText, createdAt, false);
    }

    public Receipt(
            String id,
            String categoryId,
            String merchant,
            String date,
            Double total,
            String photoPath,
            List<ReceiptItem> items,
            String rawText,
            long createdAt,
            boolean archived
    ) {
        this(
                id,
                categoryId,
                merchant,
                date,
                total,
                photoPath,
                items,
                rawText,
                createdAt,
                archived,
                AI_SCAN_EXTRACTED,
                createdAt,
                ""
        );
    }

    public Receipt(
            String id,
            String categoryId,
            String merchant,
            String date,
            Double total,
            String photoPath,
            List<ReceiptItem> items,
            String rawText,
            long createdAt,
            boolean archived,
            String aiScanStatus,
            long aiScannedAt,
            String aiScanError
    ) {
        this.id = id;
        this.categoryId = categoryId;
        this.merchant = merchant == null ? "" : merchant;
        this.date = date == null ? "" : date;
        this.total = total;
        this.photoPath = photoPath;
        this.items = items == null ? new ArrayList<>() : items;
        this.rawText = rawText == null ? "" : rawText;
        this.createdAt = createdAt;
        this.archived = archived;
        this.aiScanStatus = cleanAiScanStatus(aiScanStatus);
        this.aiScannedAt = aiScannedAt;
        this.aiScanError = aiScanError == null ? "" : aiScanError;
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

        String rawText = json.optString("rawText");
        String defaultScanStatus = items.isEmpty() && rawText.trim().isEmpty()
                ? AI_SCAN_MANUAL
                : AI_SCAN_EXTRACTED;

        String scanStatus = json.optString("aiScanStatus", defaultScanStatus);
        double amount = json.optDouble("total", Double.NaN);
        Double total = Double.isNaN(amount) || Double.isInfinite(amount) ? null : amount;
        // Before totals could be blank, untouched manual drafts used 0 as a placeholder.
        if (json.optInt("schemaVersion", 1) < 2 && total != null && total == 0 && !AI_SCAN_EXTRACTED.equals(scanStatus)
                && json.optString("merchant").trim().isEmpty() && json.optString("date").trim().isEmpty()) {
            total = null;
        }
        Receipt receipt = new Receipt(
                json.optString("id"),
                json.optString("categoryId"),
                json.optString("merchant"),
                json.optString("date"),
                total,
                json.optString("photoPath"),
                items,
                rawText,
                json.optLong("createdAt", 0),
                json.optBoolean("archived", false),
                scanStatus,
                json.optLong("aiScannedAt", 0),
                json.optString("aiScanError", "")
        );
        receipt.itemsNeedReview = json.optBoolean("itemsNeedReview");
        return receipt;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        JSONArray itemArray = new JSONArray();
        for (ReceiptItem item : items) {
            itemArray.put(item.toJson());
        }

        json.put("id", id);
        json.put("schemaVersion", 2);
        json.put("categoryId", categoryId);
        json.put("merchant", merchant);
        json.put("date", date);
        json.put("total", total == null ? JSONObject.NULL : total);
        json.put("photoPath", photoPath);
        json.put("items", itemArray);
        json.put("rawText", rawText);
        json.put("createdAt", createdAt);
        json.put("aiScanStatus", aiScanStatus);
        if (itemsNeedReview) {
            json.put("itemsNeedReview", true);
        }
        if (aiScannedAt > 0) {
            json.put("aiScannedAt", aiScannedAt);
        }
        if (aiScanError != null && !aiScanError.trim().isEmpty()) {
            json.put("aiScanError", aiScanError);
        }
        if (archived) {
            json.put("archived", true);
        }
        return json;
    }

    public boolean isAiUnscanned() {
        return AI_SCAN_MANUAL.equals(aiScanStatus) && aiScannedAt == 0;
    }

    public boolean isAiExtracted() {
        return AI_SCAN_EXTRACTED.equals(aiScanStatus);
    }

    public double totalForSummaries() {
        return total == null ? 0 : total;
    }

    private static String cleanAiScanStatus(String status) {
        if (AI_SCAN_EXTRACTED.equals(status) || AI_SCAN_FAILED.equals(status)) {
            return status;
        }
        return AI_SCAN_MANUAL;
    }
}
