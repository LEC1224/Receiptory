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

public class OpenAiReceiptExtractor {
    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";

    public ReceiptExtraction extract(
            File imageFile,
            List<Category> categories,
            String apiKey,
            String model,
            boolean allowNewCategories
    )
            throws IOException, JSONException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IOException("Missing OpenAI API key.");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(90000);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        connection.setRequestProperty("Content-Type", "application/json");

        byte[] body = buildRequest(imageFile, categories, model, allowNewCategories)
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
            throw new IOException("OpenAI request failed: " + response);
        }

        String outputText = extractOutputText(new JSONObject(response));
        return ReceiptExtraction.fromJson(new JSONObject(outputText));
    }

    private JSONObject buildRequest(
            File imageFile,
            List<Category> categories,
            String model,
            boolean allowNewCategories
    ) throws IOException, JSONException {
        JSONObject root = new JSONObject();
        root.put("model", model == null || model.trim().isEmpty() ? SettingsStore.DEFAULT_OPENAI_MODEL : model.trim());
        root.put("instructions", buildInstructions(categories, allowNewCategories));
        root.put("input", buildInput(imageFile));
        root.put("text", buildTextFormat());
        return root;
    }

    private JSONArray buildInput(File imageFile) throws IOException, JSONException {
        JSONArray input = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");

        JSONArray content = new JSONArray();
        JSONObject text = new JSONObject();
        text.put("type", "input_text");
        text.put("text", "Extract this receipt, categorize it, and return only data matching the schema.");
        content.put(text);

        JSONObject image = new JSONObject();
        image.put("type", "input_image");
        image.put("image_url", "data:image/jpeg;base64," + encodeFile(imageFile));
        content.put(image);

        message.put("content", content);
        input.put(message);
        return input;
    }

    private JSONObject buildTextFormat() throws JSONException {
        JSONObject text = new JSONObject();
        JSONObject format = new JSONObject();
        format.put("type", "json_schema");
        format.put("name", "receipt_extraction");
        format.put("strict", true);

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", new JSONArray()
                .put("merchant")
                .put("date")
                .put("total")
                .put("raw_text")
                .put("items")
                .put("category_decision"));

        JSONObject properties = new JSONObject();
        properties.put("merchant", stringSchema());
        properties.put("date", stringSchema("Receipt date in YYYY-MM-DD format. If unknown, use today's best estimate from the receipt context."));
        properties.put("total", numberSchema());
        properties.put("raw_text", stringSchema("Readable text transcribed from the receipt."));
        properties.put("items", itemsSchema());
        properties.put("category_decision", categoryDecisionSchema());
        schema.put("properties", properties);

        format.put("schema", schema);
        text.put("format", format);
        return text;
    }

    private JSONObject itemsSchema() throws JSONException {
        JSONObject item = new JSONObject();
        item.put("type", "object");
        item.put("additionalProperties", false);
        item.put("required", new JSONArray().put("name").put("cost"));

        JSONObject itemProperties = new JSONObject();
        itemProperties.put("name", stringSchema());
        itemProperties.put("cost", numberSchema());
        item.put("properties", itemProperties);

        JSONObject items = new JSONObject();
        items.put("type", "array");
        items.put("items", item);
        return items;
    }

    private JSONObject categoryDecisionSchema() throws JSONException {
        JSONObject decision = new JSONObject();
        decision.put("type", "object");
        decision.put("additionalProperties", false);
        decision.put("required", new JSONArray()
                .put("action")
                .put("existing_category_id")
                .put("new_category_name"));

        JSONObject properties = new JSONObject();
        JSONObject action = new JSONObject();
        action.put("type", "string");
        action.put("enum", new JSONArray().put("use_existing").put("create_new"));
        properties.put("action", action);
        properties.put("existing_category_id", stringSchema("When action is use_existing, this must be one of the supplied category ids. Otherwise empty string."));
        properties.put("new_category_name", stringSchema("When action is create_new, this is the proposed category name. Otherwise empty string."));
        decision.put("properties", properties);
        return decision;
    }

    private JSONObject stringSchema() throws JSONException {
        return stringSchema("");
    }

    private JSONObject stringSchema(String description) throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("type", "string");
        if (!description.isEmpty()) {
            schema.put("description", description);
        }
        return schema;
    }

    private JSONObject numberSchema() throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("type", "number");
        return schema;
    }

    private String buildInstructions(List<Category> categories, boolean allowNewCategories) {
        StringBuilder builder = new StringBuilder();
        builder.append("You extract purchase data from receipt photos for an expense manager. ");
        builder.append("Return the receipt merchant, ISO date, item rows, total, and raw transcribed text. ");
        if (allowNewCategories) {
            builder.append("Choose the best existing category if one fits. ");
            builder.append("If none fit, set category_decision.action to create_new, leave existing_category_id empty, ");
            builder.append("and provide a concise reusable category name in new_category_name. ");
            builder.append("If using an existing category, set action to use_existing, provide its id, and leave new_category_name empty. ");
        } else {
            builder.append("You MUST place this receipt into one of the existing categories provided below. ");
            builder.append("Do not create or propose a new category under any circumstances. ");
            builder.append("Always set category_decision.action to use_existing, set existing_category_id to exactly one supplied category id, ");
            builder.append("and set new_category_name to an empty string. ");
        }
        builder.append("Existing categories:\n");
        for (Category category : categories) {
            builder.append("- id: ").append(category.id).append(", name: ").append(category.name).append('\n');
        }
        return builder.toString();
    }

    private String extractOutputText(JSONObject response) {
        JSONArray output = response.optJSONArray("output");
        if (output == null) {
            return "{}";
        }
        for (int index = 0; index < output.length(); index++) {
            JSONObject item = output.optJSONObject(index);
            if (item == null || !"message".equals(item.optString("type"))) {
                continue;
            }
            JSONArray content = item.optJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int contentIndex = 0; contentIndex < content.length(); contentIndex++) {
                JSONObject contentItem = content.optJSONObject(contentIndex);
                if (contentItem != null && "output_text".equals(contentItem.optString("type"))) {
                    return contentItem.optString("text", "{}");
                }
            }
        }
        return "{}";
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
