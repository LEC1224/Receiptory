package se.kvittordning.app;

public class AiScanPack {
    public static final AiScanPack[] PACKS = {
            new AiScanPack("ai_scans_100", 100, "$2"),
            new AiScanPack("ai_scans_500", 500, "$5"),
            new AiScanPack("ai_scans_2000", 2000, "$10"),
    };

    public final String productId;
    public final int scans;
    public final String fallbackPrice;

    public AiScanPack(String productId, int scans, String fallbackPrice) {
        this.productId = productId;
        this.scans = scans;
        this.fallbackPrice = fallbackPrice;
    }

    public static AiScanPack find(String productId) {
        for (AiScanPack pack : PACKS) {
            if (pack.productId.equals(productId)) {
                return pack;
            }
        }
        return null;
    }
}
