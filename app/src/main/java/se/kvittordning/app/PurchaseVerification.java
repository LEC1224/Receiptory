package se.kvittordning.app;

import org.json.JSONObject;

public class PurchaseVerification {
    public final boolean ok;
    public final boolean alreadyGranted;
    public final int grantedScans;
    public final EntitlementState entitlementState;

    public PurchaseVerification(
            boolean ok,
            boolean alreadyGranted,
            int grantedScans,
            EntitlementState entitlementState
    ) {
        this.ok = ok;
        this.alreadyGranted = alreadyGranted;
        this.grantedScans = grantedScans;
        this.entitlementState = entitlementState == null ? EntitlementState.empty() : entitlementState;
    }

    public static PurchaseVerification fromJson(JSONObject json) {
        return new PurchaseVerification(
                json.optBoolean("ok", false),
                json.optBoolean("already_granted", false),
                json.optInt("granted_scans", 0),
                EntitlementState.fromJson(json.optJSONObject("entitlement"))
        );
    }
}
