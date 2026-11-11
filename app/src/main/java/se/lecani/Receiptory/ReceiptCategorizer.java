package se.lecani.Receiptory;

import java.util.List;
import java.util.regex.Pattern;

final class ReceiptCategorizer {
    private static final Pattern SUPERMARKET = words(
            "ica|maxi ica|willys|hemkop|coop|lidl|city gross|supermarket|grocery|"
                    + "walmart|kroger|whole foods|trader joe'?s|aldi|tesco|sainsbury'?s");
    private static final Pattern RESTAURANT = words(
            "restaurang|restaurant|pizzeria|cafe|bistro|diner|starbucks|mcdonald'?s|burger king|espresso house");
    private static final Pattern PREPARED_FOOD = words("pizza|espresso|latte|cappuccino|burger|taco|sushi");
    private static final Pattern TRANSPORT = words("circle k|okq8|preem|st1|shell|parking|parkering|uber|taxi");
    private static final Pattern HEALTH = words("apotek\\w*|pharmacy");
    private static final Pattern HARDWARE = words("byggmax|bauhaus|hornbach|hardware");

    private ReceiptCategorizer() {}

    static String suggest(String merchant, List<ReceiptItem> items) {
        String name = ReceiptParser.key(merchant);
        if (SUPERMARKET.matcher(name).find()) return "Supermarket";
        if (RESTAURANT.matcher(name).find()) return "Restaurant";
        if (TRANSPORT.matcher(name).find()) return "Transport";
        if (HEALTH.matcher(name).find()) return "Health";
        if (HARDWARE.matcher(name).find()) return "Hardware";
        int prepared = 0;
        for (ReceiptItem item : items) {
            if (PREPARED_FOOD.matcher(ReceiptParser.key(item.name)).find() && ++prepared >= 2) {
                return "Restaurant";
            }
        }
        return "Other";
    }

    private static Pattern words(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b");
    }
}
