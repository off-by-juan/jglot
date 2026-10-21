package io.github.offbyjuan.jglot.optimizer;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * The parts of Python's {@code datetime} module (and dateutil's {@code relativedelta}) that
 * sqlglot's type annotator and simplifier rely on, built on {@code java.time}: {@code
 * date.fromisoformat}, {@code datetime.fromisoformat}, date/datetime arithmetic, comparison and
 * {@code str()} formatting.
 */
final class PyDates {
    private PyDates() {}

    /** Python's ValueError/TypeError raised by datetime operations. */
    static final class PyDateError extends IllegalArgumentException {
        PyDateError(String message) {
            super(message);
        }
    }

    private static final long MICROS_PER_DAY = 86_400_000_000L;

    /**
     * A Python {@code datetime.date} ({@code isDate}) or {@code datetime.datetime}, optionally with a
     * fixed UTC offset (in microseconds) for aware datetimes. Immutable.
     */
    static final class PyDateTime implements Comparable<PyDateTime> {
        final LocalDateTime value;
        final boolean isDate;
        final Long tzMicros;

        private PyDateTime(LocalDateTime value, boolean isDate, Long tzMicros) {
            if (value.getYear() < 1 || value.getYear() > 9999) {
                throw new PyDateError("year " + value.getYear() + " is out of range");
            }
            this.value = value;
            this.isDate = isDate;
            this.tzMicros = isDate ? null : tzMicros;
        }

        static PyDateTime date(LocalDate d) {
            return new PyDateTime(d.atStartOfDay(), true, null);
        }

        static PyDateTime datetime(LocalDateTime dt, Long tzMicros) {
            return new PyDateTime(dt, false, tzMicros);
        }

        int year() {
            return value.getYear();
        }

        int month() {
            return value.getMonthValue();
        }

        int day() {
            return value.getDayOfMonth();
        }

        int microsecond() {
            return value.getNano() / 1000;
        }

        /** {@code date.weekday()}: Monday is 0. */
        int weekday() {
            return value.getDayOfWeek().getValue() - 1;
        }

        /** {@code datetime.date()} for datetimes, identity for dates. */
        PyDateTime toDate() {
            return isDate ? this : date(value.toLocalDate());
        }

        /** {@code cast_as_datetime}: dates become midnight datetimes. */
        PyDateTime toDatetime() {
            return isDate ? datetime(value, null) : this;
        }

        PyDateTime with(LocalDateTime v) {
            return new PyDateTime(v, isDate, tzMicros);
        }

        /** {@code self + timedelta(days=days)}. */
        PyDateTime plusDays(long days) {
            return with(value.plusDays(days));
        }

        /** {@code self + relativedelta}. */
        PyDateTime plus(Delta delta) {
            return delta.addTo(this);
        }

        /** {@code self - relativedelta}. */
        PyDateTime minus(Delta delta) {
            return delta.negate().addTo(this);
        }

        private long utcMicros() {
            LocalDateTime v = value;
            long days = v.toLocalDate().toEpochDay();
            long micros = days * MICROS_PER_DAY + v.toLocalTime().toNanoOfDay() / 1000;
            return micros - (tzMicros == null ? 0 : tzMicros);
        }

        /** Python {@code ==}: dates and datetimes never compare equal, nor do naive and aware datetimes. */
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PyDateTime other) || other.isDate != isDate) {
                return false;
            }
            if (isDate) {
                return value.equals(other.value);
            }
            if ((tzMicros == null) != (other.tzMicros == null)) {
                return false;
            }
            return tzMicros == null ? value.equals(other.value) : utcMicros() == other.utcMicros();
        }

        @Override
        public int hashCode() {
            return isDate || tzMicros == null ? value.hashCode() : Long.hashCode(utcMicros());
        }

        /** Python ordering; mixing dates with datetimes or naive with aware datetimes is a TypeError. */
        @Override
        public int compareTo(PyDateTime other) {
            if (isDate != other.isDate) {
                throw new PyDateError("can't compare datetime.datetime to datetime.date");
            }
            if (isDate) {
                return value.compareTo(other.value);
            }
            if ((tzMicros == null) != (other.tzMicros == null)) {
                throw new PyDateError("can't compare offset-naive and offset-aware datetimes");
            }
            return tzMicros == null ? value.compareTo(other.value) : Long.compare(utcMicros(), other.utcMicros());
        }

        /** Python {@code str()}: {@code date.isoformat()} / {@code datetime.isoformat(sep=" ")}. */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(32);
            pad(sb, year(), 4).append('-');
            pad(sb, month(), 2).append('-');
            pad(sb, day(), 2);
            if (isDate) {
                return sb.toString();
            }
            sb.append(' ');
            pad(sb, value.getHour(), 2).append(':');
            pad(sb, value.getMinute(), 2).append(':');
            pad(sb, value.getSecond(), 2);
            if (microsecond() != 0) {
                sb.append('.');
                pad(sb, microsecond(), 6);
            }
            if (tzMicros != null) {
                long off = tzMicros;
                sb.append(off < 0 ? '-' : '+');
                off = Math.abs(off);
                long hh = off / 3_600_000_000L;
                long mm = off / 60_000_000L % 60;
                long ss = off / 1_000_000L % 60;
                long us = off % 1_000_000L;
                pad(sb, hh, 2).append(':');
                pad(sb, mm, 2);
                if (ss != 0 || us != 0) {
                    sb.append(':');
                    pad(sb, ss, 2);
                    if (us != 0) {
                        sb.append('.');
                        pad(sb, us, 6);
                    }
                }
            }
            return sb.toString();
        }

        private static StringBuilder pad(StringBuilder sb, long v, int width) {
            String s = Long.toString(v);
            for (int i = s.length(); i < width; i++) {
                sb.append('0');
            }
            return sb.append(s);
        }
    }

    /**
     * dateutil's {@code relativedelta} restricted to the relative fields sqlglot uses (years, months,
     * weeks/days, hours, minutes, seconds, microseconds), normalized the way {@code _fix} does.
     */
    static final class Delta {
        final long years;
        final long months;
        final long days;
        final long hours;
        final long minutes;
        final long seconds;
        final long microseconds;

        Delta(long years, long months, long weeks, long days, long hours, long minutes, long seconds, long microseconds) {
            long[] f = {years, months, days + weeks * 7, hours, minutes, seconds, microseconds};
            fix(f);
            this.years = f[0];
            this.months = f[1];
            this.days = f[2];
            this.hours = f[3];
            this.minutes = f[4];
            this.seconds = f[5];
            this.microseconds = f[6];
        }

        private static void carry(long[] f, int from, int to, long limit, long base) {
            if (Math.abs(f[from]) > limit) {
                long s = Long.signum(f[from]);
                long v = f[from] * s;
                long div = Math.floorDiv(v, base);
                long mod = Math.floorMod(v, base);
                f[from] = mod * s;
                f[to] += div * s;
            }
        }

        private static void fix(long[] f) {
            carry(f, 6, 5, 999_999, 1_000_000);
            carry(f, 5, 4, 59, 60);
            carry(f, 4, 3, 59, 60);
            carry(f, 3, 2, 23, 24);
            carry(f, 1, 0, 11, 12);
        }

        boolean hasTime() {
            return hours != 0 || minutes != 0 || seconds != 0 || microseconds != 0;
        }

        Delta negate() {
            return new Delta(-years, -months, 0, -days, -hours, -minutes, -seconds, -microseconds);
        }

        /** {@code relativedelta.__add__(other)} for a date/datetime {@code other}. */
        PyDateTime addTo(PyDateTime other) {
            if (hasTime() && other.isDate) {
                other = PyDateTime.datetime(other.value, null);
            }
            long year = other.year() + years;
            long month = other.month();
            if (months != 0) {
                month += months;
                if (month > 12) {
                    year += 1;
                    month -= 12;
                } else if (month < 1) {
                    year -= 1;
                    month += 12;
                }
            }
            if (year < 1 || year > 9999) {
                throw new PyDateError("year " + year + " is out of range");
            }
            int dim = YearMonth.of((int) year, (int) month).lengthOfMonth();
            int day = Math.min(dim, other.day());
            LocalDateTime replaced = other.value.withDayOfMonth(1).withYear((int) year).withMonth((int) month)
                    .withDayOfMonth(day);
            LocalDateTime result = replaced.plusDays(days);
            if (!other.isDate) {
                result = result.plusHours(hours).plusMinutes(minutes).plusSeconds(seconds)
                        .plusNanos(microseconds * 1000);
            }
            return other.with(result);
        }
    }

    // ------------------------------------------------------------------------------------------
    // ISO parsing (CPython's datetime.fromisoformat / date.fromisoformat)

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int digits(String s, int from, int to) {
        if (from < 0 || to > s.length() || from >= to) {
            throw new PyDateError("Invalid isoformat string");
        }
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!isDigit(c)) {
                throw new PyDateError("Invalid isoformat string");
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static int findIsoformatDatetimeSeparator(String dtstr) {
        int len = dtstr.length();
        if (len == 7) {
            return 7;
        }

        if (dtstr.charAt(4) == '-') {
            if (dtstr.charAt(5) == 'W') {
                if (len > 8 && dtstr.charAt(8) == '-') {
                    if (len == 9) {
                        throw new PyDateError("Invalid ISO string");
                    }
                    if (len > 10 && isDigit(dtstr.charAt(10))) {
                        return 8;
                    }
                    return 10;
                }
                // YYYY-Www (8)
                return 8;
            }
            // YYYY-MM-DD (10)
            return 10;
        }
        if (dtstr.charAt(4) == 'W') {
            // YYYYWww (7) or YYYYWwwd (8)
            int idx = 7;
            while (idx < len) {
                if (!isDigit(dtstr.charAt(idx))) {
                    break;
                }
                idx += 1;
            }

            if (idx < 9) {
                return idx;
            }

            if (idx % 2 == 0) {
                // If the index of the last number is even, it's YYYYWwwd
                return 7;
            }
            return 8;
        }
        // YYYYMMDD (8)
        return 8;
    }

    private static LocalDate parseIsoformatDate(String dtstr) {
        int len = dtstr.length();
        if (len != 7 && len != 8 && len != 10) {
            throw new PyDateError("Invalid isoformat string");
        }
        int year = digits(dtstr, 0, 4);
        boolean hasSep = dtstr.charAt(4) == '-';

        int pos = 4 + (hasSep ? 1 : 0);
        try {
            if (pos < len && dtstr.charAt(pos) == 'W') {
                // YYYY-?Www-?D?
                pos += 1;
                int weekno = digits(dtstr, pos, pos + 2);
                pos += 2;

                int dayno = 1;
                if (len > pos) {
                    if ((dtstr.charAt(pos) == '-') != hasSep) {
                        throw new PyDateError("Inconsistent use of dash separator");
                    }
                    pos += hasSep ? 1 : 0;
                    dayno = digits(dtstr, pos, pos + 1);
                    if (pos + 1 != len) {
                        throw new PyDateError("Invalid isoformat string");
                    }
                }
                return isoweekToGregorian(year, weekno, dayno);
            }
            int month = digits(dtstr, pos, pos + 2);
            pos += 2;
            if ((pos < len && dtstr.charAt(pos) == '-') != hasSep) {
                throw new PyDateError("Inconsistent use of dash separator");
            }
            pos += hasSep ? 1 : 0;
            int day = digits(dtstr, pos, pos + 2);
            if (pos + 2 != len) {
                throw new PyDateError("Invalid isoformat string");
            }
            if (year < 1) {
                throw new PyDateError("year 0 is out of range");
            }
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw new PyDateError(e.getMessage());
        }
    }

    private static LocalDate isoweekToGregorian(int year, int week, int day) {
        if (year < 1 || year > 9999) {
            throw new PyDateError("year must be in 1..9999");
        }
        if (!(0 < week && week < 53)) {
            boolean outOfRange = true;
            if (week == 53) {
                // ISO years have 53 weeks in them on years starting with a
                // Thursday and leap years starting on a Wednesday
                int firstWeekday = LocalDate.of(year, 1, 1).getDayOfWeek().getValue(); // Monday=1
                if (firstWeekday == 4 || (firstWeekday == 3 && java.time.Year.isLeap(year))) {
                    outOfRange = false;
                }
            }
            if (outOfRange) {
                throw new PyDateError("Invalid week: " + week);
            }
        }
        if (!(0 < day && day < 8)) {
            throw new PyDateError("Invalid weekday: " + day + " (range is [1, 7])");
        }
        // Monday of ISO week 1 is the Monday of the week containing January 4th
        LocalDate jan4 = LocalDate.of(year, 1, 4);
        LocalDate week1Monday = jan4.minusDays(jan4.getDayOfWeek().getValue() - 1);
        LocalDate result = week1Monday.plusDays((week - 1) * 7L + (day - 1));
        if (result.getYear() < 1 || result.getYear() > 9999) {
            throw new PyDateError("date value out of range");
        }
        return result;
    }

    private static final int[] FRACTION_CORRECTION = {100000, 10000, 1000, 100, 10};

    /** Parses HH[:?MM[:?SS[{.,}fff[fff]]]] into {hour, minute, second, microsecond}. */
    private static int[] parseHhMmSsFf(String tstr) {
        int len = tstr.length();
        int[] comps = {0, 0, 0, 0};
        int pos = 0;
        boolean hasSep = false;
        for (int comp = 0; comp < 3; comp++) {
            if (len - pos < 2) {
                throw new PyDateError("Incomplete time component");
            }
            comps[comp] = digits(tstr, pos, pos + 2);
            pos += 2;
            char nextChar = pos < len ? tstr.charAt(pos) : 0;

            if (comp == 0) {
                hasSep = nextChar == ':';
            }
            if (pos >= len || comp >= 2) {
                break;
            }
            if (hasSep && nextChar != ':') {
                throw new PyDateError("Invalid time separator");
            }
            pos += hasSep ? 1 : 0;
        }

        if (pos < len) {
            char c = tstr.charAt(pos);
            if (c != '.' && c != ',') {
                throw new PyDateError("Invalid microsecond separator");
            }
            pos += 1;
            int remainder = len - pos;
            if (remainder == 0) {
                throw new PyDateError("Invalid isoformat string");
            }
            for (int i = pos; i < len; i++) {
                if (!isDigit(tstr.charAt(i))) {
                    throw new PyDateError("Non-digit values in fraction");
                }
            }
            int toParse = Math.min(remainder, 6);
            comps[3] = digits(tstr, pos, pos + toParse);
            if (toParse < 6) {
                comps[3] *= FRACTION_CORRECTION[toParse - 1];
            }
        }
        return comps;
    }

    /** {@code date.fromisoformat(text)}, or null if Python raises ValueError. */
    static PyDateTime dateFromIsoformat(String text) {
        if (text == null || !isAscii(text)) {
            return null;
        }
        int len = text.length();
        if (len != 7 && len != 8 && len != 10) {
            return null;
        }
        try {
            return PyDateTime.date(parseIsoformatDate(text));
        } catch (PyDateError e) {
            return null;
        }
    }

    /** {@code datetime.fromisoformat(text)}, or null if Python raises ValueError. */
    static PyDateTime datetimeFromIsoformat(String text) {
        if (text == null || text.length() < 7) {
            return null;
        }
        try {
            int sep = findIsoformatDatetimeSeparator(text);
            String dstr = text.substring(0, Math.min(sep, text.length()));
            String tstr = sep + 1 <= text.length() ? text.substring(sep + 1) : "";
            if (!isAscii(dstr)) {
                return null;
            }
            LocalDate date = parseIsoformatDate(dstr);

            if (tstr.isEmpty()) {
                return PyDateTime.datetime(date.atStartOfDay(), null);
            }
            if (!isAscii(tstr) || tstr.length() < 2) {
                return null;
            }

            int tzPos = tstr.indexOf('-') + 1;
            if (tzPos == 0) {
                tzPos = tstr.indexOf('+') + 1;
            }
            if (tzPos == 0) {
                tzPos = tstr.indexOf('Z') + 1;
            }
            String timestr = tzPos > 0 ? tstr.substring(0, tzPos - 1) : tstr;
            int[] t = parseHhMmSsFf(timestr);

            boolean becameNextDay = false;
            if (t[0] == 24) {
                if (t[1] == 0 && t[2] == 0 && t[3] == 0) {
                    t[0] = 0;
                    becameNextDay = true;
                } else {
                    return null;
                }
            }

            Long tz = null;
            int lenT = tstr.length();
            if (tzPos == lenT && tstr.charAt(lenT - 1) == 'Z') {
                tz = 0L;
            } else if (tzPos > 0) {
                String tzstr = tstr.substring(tzPos);
                int tl = tzstr.length();
                if (tl == 0 || tl == 1 || tl == 3 || tstr.charAt(tzPos - 1) == 'Z') {
                    return null;
                }
                int[] tc = parseHhMmSsFf(tzstr);
                long micros = ((tc[0] * 60L + tc[1]) * 60L + tc[2]) * 1_000_000L + tc[3];
                if (micros >= MICROS_PER_DAY) {
                    return null;
                }
                tz = tstr.charAt(tzPos - 1) == '-' ? -micros : micros;
            }

            if (t[1] > 59 || t[2] > 59 || t[0] > 23) {
                return null;
            }
            LocalDateTime dt = date.atTime(t[0], t[1], t[2], t[3] * 1000);
            if (becameNextDay) {
                dt = dt.plusDays(1);
            }
            return PyDateTime.datetime(dt, tz);
        } catch (PyDateError | DateTimeException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }

    /** {@code helper.is_iso_date}. */
    static boolean isIsoDate(String text) {
        return dateFromIsoformat(text) != null;
    }

    /** {@code helper.is_iso_datetime}. */
    static boolean isIsoDatetime(String text) {
        return datetimeFromIsoformat(text) != null;
    }
}
