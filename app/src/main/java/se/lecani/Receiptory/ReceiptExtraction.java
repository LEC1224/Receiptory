package se.lecani.Receiptory;

import java.util.List;

final class ReceiptExtraction {
    public final String merchant;
    public final String date;
    public final Double total;
    public final String rawText;
    public final List<ReceiptItem> items;
    public final boolean itemsNeedReview;

    public ReceiptExtraction(
            String merchant,
            String date,
            Double total,
            String rawText,
            List<ReceiptItem> items,
            boolean itemsNeedReview
    ) {
        this.merchant = merchant;
        this.date = date;
        this.total = total;
        this.rawText = rawText;
        this.items = items;
        this.itemsNeedReview = itemsNeedReview;
    }
}
