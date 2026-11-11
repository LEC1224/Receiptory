package se.lecani.Receiptory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts printed fields; absent or conflicting fields are left for review. */
final class ReceiptParser {
    private static final String CURRENCY = "(?:SEK|USD|EUR|GBP|NOK|DKK|CAD|AUD|CHF|kr|[$€£])";
    private static final String NUMBER = "(?:\\d{1,3}(?:[., '’]\\d{3}){1,3}|\\d{1,9})";
    private static final String SIGN = "[-+−–]";
    private static final Pattern MONEY = Pattern.compile("(?<![\\p{L}\\p{N}.,/+\\-])(?:" + SIGN + " *)?"
            + "(?:" + CURRENCY + " *" + NUMBER + "(?:[.,]\\d{2})?|"
            + NUMBER + "[.,]\\d{2}(?: *" + CURRENCY + ")?|" + NUMBER + "(?: *" + CURRENCY + "|:-))"
            + "(?: *[-−](?!\\d))?(?![\\d.,/:%-])", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHOLE_AT_END = Pattern.compile("(?<![\\w.,/:])" + SIGN + "?\\d{1,9}$");
    private static final Pattern CURRENCY_TOKEN = Pattern.compile(CURRENCY, Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_TAIL = Pattern.compile("^\\s*(?:\\*|[A-Fa-f])?\\s*$");

    private static final Pattern YMD = Pattern.compile("(?<![\\d./-])(\\d{4})\\s*([./-])\\s*(\\d{1,2})"
            + "\\s*\\2\\s*(\\d{1,2})(?![\\d./-])");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<![\\d./-])(\\d{1,2})\\s*([./-])\\s*(\\d{1,2})"
            + "\\s*\\2\\s*(\\d{4}|\\d{2})(?![\\d./-])");
    private static final Pattern DAY_FIRST_MONTH = Pattern.compile("\\b(\\d{1,2})\\s+([a-z]{3,10})\\.?\\s+(\\d{4})\\b");
    private static final Pattern MONTH_FIRST_DAY = Pattern.compile("\\b([a-z]{3,10})\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})\\b");
    private static final Pattern CLOCK = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");
    private static final Pattern DATE_LABEL = Pattern.compile("\\b(?:datum|date|transaction time|kopdatum|purchase date)\\b");
    private static final Pattern NOT_PURCHASE_DATE = Pattern.compile(
            "\\b(?:expiry|expires|expiration|valid until|best fore|bast fore|giltig till|"
                    + "forfallodatum|due date|return by|returns? (?:accepted )?(?:until|before)|oppet kop)\\b");
    private static final Pattern SWEDISH = Pattern.compile(
            "\\b(?:sek|kvitto|moms|kassakvitto|att betala|org[. ]*nr|oresavrundning)\\b");
    private static final Pattern US = Pattern.compile("\\b(?:usa|united states|usd)\\b");
    private static final Pattern DAY_FIRST_CURRENCY = Pattern.compile("\\b(?:eur|gbp|nok|dkk|chf)\\b|[€£]");

    private static final Pattern EXPLICIT_MERCHANT = Pattern.compile("(?iu)^(?:merchant|store|butik)\\s*:\\s*(.+)$");
    private static final Pattern CHAIN = Pattern.compile(
            "^(?:ica(?: maxi| supermarket| nara| kvantum)?|maxi ica|willys|hemkop|coop|stora coop|lidl|"
                    + "city gross|pressbyran|systembolaget|apoteket|ikea|biltema|clas ohlson|"
                    + "walmart|target|costco|kroger|whole foods|trader joe'?s|aldi|tesco|"
                    + "sainsbury'?s|mcdonald'?s|starbucks)\\b");
    private static final Pattern NON_MERCHANT = Pattern.compile(
            "^(?:[\\d#]|(?:welcome|valkommen|welcome to|receipt|kvitto|kassakvitto|kopkvitto|"
                    + "tax invoice|invoice|faktura|copy|kopia|thank you|tack|tel|telefon|"
                    + "www|https?|org[. ]*nr|vat|moms|adress|address|customer|kund|"
                    + "terminal|card|kort|cashier|kassa|transaction|transaktion|order|date|datum)\\b)");
    private static final Pattern ADDRESS = Pattern.compile(
            "(?:\\b(?:street|road|avenue|st|rd|ave)\\.?\\s+\\d|\\b\\d+.*\\b(?:street|road|avenue|st|rd|ave)\\b|"
                    + "(?:gatan|vagen|grand|torget|allen)\\s+\\d+\\b|\\b(?:se-)?\\d{3} \\d{2}\\b|@)");

    private static final Pattern PAY_TOTAL = Pattern.compile(
            "^(?:att betala|att erlagga|belopp att betala|summa att betala|total att betala|"
                    + "amount (?:due|paid)|(?:total|balance|net) (?:due|to pay)|total paid|grand total|refund total|"
                    + "zu zahlen|a payer|net a payer|a pagar)$");
    private static final Pattern GENERIC_TOTAL = Pattern.compile(
            "^(?:tot|totalt?|summa|belopp|totalbelopp|kopbelopp|total amount|summe|gesamtbetrag|"
                    + "montant total|total ttc|importe total)(?: (?:inkl(?:usive)? moms|inc(?:l)? vat|incl tax))?$");
    private static final Pattern SUMMARY = Pattern.compile(
            "^(?:sub ?total|delsumma|nettosumma|bruttosumma|exkl(?:usive)? moms|"
                    + "moms(?:specifikation)?|vat|sales tax|(?:total )?tax|(?:total )?savings|du sparade|"
                    + "cash|kontant|change|vaxel|visa|mastercard|amex|kortkop|card payment|kortbetalning|"
                    + "paid by|payment method|tendered|betalmedel|betalsatt|payment details)\\b");
    private static final Pattern META = Pattern.compile(
            "^(?:org[. ]*nr|momsnr|vat no|vat id|tel|telefon|datum|date|time|tid|kassor|kassa|"
                    + "kvittonr|kvitto nr|receipt(?: no| number)?|terminal|ref|reference|referens|"
                    + "butiksnr|store (?:no|number)|cashier|transaction|transaktion|tack|thank you|"
                    + "customer|kund(?:nr)?|member(?:ship)?|medlem(?:snr)?|loyalty|points|poang|"
                    + "authorization|auth code|a id|aid|tvr|tsi|rrn|order(?:nr)?|table|bord)\\b");
    private static final Pattern ITEM_HEADER = Pattern.compile(
            "^(?:(?:article|artikel|vara|varor|description|item|items|produkt)\\s+"
                    + "(?:pris|price|amount|belopp|qty|antal|quantity)\\b|"
                    + "(?:description|items|varor|artiklar|produkter|article|artikel)$)");
    private static final Pattern DISCOUNT = Pattern.compile(
            "^(?:(?:member|loyalty|item|coupon|store) discount|discount|rabatt|medlemsrabatt|kupong)\\b");
    private static final Pattern ADJUSTMENT = Pattern.compile(
            "^(?:sales tax|tax|vat|moms|rounding|oresavrundning|avrundning|service charge|tip|dricks)\\b");
    private static final Pattern QUANTITY = Pattern.compile(
            "(?iu)\\b\\d+(?:[.,]\\d{1,3})?\\s*(?:(?:kg|g|lb|st|pcs|ea)\\s*)?(?:[x×@à]|a\\s)\\s*\\d");
    private static final Pattern QTY_WORDS = Pattern.compile("(?i)\\b(?:kg|g|lb|st|pcs|ea|x|kr|sek|usd)\\b");
    private static final Pattern COLUMN_QUANTITY = Pattern.compile("\\d{1,4}(?:[.,]\\d{1,3})?");
    private static final Pattern PAYMENT_LABEL = Pattern.compile(
            "^(?:card|credit|debit|kort|betalkort|payment|cash tendered|kontant|vaxel)(?: payment| purchase)?$");
    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private ReceiptParser() {}

    static ReceiptExtraction parse(List<ReceiptLayout.Word> words, Locale locale) {
        List<ReceiptLayout.Row> rows = ReceiptLayout.rows(words);
        List<String> keys = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        for (ReceiptLayout.Row row : rows) {
            if (raw.length() > 0) {
                raw.append('\n');
            }
            raw.append(row.text);
            keys.add(key(row.text));
        }

        String document = key(raw.toString());
        boolean swedish = SWEDISH.matcher(document).find();
        boolean monthFirst = !swedish && !DAY_FIRST_CURRENCY.matcher(document).find()
                && ("US".equals(locale.getCountry()) || US.matcher(document).find());
        String date = findDate(keys, swedish, monthFirst);
        int merchantRow = findMerchant(rows, keys);
        String merchant = merchantRow < 0 ? "" : rows.get(merchantRow).text.replaceAll("\\s+", " ");
        Matcher merchantLabel = EXPLICIT_MERCHANT.matcher(merchant);
        if (merchantLabel.matches()) {
            merchant = merchantLabel.group(1);
        }

        Total total = findTotal(rows);
        Items items = findItems(rows, keys, Math.max(0, merchantRow + 1), total);
        return new ReceiptExtraction(
                merchant, date, total.cents == null ? null : total.cents / 100.0, raw.toString(),
                items.rows, !items.reconciles(total.cents)
        );
    }

    private static int findMerchant(List<ReceiptLayout.Row> rows, List<String> keys) {
        int firstPlausible = -1;
        int count = Math.min(9, rows.size());
        for (int i = 0; i < count; i++) {
            String text = keys.get(i);
            if (ITEM_HEADER.matcher(text).find() || totalScore(text) > 0 || SUMMARY.matcher(text).find()) {
                break;
            }
            if (EXPLICIT_MERCHANT.matcher(rows.get(i).text).matches()) {
                return i;
            }
            if (moneyAtEnd(rows.get(i).text) != null) {
                break;
            }
            if (NON_MERCHANT.matcher(text).find() || ADDRESS.matcher(text).find() || containsDate(text)
                    || CLOCK.matcher(text).find() || !LETTER.matcher(text).find()) {
                continue;
            }
            if (CHAIN.matcher(text).find()) {
                return i;
            }
            if (firstPlausible == -1 && i < 5 && text.length() >= 2 && text.length() <= 60) {
                firstPlausible = i;
            }
        }
        return firstPlausible;
    }

    private static String findDate(List<String> keys, boolean swedish, boolean monthFirst) {
        String chosen = "";
        int bestScore = -1;
        boolean conflict = false;
        for (int i = 0; i < keys.size(); i++) {
            String text = keys.get(i);
            if (NOT_PURCHASE_DATE.matcher(text).find()) {
                continue;
            }
            String date = dateIn(text, swedish, monthFirst);
            if (date.isEmpty()) {
                continue;
            }
            int score = (DATE_LABEL.matcher(text).find() ? 6 : 0)
                    + (CLOCK.matcher(text).find() ? 3 : 0) + (i < 8 ? 1 : 0);
            if (score > bestScore) {
                chosen = date;
                bestScore = score;
                conflict = false;
            } else if (score == bestScore && !date.equals(chosen)) {
                conflict = true;
            }
        }
        return conflict ? "" : chosen;
    }

    private static String dateIn(String text, boolean swedish, boolean monthFirst) {
        Matcher ymd = YMD.matcher(text);
        if (ymd.find()) {
            return isoDate(integer(ymd, 1), integer(ymd, 3), integer(ymd, 4));
        }
        Matcher numeric = SHORT_DATE.matcher(text);
        if (numeric.find()) {
            int a = integer(numeric, 1), b = integer(numeric, 3), year = integer(numeric, 4);
            if (swedish && "-".equals(numeric.group(2)) && numeric.group(4).length() == 2 && a >= 20) {
                String shortIso = isoDate(2000 + a, b, year);
                if (!shortIso.isEmpty()) {
                    return shortIso;
                }
            }
            if (numeric.group(4).length() == 2) {
                year += year >= 90 ? 1900 : 2000;
            }
            if (a > 12 || (b <= 12 && !monthFirst)) {
                return isoDate(year, b, a);
            }
            return isoDate(year, a, b);
        }
        Matcher dayFirst = DAY_FIRST_MONTH.matcher(text);
        if (dayFirst.find()) {
            return isoDate(integer(dayFirst, 3), month(dayFirst.group(2)), integer(dayFirst, 1));
        }
        Matcher named = MONTH_FIRST_DAY.matcher(text);
        if (named.find()) {
            return isoDate(integer(named, 3), month(named.group(1)), integer(named, 2));
        }
        return "";
    }

    private static int month(String name) {
        String[] months = {"jan", "feb", "mar", "apr", "may/maj", "jun", "jul", "aug",
                "sep", "oct/okt", "nov", "dec"};
        String prefix = name.substring(0, 3);
        for (int i = 0; i < months.length; i++) {
            for (String alias : months[i].split("/")) {
                if (alias.equals(prefix)) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    private static String isoDate(int year, int month, int day) {
        if (year < 1990 || year > 2099 || month < 1 || month > 12 || day < 1) {
            return "";
        }
        boolean leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        int[] days = {31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return day > days[month - 1] ? "" : String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }

    static boolean isIsoDate(String text) {
        Matcher match = YMD.matcher(text);
        return match.matches() && text.equals(isoDate(integer(match, 1), integer(match, 3), integer(match, 4)));
    }

    private static int integer(Matcher match, int index) {
        return Integer.parseInt(match.group(index));
    }

    private static boolean containsDate(String text) {
        return YMD.matcher(text).find() || SHORT_DATE.matcher(text).find()
                || !dateIn(text, false, false).isEmpty();
    }

    private static Total findTotal(List<ReceiptLayout.Row> rows) {
        Total best = new Total(null, rows.size());
        int bestScore = 0;
        boolean conflict = false;
        for (int i = 0; i < rows.size(); i++) {
            String text = rows.get(i).text;
            Amount amount = amountForTotal(text);
            int score = totalScore(amount == null ? text : text.substring(0, amount.start));
            if (score == 0) {
                continue;
            }
            if (amount == null && i + 1 < rows.size()
                    && rows.get(i + 1).y - rows.get(i).y <= rows.get(i).height * 2.8f) {
                Amount next = amountForTotal(rows.get(i + 1).text);
                if (next != null && rows.get(i + 1).text.substring(0, next.start).trim().isEmpty()) {
                    amount = next;
                }
            }
            if (amount == null) {
                best.index = Math.min(best.index, i);
            } else if (score > bestScore) {
                best = new Total(amount.cents, i);
                bestScore = score;
                conflict = false;
            } else if (score == bestScore && !Long.valueOf(amount.cents).equals(best.cents)) {
                conflict = true;
            }
        }
        return conflict ? new Total(null, best.index) : best;
    }

    private static int totalScore(String text) {
        String label = key(text).replaceAll("[.:=*]+", " ").trim()
                .replaceAll("\\s+(?:sek|usd|eur|gbp|nok|dkk|cad|aud|chf|kr)$", "").trim();
        if (PAY_TOTAL.matcher(label).matches()) {
            return 2;
        }
        return GENERIC_TOTAL.matcher(label).matches() ? 1 : 0;
    }

    private static Items findItems(List<ReceiptLayout.Row> rows, List<String> keys, int start, Total total) {
        Items items = new Items();
        int summaryStart = total.index;
        int pendingName = -1;
        for (int i = start; i < total.index; i++) {
            ReceiptLayout.Row row = rows.get(i);
            String text = keys.get(i);
            Amount amount = moneyAtEnd(row.text);
            if (SUMMARY.matcher(text).find() || totalScore(text) > 0
                    || (amount != null && (totalScore(row.text.substring(0, amount.start)) > 0
                            || PAYMENT_LABEL.matcher(key(row.text.substring(0, amount.start))).matches()))) {
                if (!items.rows.isEmpty()) {
                    summaryStart = i;
                    break;
                }
                pendingName = -1;
                continue;
            }
            if (META.matcher(text).find() || ITEM_HEADER.matcher(text).find()
                    || containsDate(text) || CLOCK.matcher(text).find() || ADDRESS.matcher(text).find()
                    || NOT_PURCHASE_DATE.matcher(text).find() || ADJUSTMENT.matcher(text).find()) {
                if (amount != null && ADJUSTMENT.matcher(text).find()) {
                    items.adjustments += amount.cents;
                }
                pendingName = -1;
                continue;
            }
            if (amount == null) {
                pendingName = LETTER.matcher(text).find() ? i : -1;
                continue;
            }

            String prefix = cleanItemName(row.text.substring(0, amount.start));
            boolean continuation = prefix.isEmpty() || (QUANTITY.matcher(prefix).find()
                    && !LETTER.matcher(QTY_WORDS.matcher(prefix).replaceAll("")).find());
            String name = continuation ? "" : prefix;
            if (pendingName == i - 1 && pendingName >= 0
                    && row.y - rows.get(pendingName).y <= row.height * 2.5f) {
                ReceiptLayout.Row prior = rows.get(pendingName);
                if (continuation && row.rightAt(amount.end - 1) > prior.left + 2 * row.height) {
                    name = cleanItemName(prior.text);
                } else if (!continuation && row.left - prior.left > row.height * 0.6f
                        && row.left - prior.left < row.height * 3 && !DISCOUNT.matcher(text).find()) {
                    name = cleanItemName(prior.text + " " + name);
                }
            }
            pendingName = -1;
            if (LETTER.matcher(name).find()) {
                boolean discount = DISCOUNT.matcher(key(name)).find();
                items.add(name, discount ? -Math.abs(amount.cents) : amount.cents);
            }
        }

        // Adjustments printed in the summary can explain a difference without becoming purchases.
        for (int i = summaryStart; i < total.index; i++) {
            Amount amount = moneyAtEnd(rows.get(i).text);
            if (amount != null) {
                if (ADJUSTMENT.matcher(keys.get(i)).find()) {
                    items.adjustments += amount.cents;
                } else if (amount.cents < 0 && DISCOUNT.matcher(keys.get(i)).find()) {
                    items.add(cleanItemName(rows.get(i).text.substring(0, amount.start)), amount.cents);
                }
            }
        }
        return items;
    }

    private static String cleanItemName(String name) {
        String cleaned = name.trim().replaceAll("^\\d{5,}\\s+", "").replaceAll("\\s*[:=]+$", "");
        // Separate quantity and unit-price columns from the product name.
        int gap;
        while ((gap = cleaned.lastIndexOf("  ")) >= 0) {
            String field = cleaned.substring(gap + 2).trim();
            Amount amount = moneyAtEnd(field);
            if (!COLUMN_QUANTITY.matcher(field).matches() && (amount == null || amount.start != 0)) {
                break;
            }
            cleaned = cleaned.substring(0, gap).trim();
        }
        Matcher quantity = QUANTITY.matcher(cleaned);
        if (quantity.find() && quantity.start() > 0 && LETTER.matcher(cleaned.substring(0, quantity.start())).find()) {
            cleaned = cleaned.substring(0, quantity.start());
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private static Amount moneyAtEnd(String text) {
        Matcher match = MONEY.matcher(text);
        Amount result = null;
        while (match.find()) {
            int start = match.start(), end = match.end();
            long value = cents(match.group());
            if (start > 0 && end < text.length() && text.charAt(start - 1) == '(' && text.charAt(end) == ')') {
                start--;
                end++;
                value = -Math.abs(value);
            }
            if (AMOUNT_TAIL.matcher(text.substring(end)).matches()) {
                result = new Amount(value, start, end);
            }
        }
        return result;
    }

    private static Amount amountForTotal(String text) {
        Amount money = moneyAtEnd(text);
        if (money != null) {
            return money;
        }
        Matcher whole = WHOLE_AT_END.matcher(text);
        return whole.find() ? new Amount(cents(whole.group()), whole.start(), whole.end()) : null;
    }

    private static long cents(String text) {
        boolean negative = text.startsWith("-") || text.startsWith("−") || text.startsWith("–")
                || (text.endsWith("-") && !text.endsWith(":-")) || text.endsWith("−");
        String digits = CURRENCY_TOKEN.matcher(text).replaceAll("").replace(":-", "")
                .replaceAll("[^0-9.,]", "");
        int separator = Math.max(digits.lastIndexOf('.'), digits.lastIndexOf(','));
        long amount = Long.parseLong(digits.replaceAll("[.,]", ""));
        if (separator < 0 || digits.length() - separator != 3) {
            amount *= 100;
        }
        return negative ? -amount : amount;
    }

    static String key(String text) {
        return MARKS.matcher(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00a0\\u202f]+", " ").trim();
    }

    private static final class Amount {
        final long cents;
        final int start;
        final int end;

        Amount(long cents, int start, int end) {
            this.cents = cents;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Total {
        final Long cents;
        int index;

        Total(Long cents, int index) {
            this.cents = cents;
            this.index = index;
        }
    }

    private static final class Items {
        final List<ReceiptItem> rows = new ArrayList<>();
        long sum;
        long adjustments;

        void add(String name, long cents) {
            rows.add(new ReceiptItem(name, cents / 100.0));
            sum += cents;
        }

        boolean reconciles(Long total) {
            return total != null && !rows.isEmpty()
                    && (Math.abs(sum - total) <= 1 || Math.abs(sum + adjustments - total) <= 1);
        }
    }
}
