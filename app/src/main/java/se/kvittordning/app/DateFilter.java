package se.kvittordning.app;

public class DateFilter {
    public enum Mode {
        ALL,
        MONTH,
        YEAR,
        CUSTOM
    }

    public final Mode mode;
    public final String month;
    public final String year;
    public final String startDate;
    public final String endDate;

    private DateFilter(Mode mode, String month, String year, String startDate, String endDate) {
        this.mode = mode;
        this.month = month;
        this.year = year;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public static DateFilter all() {
        return new DateFilter(Mode.ALL, "", "", "", "");
    }

    public static DateFilter month(String yyyyMm) {
        return new DateFilter(Mode.MONTH, yyyyMm, "", "", "");
    }

    public static DateFilter year(String yyyy) {
        return new DateFilter(Mode.YEAR, "", yyyy, "", "");
    }

    public static DateFilter custom(String startDate, String endDate) {
        return new DateFilter(Mode.CUSTOM, "", "", startDate, endDate);
    }

    public boolean matches(String date) {
        if (date == null || date.length() < 4) {
            return mode == Mode.ALL;
        }
        switch (mode) {
            case MONTH:
                return date.startsWith(month);
            case YEAR:
                return date.startsWith(year);
            case CUSTOM:
                boolean afterStart = startDate.isEmpty() || date.compareTo(startDate) >= 0;
                boolean beforeEnd = endDate.isEmpty() || date.compareTo(endDate) <= 0;
                return afterStart && beforeEnd;
            case ALL:
            default:
                return true;
        }
    }

    public String label() {
        switch (mode) {
            case MONTH:
                return month;
            case YEAR:
                return year;
            case CUSTOM:
                return startDate + " - " + endDate;
            case ALL:
            default:
                return "All time";
        }
    }
}
