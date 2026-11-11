package se.lecani.Receiptory;

import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class LocalReceiptScannerTest {
    @Rule public TemporaryFolder sandbox = new TemporaryFolder(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Test
    public void bundledScannerReadsRotatedReceiptWithoutInternetPermission() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(PackageManager.PERMISSION_DENIED, app.checkSelfPermission(Manifest.permission.INTERNET));

        File photo = receiptPhoto(app, true,
                "WILLYS KISTA",
                "KASSAKVITTO",
                "Datum 2026-08-12 13:05",
                "VARA\tPRIS",
                "HAVREMJOLK\t21,90",
                "KAFFE\t34,10",
                "ATT BETALA\t56,00 SEK",
                "KONTANT\t100,00",
                "VAXEL\t44,00");
        try (LocalReceiptScanner scanner = new LocalReceiptScanner()) {
            ReceiptExtraction receipt = scanner.scan(photo, Locale.US);
            assertEquals(receipt.rawText, "WILLYS KISTA", receipt.merchant);
            assertEquals(receipt.rawText, "2026-08-12", receipt.date);
            assertEquals(receipt.rawText, 56.00, receipt.total, 0.001);
            assertEquals(receipt.rawText, 2, receipt.items.size());
            assertEquals("HAVREMJOLK", receipt.items.get(0).name);
            assertEquals(21.90, receipt.items.get(0).cost, 0.001);
            assertEquals(34.10, receipt.items.get(1).cost, 0.001);
            assertFalse(receipt.rawText, receipt.itemsNeedReview);
        } finally {
            photo.delete();
        }
    }

    @Test
    public void missingTotalRemainsBlankInStorage() throws Exception {
        Receipt legacy = Receipt.fromJson(new JSONObject()
                .put("merchant", "Corner Store").put("total", 0)
                .put("aiScanStatus", "extracted"));
        assertEquals(0.0, legacy.total, 0.001);
        assertEquals(0.0, Receipt.fromJson(legacy.toJson()).total, 0.001);

        Receipt partial = Receipt.fromJson(new JSONObject().put("itemsNeedReview", true));
        Receipt restored = Receipt.fromJson(partial.toJson());
        assertNull(restored.total);
        assertTrue(restored.itemsNeedReview);
        assertEquals(0.0, restored.totalForSummaries(), 0.001);
    }

    @Test
    public void backupMergeKeepsDifferentUnscannedPhotosAndDeduplicatesById() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File files = sandbox.newFolder("files");
        File cache = sandbox.newFolder("cache");
        Context isolated = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public File getCacheDir() { return cache; }
        };
        JSONArray receipts = new JSONArray()
                .put(new JSONObject().put("id", "one").put("total", 0).put("photoPath", "one.jpg"))
                .put(new JSONObject().put("id", "two").put("total", 0).put("photoPath", "two.jpg"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("receipts.json"));
            zip.write(new JSONObject().put("receipts", receipts).toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String filename : new String[]{"one.jpg", "two.jpg"}) {
                zip.putNextEntry(new ZipEntry("photos/" + filename));
                zip.write(filename.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        ReceiptStore store = new ReceiptStore(isolated);
        assertEquals(2, store.importBackup(new ByteArrayInputStream(bytes.toByteArray()), false).addedReceipts);
        assertEquals(2, store.getUnscannedReceipts().size());
        assertEquals(2, store.importBackup(new ByteArrayInputStream(bytes.toByteArray()), false).skippedReceipts);
    }

    @Test
    public void unreadableImageReportsFailureRatherThanFabricatingData() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File photo = receiptPhoto(app, false);
        try (LocalReceiptScanner scanner = new LocalReceiptScanner()) {
            try {
                scanner.scan(photo, Locale.US);
                fail("A blank photo should fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("No readable text"));
            }
        } finally {
            photo.delete();
        }
    }

    static File receiptPhoto(Context app, boolean rotate, String... lines) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(1100, 160 + lines.length * 76, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.MONOSPACE);
        for (int i = 0; i < lines.length; i++) {
            paint.setTextSize(i == 0 ? 55 : 44);
            String[] fields = lines[i].split("\\t", -1);
            canvas.drawText(fields[0], 65, 100 + i * 76, paint);
            if (fields.length > 1) {
                canvas.drawText(fields[1], 1035 - paint.measureText(fields[1]), 100 + i * 76, paint);
            }
        }
        if (rotate) {
            Matrix matrix = new Matrix();
            matrix.setRotate(-90);
            Bitmap sideways = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            bitmap = sideways;
        }
        File file = File.createTempFile("receipt-test-", ".jpg", app.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 93, output));
        } finally {
            bitmap.recycle();
        }
        if (rotate) {
            ExifInterface exif = new ExifInterface(file);
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
            exif.saveAttributes();
        }
        return file;
    }
}
