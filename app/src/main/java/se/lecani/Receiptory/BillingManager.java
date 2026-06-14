package se.lecani.Receiptory;

import android.app.Activity;

import androidx.annotation.NonNull;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BillingManager implements PurchasesUpdatedListener {
    private final Activity activity;
    private final Listener listener;
    private final BillingClient billingClient;
    private final Map<String, ProductDetails> productDetails = new HashMap<>();

    public BillingManager(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        billingClient = BillingClient.newBuilder(activity)
                .setListener(this)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build()
                )
                .enableAutoServiceReconnection()
                .build();
    }

    public void start() {
        if (billingClient.isReady()) {
            queryProducts();
            queryExistingPurchases();
            return;
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    queryProducts();
                    queryExistingPurchases();
                } else {
                    listener.onBillingUnavailable(billingResult.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                listener.onBillingUnavailable("Google Play billing disconnected.");
            }
        });
    }

    public void destroy() {
        if (billingClient.isReady()) {
            billingClient.endConnection();
        }
    }

    public String priceFor(AiScanPack pack) {
        ProductDetails details = productDetails.get(pack.productId);
        if (details == null || details.getOneTimePurchaseOfferDetails() == null) {
            return pack.fallbackPrice;
        }
        return details.getOneTimePurchaseOfferDetails().getFormattedPrice();
    }

    public boolean hasProductDetails(AiScanPack pack) {
        return productDetails.containsKey(pack.productId);
    }

    public void launchPurchase(AiScanPack pack) {
        ProductDetails details = productDetails.get(pack.productId);
        if (details == null) {
            listener.onBillingUnavailable("Product is not available in Google Play yet.");
            return;
        }
        BillingFlowParams.ProductDetailsParams productParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build();
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(java.util.Collections.singletonList(productParams))
                .build();
        BillingResult result = billingClient.launchBillingFlow(activity, flowParams);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            listener.onBillingUnavailable(result.getDebugMessage());
        }
    }

    private void queryProducts() {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (AiScanPack pack : AiScanPack.PACKS) {
            products.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(pack.productId)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build());
        }
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build();
        billingClient.queryProductDetailsAsync(params, this::onProductDetailsResponse);
    }

    private void onProductDetailsResponse(
            BillingResult billingResult,
            QueryProductDetailsResult queryProductDetailsResult
    ) {
        if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            listener.onBillingUnavailable(billingResult.getDebugMessage());
            return;
        }
        productDetails.clear();
        for (ProductDetails details : queryProductDetailsResult.getProductDetailsList()) {
            productDetails.put(details.getProductId(), details);
        }
        listener.onProductsUpdated();
    }

    private void queryExistingPurchases() {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases);
            }
        });
    }

    @Override
    public void onPurchasesUpdated(
            @NonNull BillingResult billingResult,
            List<Purchase> purchases
    ) {
        int responseCode = billingResult.getResponseCode();
        if (responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases);
        } else if (responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            listener.onBillingUnavailable(billingResult.getDebugMessage());
        }
    }

    private void handlePurchases(List<Purchase> purchases) {
        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                listener.onPurchaseReadyForVerification(purchase);
            }
        }
    }

    public interface Listener {
        void onProductsUpdated();

        void onPurchaseReadyForVerification(Purchase purchase);

        void onBillingUnavailable(String message);
    }
}
