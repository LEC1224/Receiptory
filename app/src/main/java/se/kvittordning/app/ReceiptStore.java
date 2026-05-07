package se.kvittordning.app;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class ReceiptStore {
    private static final String DATABASE_FILE = "receipts.json";

    private final Context context;
    private final File databaseFile;
    private final File photoDirectory;
    private final List<Category> categories = new ArrayList<>();
    private final List<Receipt> receipts = new ArrayList<>();

    public ReceiptStore(Context context) {
        this.context = context.getApplicationContext();
        databaseFile = new File(this.context.getFilesDir(), DATABASE_FILE);
        photoDirectory = new File(this.context.getFilesDir(), "receipt_photos");
        if (!photoDirectory.exists()) {
            photoDirectory.mkdirs();
        }
        load();
    }

    public List<Category> getCategories() {
        return new ArrayList<>(categories);
    }

    public List<Receipt> getReceipts() {
        List<Receipt> sorted = new ArrayList<>(receipts);
        sorted.sort((left, right) -> Long.compare(right.createdAt, left.createdAt));
        return sorted;
    }

    public List<Receipt> getReceiptsForCategory(String categoryId) {
        List<Receipt> filtered = new ArrayList<>();
        for (Receipt receipt : receipts) {
            if (receipt.categoryId.equals(categoryId)) {
                filtered.add(receipt);
            }
        }
        filtered.sort((left, right) -> Long.compare(right.createdAt, left.createdAt));
        return filtered;
    }

    public Category getCategory(String categoryId) {
        for (Category category : categories) {
            if (category.id.equals(categoryId)) {
                return category;
            }
        }
        return null;
    }

    public Receipt getReceipt(String receiptId) {
        for (Receipt receipt : receipts) {
            if (receipt.id.equals(receiptId)) {
                return receipt;
            }
        }
        return null;
    }

    public Category addCategory(String name) {
        return addCategory(name, chooseIconForName(name));
    }

    public Category addCategory(String name, String icon) {
        Category existing = findCategoryByName(name);
        if (existing != null) {
            return existing;
        }

        Category category = new Category(createCategoryId(name), name.trim(), cleanIcon(icon));
        categories.add(category);
        save();
        return category;
    }

    public void updateCategory(String categoryId, String name, String icon) {
        Category category = getCategory(categoryId);
        if (category != null && name != null && !name.trim().isEmpty()) {
            category.name = name.trim();
            category.icon = cleanIcon(icon);
            save();
        }
    }

    public void addReceipt(Receipt receipt) {
        receipts.add(receipt);
        save();
    }

    public void updateReceipt(Receipt updatedReceipt) {
        for (int index = 0; index < receipts.size(); index++) {
            if (receipts.get(index).id.equals(updatedReceipt.id)) {
                receipts.set(index, updatedReceipt);
                save();
                return;
            }
        }
    }

    public void moveReceipt(String receiptId, String categoryId) {
        Receipt receipt = getReceipt(receiptId);
        if (receipt != null && getCategory(categoryId) != null) {
            receipt.categoryId = categoryId;
            save();
        }
    }

    public double totalForCategory(String categoryId, DateFilter filter) {
        double total = 0;
        for (Receipt receipt : receipts) {
            if (receipt.categoryId.equals(categoryId) && filter.matches(receipt.date)) {
                total += receipt.total;
            }
        }
        return total;
    }

    public File savePhoto(Uri sourceUri) throws IOException {
        String fileName = "receipt_" + System.currentTimeMillis() + ".jpg";
        File destination = new File(photoDirectory, fileName);
        try (InputStream input = context.getContentResolver().openInputStream(sourceUri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("Could not open captured receipt photo.");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return destination;
    }

    public void ensureDefaultCategories() {
        String[][] defaults = {
                {"Supermarket", "🛒"},
                {"Restaurant", "🍽"},
                {"Other", "🧾"}
        };
        String[] oldDefaults = {
                "Hardware",
                "Electronics",
                "Transport",
                "Health",
                "Home"
        };
        boolean changed = false;
        for (String name : oldDefaults) {
            Category oldDefault = findCategoryByName(name);
            if (oldDefault != null && getReceiptsForCategory(oldDefault.id).isEmpty()) {
                categories.remove(oldDefault);
                changed = true;
            }
        }
        for (String[] categoryInfo : defaults) {
            String name = categoryInfo[0];
            if (findCategoryByName(name) == null) {
                categories.add(new Category(createCategoryId(name), name, categoryInfo[1]));
                changed = true;
            } else {
                Category existing = findCategoryByName(name);
                if (existing != null && (existing.icon == null || existing.icon.trim().isEmpty())) {
                    existing.icon = categoryInfo[1];
                    changed = true;
                }
            }
        }
        if (changed) {
            save();
        }
    }

    private void load() {
        categories.clear();
        receipts.clear();

        if (!databaseFile.exists()) {
            ensureDefaultCategories();
            return;
        }

        try {
            JSONObject root = new JSONObject(readFile(databaseFile));
            JSONArray categoryArray = root.optJSONArray("categories");
            if (categoryArray != null) {
                for (int index = 0; index < categoryArray.length(); index++) {
                    JSONObject json = categoryArray.optJSONObject(index);
                    if (json != null) {
                        categories.add(Category.fromJson(json));
                    }
                }
            }

            JSONArray receiptArray = root.optJSONArray("receipts");
            if (receiptArray != null) {
                for (int index = 0; index < receiptArray.length(); index++) {
                    JSONObject json = receiptArray.optJSONObject(index);
                    if (json != null) {
                        receipts.add(Receipt.fromJson(json));
                    }
                }
            }
        } catch (JSONException | IOException ignored) {
            categories.clear();
            receipts.clear();
        }

        ensureDefaultCategories();
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            JSONArray categoryArray = new JSONArray();
            JSONArray receiptArray = new JSONArray();

            List<Category> sortedCategories = new ArrayList<>(categories);
            Collections.sort(sortedCategories, Comparator.comparing(category -> category.name.toLowerCase(Locale.ROOT)));
            for (Category category : sortedCategories) {
                categoryArray.put(category.toJson());
            }
            for (Receipt receipt : receipts) {
                receiptArray.put(receipt.toJson());
            }

            root.put("categories", categoryArray);
            root.put("receipts", receiptArray);

            try (FileOutputStream output = new FileOutputStream(databaseFile)) {
                output.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException | IOException ignored) {
        }
    }

    private Category findCategoryByName(String name) {
        String normalized = normalize(name);
        for (Category category : categories) {
            if (normalize(category.name).equals(normalized)) {
                return category;
            }
        }
        return null;
    }

    private static String readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String createCategoryId(String name) {
        String slug = normalize(name)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            slug = "category";
        }
        return slug + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public static String chooseIconForName(String name) {
        String normalized = normalize(name);
        if (normalized.contains("supermarket") || normalized.contains("grocery") || normalized.contains("food")) return "🛒";
        if (normalized.contains("restaurant") || normalized.contains("cafe") || normalized.contains("coffee")) return "🍽";
        if (normalized.contains("hardware") || normalized.contains("tools")) return "🛠";
        if (normalized.contains("electronics") || normalized.contains("phone") || normalized.contains("computer")) return "💻";
        if (normalized.contains("transport") || normalized.contains("fuel") || normalized.contains("gas")) return "⛽";
        if (normalized.contains("health") || normalized.contains("pharmacy")) return "🏥";
        if (normalized.contains("home") || normalized.contains("furniture")) return "🏠";
        if (normalized.contains("clothes") || normalized.contains("fashion")) return "👕";
        if (normalized.contains("travel") || normalized.contains("hotel")) return "✈";
        return "🧾";
    }

    private static String cleanIcon(String icon) {
        if (icon == null || icon.trim().isEmpty()) {
            return "🧾";
        }
        return icon.trim();
    }
}
