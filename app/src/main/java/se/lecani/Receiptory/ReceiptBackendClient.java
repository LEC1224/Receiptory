package se.lecani.Receiptory;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ReceiptBackendClient {
    public ReceiptExtraction extract(
            File imageFile,
            List<Category> categories,
            String backendUrl,
            boolean allowNewCategories,
            String userId
    )
            throws IOException, JSONException {
        if (backendUrl == null || backendUrl.trim().isEmpty()) {
            throw new IOException("Missing AI backend URL.");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(extractUrl(backendUrl)).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(90000);
        connection.setRequestProperty("Content-Type", "application/json");

        byte[] body = buildRequest(imageFile, categories, allowNewCategories, userId)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        String response = readStream(status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        if (status < 200 || status >= 300) {
            throw new IOException("AI backend request failed: " + response);
        }

        return ReceiptExtraction.fromJson(new JSONObject(response));
    }

    public EntitlementState getEntitlements(String backendUrl, String userId) throws IOException, JSONException {
        JSONObject request = new JSONObject();
        request.put("user_id", userId);
        JSONObject response = postJson(backendUrl, "/entitlements", request);
        return EntitlementState.fromJson(response);
    }

    public PurchaseVerification verifyPurchase(
            String backendUrl,
            String userId,
            String productId,
            String purchaseToken
    ) throws IOException, JSONException {
        JSONObject request = new JSONObject();
        request.put("user_id", userId);
        request.put("product_id", productId);
        request.put("purchase_token", purchaseToken);
        JSONObject response = postJson(backendUrl, "/purchases/verify", request);
        return PurchaseVerification.fromJson(response);
    }

    private JSONObject buildRequest(
            File imageFile,
            List<Category> categories,
            boolean allowNewCategories,
            String userId
    ) throws IOException, JSONException {
        JSONObject root = new JSONObject();
        root.put("image_base64", encodeFile(imageFile));
        root.put("allow_new_categories", allowNewCategories);
        root.put("user_id", userId);

        JSONArray categoryArray = new JSONArray();
        for (Category category : categories) {
            JSONObject categoryJson = new JSONObject();
            categoryJson.put("id", category.id);
            categoryJson.put("name", category.name);
            categoryArray.put(categoryJson);
        }
        root.put("categories", categoryArray);
        return root;
    }

    private JSONObject postJson(String backendUrl, String path, JSONObject request) throws IOException, JSONException {
        if (backendUrl == null || backendUrl.trim().isEmpty()) {
            throw new IOException("Missing AI backend URL.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpointUrl(backendUrl, path)).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(90000);
        connection.setRequestProperty("Content-Type", "application/json");

        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }

        int status = connection.getResponseCode();
        String response = readStream(status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream());
        if (status < 200 || status >= 300) {
            throw new IOException("Backend request failed: " + response);
        }
        return new JSONObject(response);
    }

    private String extractUrl(String backendUrl) {
        return endpointUrl(backendUrl, "/extract");
    }

    private String endpointUrl(String backendUrl, String path) {
        String cleanUrl = backendUrl.trim();
        while (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
        }
        return cleanUrl + path;
    }

    private static String encodeFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        }
    }

    private static String readStream(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
