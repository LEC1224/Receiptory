package se.lecani.Receiptory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reassembles visual rows even when OCR puts the amount column in a separate block. */
final class ReceiptLayout {
    private ReceiptLayout() {}

    static List<Row> rows(List<Word> words) {
        if (words.isEmpty()) {
            return Collections.emptyList();
        }
        List<Word> ordered = new ArrayList<>(words);
        Collections.sort(ordered, (a, b) -> Float.compare(a.y, b.y));
        List<Float> heights = new ArrayList<>();
        for (Word word : ordered) {
            heights.add(word.height);
        }
        Collections.sort(heights);
        float height = heights.get(heights.size() / 2);

        List<Band> bands = new ArrayList<>();
        for (Word word : ordered) {
            Band nearest = null;
            float distance = Math.max(2, Math.min(height, word.height) * 0.58f);
            for (int i = bands.size() - 1; i >= 0; i--) {
                Band band = bands.get(i);
                if (word.y - band.y > height * 1.2f) {
                    break;
                }
                float delta = Math.abs(word.y - band.y);
                if (delta < distance) {
                    distance = delta;
                    nearest = band;
                }
            }
            if (nearest == null) {
                nearest = new Band(word.y);
                bands.add(nearest);
            }
            nearest.words.add(word);
            nearest.y += (word.y - nearest.y) / nearest.words.size();
        }

        Collections.sort(bands, (a, b) -> Float.compare(a.y, b.y));
        List<Row> rows = new ArrayList<>();
        for (Band band : bands) {
            Collections.sort(band.words, (a, b) -> Float.compare(a.left, b.left));
            rows.add(new Row(band, height));
        }
        return rows;
    }

    static final class Word {
        final String text;
        final float left;
        final float right;
        final float y;
        final float height;

        Word(String text, float left, float top, float right, float bottom) {
            this.text = text.replace('\u00a0', ' ').replace('\u202f', ' ').trim();
            this.left = left;
            this.right = right;
            this.y = (top + bottom) / 2;
            this.height = Math.max(1, bottom - top);
        }
    }

    private static final class Band {
        final List<Word> words = new ArrayList<>();
        float y;

        Band(float y) {
            this.y = y;
        }
    }

    static final class Row {
        final String text;
        final float left;
        final float y;
        final float height;
        private final List<Word> words;
        private final int[] offsets;

        Row(Band band, float height) {
            this.words = band.words;
            this.y = band.y;
            this.height = height;
            this.left = words.get(0).left;
            this.offsets = new int[words.size()];
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                Word word = words.get(i);
                if (i > 0) {
                    // A large gap separates columns. Keep it distinct from a space inside 1 234,56.
                    builder.append(word.left - words.get(i - 1).right > height * 0.9f ? "  " : " ");
                }
                offsets[i] = builder.length();
                builder.append(word.text);
            }
            text = builder.toString();
        }

        float rightAt(int offset) {
            for (int i = offsets.length - 1; i >= 0; i--) {
                if (offset >= offsets[i]) {
                    return words.get(i).right;
                }
            }
            return words.get(0).right;
        }
    }
}
