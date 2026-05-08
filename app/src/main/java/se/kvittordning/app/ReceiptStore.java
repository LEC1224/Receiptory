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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ReceiptStore {
    private static final String DATABASE_FILE = "receipts.json";
    private static final String BACKUP_DATABASE_ENTRY = "receipts.json";
    private static final String BACKUP_PHOTO_DIRECTORY = "photos/";

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
        List<Category> visible = new ArrayList<>();
        for (Category category : categories) {
            if (!category.deleted) {
                visible.add(category);
            }
        }
        return visible;
    }

    public List<Receipt> getReceipts() {
        return getReceipts(false);
    }

    public List<Receipt> getReceipts(boolean includeArchived) {
        List<Receipt> sorted = new ArrayList<>(receipts);
        if (!includeArchived) {
            sorted.removeIf(receipt -> receipt.archived);
        }
        sorted.sort((left, right) -> Long.compare(right.createdAt, left.createdAt));
        return sorted;
    }

    public List<Receipt> getArchivedReceipts() {
        List<Receipt> archived = new ArrayList<>();
        for (Receipt receipt : receipts) {
            if (receipt.archived) {
                archived.add(receipt);
            }
        }
        archived.sort((left, right) -> Long.compare(right.createdAt, left.createdAt));
        return archived;
    }

    public List<Receipt> getReceiptsForCategory(String categoryId) {
        return getReceiptsForCategory(categoryId, false);
    }

    public List<Receipt> getReceiptsForCategory(String categoryId, boolean includeArchived) {
        List<Receipt> filtered = new ArrayList<>();
        for (Receipt receipt : receipts) {
            if (receipt.categoryId.equals(categoryId) && (includeArchived || !receipt.archived)) {
                filtered.add(receipt);
            }
        }
        filtered.sort((left, right) -> Long.compare(right.createdAt, left.createdAt));
        return filtered;
    }

    public Category getCategory(String categoryId) {
        for (Category category : categories) {
            if (category.id.equals(categoryId) && !category.deleted) {
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
        return addCategory(name, icon, false);
    }

    public Category addAiSuggestedCategory(String name) {
        return addCategory(name, chooseIconForName(name), true);
    }

    private Category addCategory(String name, String icon, boolean aiSuggested) {
        Category existing = findCategoryByName(name);
        if (existing != null) {
            if (existing.deleted) {
                existing.deleted = false;
                existing.icon = cleanIcon(icon);
                existing.aiSuggested = aiSuggested;
                save();
            }
            return existing;
        }

        Category category = new Category(createCategoryId(name), name.trim(), cleanIcon(icon));
        category.aiSuggested = aiSuggested;
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

    public boolean deleteEmptyAiSuggestedCategory(String categoryId) {
        Category category = getCategory(categoryId);
        if (category == null || !category.aiSuggested) {
            return false;
        }
        if (!getReceiptsForCategory(categoryId, true).isEmpty()) {
            return false;
        }
        category.deleted = true;
        save();
        return true;
    }

    public void setReceiptArchived(String receiptId, boolean archived) {
        Receipt receipt = getReceipt(receiptId);
        if (receipt != null) {
            receipt.archived = archived;
            save();
        }
    }

    public boolean deleteReceipt(String receiptId) {
        for (int index = 0; index < receipts.size(); index++) {
            Receipt receipt = receipts.get(index);
            if (receipt.id.equals(receiptId)) {
                receipts.remove(index);
                deletePhoto(receipt);
                save();
                return true;
            }
        }
        return false;
    }

    public boolean deleteCategory(String categoryId) {
        Category category = getCategory(categoryId);
        if (category == null) {
            return false;
        }
        category.deleted = true;
        List<Receipt> deletedReceipts = new ArrayList<>();
        for (Receipt receipt : receipts) {
            if (receipt.categoryId.equals(categoryId)) {
                deletedReceipts.add(receipt);
            }
        }
        receipts.removeAll(deletedReceipts);
        for (Receipt receipt : deletedReceipts) {
            deletePhoto(receipt);
        }
        save();
        return true;
    }

    public double totalForCategory(String categoryId, DateFilter filter) {
        return totalForCategory(categoryId, filter, false);
    }

    public double totalForCategory(String categoryId, DateFilter filter, boolean includeArchived) {
        double total = 0;
        for (Receipt receipt : receipts) {
            if (receipt.categoryId.equals(categoryId)
                    && (includeArchived || !receipt.archived)
                    && filter.matches(receipt.date)) {
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

    public void exportBackup(OutputStream target) throws IOException, JSONException {
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            zip.putNextEntry(new ZipEntry(BACKUP_DATABASE_ENTRY));
            if (databaseFile.exists()) {
                try (FileInputStream input = new FileInputStream(databaseFile)) {
                    copy(input, zip);
                }
            } else {
                zip.write(databaseJson().getBytes(StandardCharsets.UTF_8));
            }
            zip.closeEntry();

            Set<String> exportedPhotos = new HashSet<>();
            for (Receipt receipt : receipts) {
                File photo = new File(receipt.photoPath);
                if (!photo.exists() || !photo.isFile()) {
                    continue;
                }
                String fileName = photo.getName();
                if (fileName.isEmpty() || exportedPhotos.contains(fileName)) {
                    continue;
                }
                exportedPhotos.add(fileName);
                zip.putNextEntry(new ZipEntry(BACKUP_PHOTO_DIRECTORY + fileName));
                try (FileInputStream input = new FileInputStream(photo)) {
                    copy(input, zip);
                }
                zip.closeEntry();
            }
        }
    }

    public RestoreResult importBackup(InputStream source, boolean replace) throws IOException, JSONException {
        File tempDirectory = new File(context.getCacheDir(), "restore_" + System.currentTimeMillis());
        if (!tempDirectory.mkdirs()) {
            throw new IOException("Could not prepare restore.");
        }

        File importedDatabase = new File(tempDirectory, BACKUP_DATABASE_ENTRY);
        File importedPhotos = new File(tempDirectory, "photos");
        if (!importedPhotos.mkdirs()) {
            deleteRecursively(tempDirectory);
            throw new IOException("Could not prepare restored photos.");
        }

        try {
            unzipBackup(source, importedDatabase, importedPhotos);
            if (!importedDatabase.exists()) {
                throw new IOException("Backup does not contain a receipt database.");
            }

            JSONObject root = new JSONObject(readFile(importedDatabase));
            List<Category> importedCategories = readCategories(root);
            List<Receipt> importedReceipts = readReceipts(root);
            RestoreResult result = replace
                    ? replaceData(importedCategories, importedReceipts, importedPhotos)
                    : mergeData(importedCategories, importedReceipts, importedPhotos);
            save();
            ensureDefaultCategories();
            return result;
        } finally {
            deleteRecursively(tempDirectory);
        }
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
                if (existing != null && !existing.deleted && (existing.icon == null || existing.icon.trim().isEmpty())) {
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
            try (FileOutputStream output = new FileOutputStream(databaseFile)) {
                output.write(databaseJson().getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException | IOException ignored) {
        }
    }

    private String databaseJson() throws JSONException {
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
        return root.toString(2);
    }

    private RestoreResult replaceData(List<Category> importedCategories, List<Receipt> importedReceipts, File importedPhotos) throws IOException {
        receipts.clear();
        categories.clear();
        deleteRecursively(photoDirectory);
        if (!photoDirectory.exists() && !photoDirectory.mkdirs()) {
            throw new IOException("Could not prepare receipt photos.");
        }

        for (Category category : importedCategories) {
            categories.add(new Category(category.id, category.name, category.icon, category.deleted, category.aiSuggested));
        }

        int addedReceipts = 0;
        for (Receipt receipt : importedReceipts) {
            receipts.add(copyImportedReceipt(receipt, importedPhotos, receipt.categoryId));
            addedReceipts++;
        }
        return new RestoreResult(addedReceipts, importedCategories.size(), 0);
    }

    private RestoreResult mergeData(List<Category> importedCategories, List<Receipt> importedReceipts, File importedPhotos) throws IOException {
        Map<String, String> categoryIds = new HashMap<>();
        int addedCategories = 0;
        for (Category importedCategory : importedCategories) {
            Category existing = findCategoryByName(importedCategory.name);
            if (existing != null) {
                if (existing.deleted && !importedCategory.deleted) {
                    existing.deleted = false;
                    existing.icon = cleanIcon(importedCategory.icon);
                }
                categoryIds.put(importedCategory.id, existing.id);
                continue;
            }

            String categoryId = importedCategory.id;
            if (getCategory(categoryId) != null || categoryId == null || categoryId.trim().isEmpty()) {
                categoryId = createCategoryId(importedCategory.name);
            }
            categories.add(new Category(
                    categoryId,
                    importedCategory.name,
                    cleanIcon(importedCategory.icon),
                    importedCategory.deleted,
                    importedCategory.aiSuggested
            ));
            categoryIds.put(importedCategory.id, categoryId);
            addedCategories++;
        }

        Set<String> existingIds = new HashSet<>();
        Set<String> existingSignatures = new HashSet<>();
        for (Receipt receipt : receipts) {
            existingIds.add(receipt.id);
            existingSignatures.add(receiptSignature(receipt));
        }

        int addedReceipts = 0;
        int skippedReceipts = 0;
        for (Receipt importedReceipt : importedReceipts) {
            String signature = receiptSignature(importedReceipt);
            if (existingIds.contains(importedReceipt.id) || existingSignatures.contains(signature)) {
                skippedReceipts++;
                continue;
            }

            String categoryId = categoryIds.get(importedReceipt.categoryId);
            if (categoryId == null || getCategory(categoryId) == null) {
                Category other = findCategoryByName("Other");
                categoryId = other == null ? addCategory("Other").id : other.id;
            }
            Receipt copied = copyImportedReceipt(importedReceipt, importedPhotos, categoryId);
            receipts.add(copied);
            existingIds.add(copied.id);
            existingSignatures.add(signature);
            addedReceipts++;
        }
        return new RestoreResult(addedReceipts, addedCategories, skippedReceipts);
    }

    private Receipt copyImportedReceipt(Receipt receipt, File importedPhotos, String categoryId) throws IOException {
        String photoPath = receipt.photoPath;
        File sourcePhoto = new File(importedPhotos, new File(receipt.photoPath).getName());
        if (sourcePhoto.exists() && sourcePhoto.isFile()) {
            File destination = uniquePhotoFile(sourcePhoto.getName());
            try (FileInputStream input = new FileInputStream(sourcePhoto);
                 FileOutputStream output = new FileOutputStream(destination)) {
                copy(input, output);
            }
            photoPath = destination.getAbsolutePath();
        }

        return new Receipt(
                receipt.id == null || receipt.id.trim().isEmpty() ? UUID.randomUUID().toString() : receipt.id,
                categoryId,
                receipt.merchant,
                receipt.date,
                receipt.total,
                photoPath,
                new ArrayList<>(receipt.items),
                receipt.rawText,
                receipt.createdAt,
                receipt.archived
        );
    }

    private void deletePhoto(Receipt receipt) {
        if (receipt == null || receipt.photoPath == null || receipt.photoPath.trim().isEmpty()) {
            return;
        }
        File photo = new File(receipt.photoPath);
        if (photo.exists() && photo.isFile()) {
            photo.delete();
        }
    }

    private File uniquePhotoFile(String fileName) {
        String cleanName = fileName == null ? "" : fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (cleanName.trim().isEmpty()) {
            cleanName = "receipt_" + System.currentTimeMillis() + ".jpg";
        }
        File destination = new File(photoDirectory, cleanName);
        if (!destination.exists()) {
            return destination;
        }

        String base = cleanName;
        String extension = "";
        int dot = cleanName.lastIndexOf('.');
        if (dot > 0) {
            base = cleanName.substring(0, dot);
            extension = cleanName.substring(dot);
        }
        int counter = 1;
        do {
            destination = new File(photoDirectory, base + "_" + counter + extension);
            counter++;
        } while (destination.exists());
        return destination;
    }

    private void unzipBackup(InputStream source, File importedDatabase, File importedPhotos) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }

                if (BACKUP_DATABASE_ENTRY.equals(name)) {
                    try (FileOutputStream output = new FileOutputStream(importedDatabase)) {
                        copy(zip, output);
                    }
                } else if (name.startsWith(BACKUP_PHOTO_DIRECTORY)) {
                    String fileName = new File(name).getName();
                    if (!fileName.trim().isEmpty()) {
                        File outputFile = new File(importedPhotos, fileName.replaceAll("[\\\\/:*?\"<>|]", "_"));
                        try (FileOutputStream output = new FileOutputStream(outputFile)) {
                            copy(zip, output);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private List<Category> readCategories(JSONObject root) {
        List<Category> importedCategories = new ArrayList<>();
        JSONArray categoryArray = root.optJSONArray("categories");
        if (categoryArray == null) {
            return importedCategories;
        }
        for (int index = 0; index < categoryArray.length(); index++) {
            JSONObject json = categoryArray.optJSONObject(index);
            if (json != null) {
                importedCategories.add(Category.fromJson(json));
            }
        }
        return importedCategories;
    }

    private List<Receipt> readReceipts(JSONObject root) {
        List<Receipt> importedReceipts = new ArrayList<>();
        JSONArray receiptArray = root.optJSONArray("receipts");
        if (receiptArray == null) {
            return importedReceipts;
        }
        for (int index = 0; index < receiptArray.length(); index++) {
            JSONObject json = receiptArray.optJSONObject(index);
            if (json != null) {
                importedReceipts.add(Receipt.fromJson(json));
            }
        }
        return importedReceipts;
    }

    private static String receiptSignature(Receipt receipt) {
        return normalize(receipt.merchant)
                + "|" + normalize(receipt.date)
                + "|" + String.format(Locale.US, "%.2f", receipt.total)
                + "|" + normalize(receipt.rawText);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
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

    public static class RestoreResult {
        public final int addedReceipts;
        public final int addedCategories;
        public final int skippedReceipts;

        RestoreResult(int addedReceipts, int addedCategories, int skippedReceipts) {
            this.addedReceipts = addedReceipts;
            this.addedCategories = addedCategories;
            this.skippedReceipts = skippedReceipts;
        }
    }
}
