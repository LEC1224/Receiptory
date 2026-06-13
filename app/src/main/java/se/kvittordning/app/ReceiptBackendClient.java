package se.kvittordning.app;

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
            boolean allowNewCategories
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

        byte[] body = buildRequest(imageFile, categories, allowNewCategories)
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

    private JSONObject buildRequest(
            File imageFile,
            List<Category> categories,
            boolean allowNewCategories
    ) throws IOException, JSONException {
        JSONObject root = new JSONObject();
        root.put("image_base64", encodeFile(imageFile));
        root.put("allow_new_categories", allowNewCategories);

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

    private String extractUrl(String backendUrl) {
        String cleanUrl = backendUrl.trim();
        while (cleanUrl.endsWith("/")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
        }
        return cleanUrl + "/extract";
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
