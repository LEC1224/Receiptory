package se.lecani.Receiptory;

import org.json.JSONObject;

public class EntitlementState {
    public final int remainingScans;
    public final int purchasedScans;
    public final int usedScans;

    public EntitlementState(int remainingScans, int purchasedScans, int usedScans) {
        this.remainingScans = Math.max(0, remainingScans);
        this.purchasedScans = Math.max(0, purchasedScans);
        this.usedScans = Math.max(0, usedScans);
    }

    public static EntitlementState empty() {
        return new EntitlementState(0, 0, 0);
    }

    public static EntitlementState fromJson(JSONObject json) {
        if (json == null) {
            return empty();
        }
        return new EntitlementState(
                json.optInt("remaining_scans", 0),
                json.optInt("purchased_scans", 0),
                json.optInt("used_scans", 0)
        );
    }
}
