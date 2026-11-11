package se.lecani.Receiptory;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;

import androidx.exifinterface.media.ExifInterface;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/** Used serially on the receipt worker, never on the UI thread. */
final class LocalReceiptScanner implements AutoCloseable {
    private TextRecognizer recognizer;

    ReceiptExtraction scan(File photo, Locale locale) throws IOException {
        Bitmap bitmap = decode(photo);
        try {
            ExifInterface exif = new ExifInterface(photo);
            if (exif.isFlipped()) {
                Matrix mirror = new Matrix();
                mirror.setScale(-1, 1);
                Bitmap flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                        bitmap.getHeight(), mirror, true);
                if (bitmap != flipped) {
                    bitmap.recycle();
                    bitmap = flipped;
                }
            }
            if (recognizer == null) {
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            }
            int rotation = exif.getRotationDegrees();
            Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotation)));
            List<ReceiptLayout.Word> words = toWords(text, rotation);
            if (words.isEmpty()) {
                throw new IOException("No readable text. Try a sharper photo or enter the receipt manually.");
            }
            return ReceiptParser.parse(words, locale);
        } catch (ExecutionException exception) {
            throw new IOException("Could not read the receipt photo. Try scanning again or enter it manually.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Scanning was interrupted.", exception);
        } finally {
            // An interrupted await does not cancel ML Kit's use of the bitmap.
            if (!Thread.currentThread().isInterrupted()) {
                bitmap.recycle();
            }
        }
    }

    private static Bitmap decode(File photo) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("The receipt photo could not be opened.");
        }

        // Leave headroom for OCR and the preview on phones with smaller heaps.
        long pixelBudget = Math.min(12_000_000L, Runtime.getRuntime().maxMemory() / 20);
        options.inSampleSize = 1;
        while ((long) (options.outWidth / options.inSampleSize)
                * (options.outHeight / options.inSampleSize) > pixelBudget
                || Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 6144) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(photo.getAbsolutePath(), options);
        if (bitmap == null) {
            throw new IOException("The receipt photo could not be opened.");
        }
        return bitmap;
    }

    private static List<ReceiptLayout.Word> toWords(Text text, int rotation) {
        List<Text.Line> lines = new ArrayList<>();
        List<Double> angles = new ArrayList<>();
        double orientation = Math.toRadians(rotation);
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                lines.add(line);
                Point[] corners = line.getCornerPoints();
                if (corners != null && corners.length == 4 && line.getElements().size() > 1) {
                    double angle = Math.atan2(corners[1].y - corners[0].y, corners[1].x - corners[0].x)
                            + orientation;
                    angle = Math.atan2(Math.sin(angle), Math.cos(angle));
                    if (Math.abs(angle) < Math.PI / 6) {
                        angles.add(angle);
                    }
                }
            }
        }
        Collections.sort(angles);
        // ML Kit reads the rotated image but reports corners in the bitmap's original frame.
        double skew = angles.isEmpty() ? 0 : angles.get(angles.size() / 2);
        double angle = skew - orientation;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);

        List<ReceiptLayout.Word> words = new ArrayList<>();
        for (Text.Line line : lines) {
            for (Text.Element element : line.getElements()) {
                if (element.getText().trim().isEmpty()) {
                    continue;
                }
                Point[] points = element.getCornerPoints();
                if (points == null || points.length != 4) {
                    Rect bounds = element.getBoundingBox();
                    if (bounds == null) {
                        continue;
                    }
                    points = new Point[]{
                            new Point(bounds.left, bounds.top), new Point(bounds.right, bounds.top),
                            new Point(bounds.right, bounds.bottom), new Point(bounds.left, bounds.bottom)
                    };
                }
                float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
                float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
                for (Point point : points) {
                    float x = point.x * cos + point.y * sin;
                    float y = point.y * cos - point.x * sin;
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
                words.add(new ReceiptLayout.Word(element.getText(), left, top, right, bottom));
            }
        }
        return words;
    }

    @Override
    public void close() {
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }
}
