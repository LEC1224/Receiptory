package se.lecani.Receiptory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class ReceiptParserTest {
    @Test
    public void swedishGroceryWithQuantityDiscountDepositAndTender() {
        ReceiptExtraction receipt = parse(Locale.US,
                "KASSAKVITTO",
                "Willys Nacka",
                "Stationsgatan 4",
                "Org.nr 556000-1234",
                "Datum 12/08/2026 17:45",
                "Vara\tPris",
                "Ekologisk mjölk 1 x 15,95\t15,95 A",
                "Bananer",
                "0,540 kg x 24,90\t13,45",
                "Pant\t2,00",
                "Medlemsrabatt\t5,00-",
                "ATT BETALA\t26,40 SEK",
                "Kontant\t100,00",
                "Växel\t73,60",
                "Moms 12,00%\t2,83");
        assertEquals("Willys Nacka", receipt.merchant);
        assertEquals("2026-08-12", receipt.date);
        assertEquals(26.40, receipt.total, 0.001);
        assertItems(receipt, new String[]{"Ekologisk mjölk", "Bananer", "Pant", "Medlemsrabatt"},
                15.95, 13.45, 2.00, -5.00);
        assertFalse(receipt.itemsNeedReview);
        assertTrue(receipt.rawText.contains("100,00"));
    }

    @Test
    public void usRestaurantWithSeparatePricesAndTax() {
        ReceiptExtraction receipt = parse(Locale.US,
                "Oak Street Cafe",
                "234 Oak St",
                "12/08/2026 10:35 AM",
                "Item\tQty\tAmount",
                "Latte\t2\t$4.00\t$8.00",
                "Sandwich\t$10.00",
                "Subtotal\t$18.00",
                "Sales Tax\t$1.53",
                "TOTAL\t$19.53",
                "VISA\t$19.53");
        assertEquals("Oak Street Cafe", receipt.merchant);
        assertEquals("2026-12-08", receipt.date);
        assertEquals(19.53, receipt.total, 0.001);
        assertItems(receipt, new String[]{"Latte", "Sandwich"}, 8.00, 10.00);
        assertFalse(receipt.itemsNeedReview);
    }

    @Test
    public void europeanAmountsAndTotalOnNextRow() {
        ReceiptExtraction receipt = parse(Locale.US,
                "BUTIK: Fotoateljén",
                "2026-10-07",
                "Stor print\t1 249,50",
                "ATT BETALA:",
                "\t1.249,50 EUR");
        assertEquals("Fotoateljén", receipt.merchant);
        assertEquals("2026-10-07", receipt.date);
        assertEquals(1249.50, receipt.total, 0.001);
        assertItems(receipt, new String[]{"Stor print"}, 1249.50);
        assertFalse(receipt.itemsNeedReview);
    }

    @Test
    public void printedAmountDueWinsOverUnroundedTotal() {
        ReceiptExtraction receipt = parse(Locale.US,
                "ICA Nära Dalby",
                "Datum 26-10-11",
                "Kaffe\t39,95",
                "SUMMA\t39,95",
                "Öresavrundning\t0,05",
                "ATT BETALA\t40:-",
                "Kontant\t100");
        assertEquals("2026-10-11", receipt.date);
        assertEquals(40.00, receipt.total, 0.001);
        assertItems(receipt, new String[]{"Kaffe"}, 39.95);
        assertFalse(receipt.itemsNeedReview);
    }

    @Test
    public void partialReceiptDoesNotGuessTotalMerchantOrDate() {
        ReceiptExtraction receipt = parse(Locale.US,
                "Kvitto",
                "Datum 2025-02-29",
                "Yoghurt\t12,50",
                "Moms 12%\t1,34",
                "Kontant\t50,00",
                "Växel\t37,50");
        assertEquals("", receipt.merchant);
        assertEquals("", receipt.date);
        assertNull(receipt.total);
        assertItems(receipt, new String[]{"Yoghurt"}, 12.50);
        assertTrue(receipt.itemsNeedReview);
    }

    @Test
    public void conflictingTotalsAndDatesAreNotGuessed() {
        ReceiptExtraction receipt = parse(Locale.US,
                "Cedar Market",
                "Date 2025-04-12",
                "Date 2025-04-13",
                "Batteries\t7.00",
                "TOTAL\t7.00",
                "TOTAL\t10.00");
        assertEquals("", receipt.date);
        assertNull(receipt.total);
        assertTrue(receipt.itemsNeedReview);
    }

    @Test
    public void mismatchedItemsKeepPrintedTotalAndFlagReview() {
        ReceiptExtraction receipt = parse(Locale.UK,
                "Coffee House",
                "11 Nov 2026",
                "Large coffee\t£4.50",
                "Amount paid\t£6.00",
                "Returns accepted until 30 Nov 2026");
        assertEquals("2026-11-11", receipt.date);
        assertEquals(6.00, receipt.total, 0.001);
        assertTrue(receipt.itemsNeedReview);
    }

    @Test
    public void negativeReturnsAndZeroAreLegitimateAmounts() {
        ReceiptExtraction refund = parse(Locale.US,
                "Cedar Market", "Nov 11, 2026", "Shirt\t(23.99)", "TOTAL\t-$23.99");
        assertEquals(-23.99, refund.total, 0.001);
        assertItems(refund, new String[]{"Shirt"}, -23.99);
        assertFalse(refund.itemsNeedReview);
        ReceiptExtraction zero = parse(Locale.US,
                "Cedar Market", "2026-11-11", "Sample\t0.00", "TOTAL\t0");
        assertEquals(0.0, zero.total, 0.001);
        assertFalse(zero.itemsNeedReview);
    }

    @Test
    public void indentedWrappedNameIsJoinedToItsPriceRow() {
        ReceiptExtraction receipt = parse(Locale.US,
                "Spring Market",
                "2026-11-11",
                "Stone ground",
                "  mustard\t2.99",
                "TOTAL\t2.99");
        assertItems(receipt, new String[]{"Stone ground mustard"}, 2.99);
        assertFalse(receipt.itemsNeedReview);
    }

    @Test
    public void categorySuggestionsUseMerchantAndItems() {
        assertEquals("Supermarket", ReceiptCategorizer.suggest("ICA Kvantum Västerås",
                Collections.singletonList(new ReceiptItem("pizza", 30))));
        assertEquals("Health", ReceiptCategorizer.suggest("Apotek Hjärtat", Collections.emptyList()));
        assertEquals("Other", ReceiptCategorizer.suggest("North Corner", Collections.emptyList()));
    }

    private static ReceiptExtraction parse(Locale locale, String... rows) {
        List<ReceiptLayout.Word> words = new ArrayList<>();
        for (int row = 0; row < rows.length; row++) {
            String[] columns = rows[row].split("\\t", -1);
            for (int column = 0; column < columns.length; column++) {
                int x = 20 + column * 240;
                for (String word : columns[column].split(" ", -1)) {
                    int width = word.length() * 8;
                    if (!word.isEmpty()) {
                        words.add(new ReceiptLayout.Word(word, x, row * 28 + column * 2,
                                x + width, row * 28 + column * 2 + 18));
                    }
                    x += width + (word.isEmpty() ? 10 : 6);
                }
            }
        }
        // OCR blocks don't necessarily arrive in reading order.
        Collections.reverse(words);
        return ReceiptParser.parse(words, locale);
    }

    private static void assertItems(ReceiptExtraction receipt, String[] names, double... amounts) {
        assertEquals("Items in " + receipt.rawText, names.length, receipt.items.size());
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], receipt.items.get(i).name);
            assertEquals(amounts[i], receipt.items.get(i).cost, 0.001);
        }
    }
}
