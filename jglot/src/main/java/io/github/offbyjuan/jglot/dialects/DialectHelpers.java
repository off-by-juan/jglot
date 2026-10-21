package io.github.offbyjuan.jglot.dialects;

import io.github.offbyjuan.jglot.errors.ParseError;
import io.github.offbyjuan.jglot.expressions.Arg;
import io.github.offbyjuan.jglot.expressions.Args;
import io.github.offbyjuan.jglot.expressions.DType;
import io.github.offbyjuan.jglot.expressions.DataTypes;
import io.github.offbyjuan.jglot.expressions.Exp;
import io.github.offbyjuan.jglot.expressions.ExprType;
import io.github.offbyjuan.jglot.expressions.Expression;
import io.github.offbyjuan.jglot.generator.Generator;
import io.github.offbyjuan.jglot.generator.GeneratorOptions;
import io.github.offbyjuan.jglot.optimizer.Scopes;
import io.github.offbyjuan.jglot.optimizer.TypeAnnotator;
import io.github.offbyjuan.jglot.util.Py;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the module-level helper functions of sqlglot's {@code dialects/dialect.py}.
 *
 * <p>Conventions:
 *
 * <ul>
 *   <li>Generator helpers {@code def foo_sql(self, expression)} are {@code fooSql(Generator self,
 *       Expression expression)}.
 *   <li>Factories returning a generator function ({@code if_sql(name, false_value)}) are a single
 *       method taking {@code (self, expression, <factory params>)} and returning the SQL directly.
 *   <li>Parser builders and builder factories take the factory params first, then {@code
 *       List<Expression> args, Dialect dialect} (the parsing dialect, unused by some builders).
 * </ul>
 */
public final class DialectHelpers {
    private DialectHelpers() {}

    // ------------------------------------------------------------------------------------------
    // Module-level constants

    /** sqlglot's {@code UNESCAPED_SEQUENCES}. */
    public static final Map<String, String> UNESCAPED_SEQUENCES;

    /** sqlglot's {@code STRICT_TIME_FORMATS}. */
    public static final Map<String, String> STRICT_TIME_FORMATS;

    /** sqlglot's {@code WEEK_START_DAY_TO_DOW}: days of week to ISO 8601 day-of-week numbers. */
    public static final Map<String, Integer> WEEK_START_DAY_TO_DOW;

    static {
        LinkedHashMap<String, String> u = new LinkedHashMap<>();
        u.put("\\a", "\u0007");
        u.put("\\b", "\b");
        u.put("\\f", "\f");
        u.put("\\n", "\n");
        u.put("\\r", "\r");
        u.put("\\t", "\t");
        u.put("\\v", "\u000b");
        u.put("\\\\", "\\");
        UNESCAPED_SEQUENCES = Collections.unmodifiableMap(u);

        LinkedHashMap<String, String> s = new LinkedHashMap<>();
        s.put("%mstrict", "%m");
        s.put("%dstrict", "%d");
        s.put("%Hstrict", "%H");
        s.put("%Istrict", "%I");
        s.put("%Mstrict", "%M");
        s.put("%Sstrict", "%S");
        STRICT_TIME_FORMATS = Collections.unmodifiableMap(s);

        LinkedHashMap<String, Integer> w = new LinkedHashMap<>();
        w.put("MONDAY", 1);
        w.put("TUESDAY", 2);
        w.put("WEDNESDAY", 3);
        w.put("THURSDAY", 4);
        w.put("FRIDAY", 5);
        w.put("SATURDAY", 6);
        w.put("SUNDAY", 7);
        WEEK_START_DAY_TO_DOW = Collections.unmodifiableMap(w);
    }

    /** sqlglot's {@code time.TIMEZONES} (lower-cased tz database names). */
    private static final Set<String> TIMEZONES = Set.of(Py.split("""
            africa/abidjan africa/accra africa/addis_ababa africa/algiers africa/asmara africa/asmera
            africa/bamako africa/bangui africa/banjul africa/bissau africa/blantyre africa/brazzaville
            africa/bujumbura africa/cairo africa/casablanca africa/ceuta africa/conakry africa/dakar
            africa/dar_es_salaam africa/djibouti africa/douala africa/el_aaiun africa/freetown
            africa/gaborone africa/harare africa/johannesburg africa/juba africa/kampala africa/khartoum
            africa/kigali africa/kinshasa africa/lagos africa/libreville africa/lome africa/luanda
            africa/lubumbashi africa/lusaka africa/malabo africa/maputo africa/maseru africa/mbabane
            africa/mogadishu africa/monrovia africa/nairobi africa/ndjamena africa/niamey africa/nouakchott
            africa/ouagadougou africa/porto-novo africa/sao_tome africa/timbuktu africa/tripoli
            africa/tunis africa/windhoek america/adak america/anchorage america/anguilla america/antigua
            america/araguaina america/argentina/buenos_aires america/argentina/catamarca
            america/argentina/comodrivadavia america/argentina/cordoba america/argentina/jujuy
            america/argentina/la_rioja america/argentina/mendoza america/argentina/rio_gallegos
            america/argentina/salta america/argentina/san_juan america/argentina/san_luis
            america/argentina/tucuman america/argentina/ushuaia america/aruba america/asuncion
            america/atikokan america/atka america/bahia america/bahia_banderas america/barbados
            america/belem america/belize america/blanc-sablon america/boa_vista america/bogota
            america/boise america/buenos_aires america/cambridge_bay america/campo_grande america/cancun
            america/caracas america/catamarca america/cayenne america/cayman america/chicago
            america/chihuahua america/ciudad_juarez america/coral_harbour america/cordoba
            america/costa_rica america/creston america/cuiaba america/curacao america/danmarkshavn
            america/dawson america/dawson_creek america/denver america/detroit america/dominica
            america/edmonton america/eirunepe america/el_salvador america/ensenada america/fort_nelson
            america/fort_wayne america/fortaleza america/glace_bay america/godthab america/goose_bay
            america/grand_turk america/grenada america/guadeloupe america/guatemala america/guayaquil
            america/guyana america/halifax america/havana america/hermosillo america/indiana/indianapolis
            america/indiana/knox america/indiana/marengo america/indiana/petersburg
            america/indiana/tell_city america/indiana/vevay america/indiana/vincennes
            america/indiana/winamac america/indianapolis america/inuvik america/iqaluit america/jamaica
            america/jujuy america/juneau america/kentucky/louisville america/kentucky/monticello
            america/knox_in america/kralendijk america/la_paz america/lima america/los_angeles
            america/louisville america/lower_princes america/maceio america/managua america/manaus
            america/marigot america/martinique america/matamoros america/mazatlan america/mendoza
            america/menominee america/merida america/metlakatla america/mexico_city america/miquelon
            america/moncton america/monterrey america/montevideo america/montreal america/montserrat
            america/nassau america/new_york america/nipigon america/nome america/noronha
            america/north_dakota/beulah america/north_dakota/center america/north_dakota/new_salem
            america/nuuk america/ojinaga america/panama america/pangnirtung america/paramaribo
            america/phoenix america/port-au-prince america/port_of_spain america/porto_acre
            america/porto_velho america/puerto_rico america/punta_arenas america/rainy_river
            america/rankin_inlet america/recife america/regina america/resolute america/rio_branco
            america/rosario america/santa_isabel america/santarem america/santiago america/santo_domingo
            america/sao_paulo america/scoresbysund america/shiprock america/sitka america/st_barthelemy
            america/st_johns america/st_kitts america/st_lucia america/st_thomas america/st_vincent
            america/swift_current america/tegucigalpa america/thule america/thunder_bay america/tijuana
            america/toronto america/tortola america/vancouver america/virgin america/whitehorse
            america/winnipeg america/yakutat america/yellowknife antarctica/casey antarctica/davis
            antarctica/dumontdurville antarctica/macquarie antarctica/mawson antarctica/mcmurdo
            antarctica/palmer antarctica/rothera antarctica/south_pole antarctica/syowa antarctica/troll
            antarctica/vostok arctic/longyearbyen asia/aden asia/almaty asia/amman asia/anadyr asia/aqtau
            asia/aqtobe asia/ashgabat asia/ashkhabad asia/atyrau asia/baghdad asia/bahrain asia/baku
            asia/bangkok asia/barnaul asia/beirut asia/bishkek asia/brunei asia/calcutta asia/chita
            asia/choibalsan asia/chongqing asia/chungking asia/colombo asia/dacca asia/damascus asia/dhaka
            asia/dili asia/dubai asia/dushanbe asia/famagusta asia/gaza asia/harbin asia/hebron
            asia/ho_chi_minh asia/hong_kong asia/hovd asia/irkutsk asia/istanbul asia/jakarta asia/jayapura
            asia/jerusalem asia/kabul asia/kamchatka asia/karachi asia/kashgar asia/kathmandu asia/katmandu
            asia/khandyga asia/kolkata asia/krasnoyarsk asia/kuala_lumpur asia/kuching asia/kuwait
            asia/macao asia/macau asia/magadan asia/makassar asia/manila asia/muscat asia/nicosia
            asia/novokuznetsk asia/novosibirsk asia/omsk asia/oral asia/phnom_penh asia/pontianak
            asia/pyongyang asia/qatar asia/qostanay asia/qyzylorda asia/rangoon asia/riyadh asia/saigon
            asia/sakhalin asia/samarkand asia/seoul asia/shanghai asia/singapore asia/srednekolymsk
            asia/taipei asia/tashkent asia/tbilisi asia/tehran asia/tel_aviv asia/thimbu asia/thimphu
            asia/tokyo asia/tomsk asia/ujung_pandang asia/ulaanbaatar asia/ulan_bator asia/urumqi
            asia/ust-nera asia/vientiane asia/vladivostok asia/yakutsk asia/yangon asia/yekaterinburg
            asia/yerevan atlantic/azores atlantic/bermuda atlantic/canary atlantic/cape_verde
            atlantic/faeroe atlantic/faroe atlantic/jan_mayen atlantic/madeira atlantic/reykjavik
            atlantic/south_georgia atlantic/st_helena atlantic/stanley australia/act australia/adelaide
            australia/brisbane australia/broken_hill australia/canberra australia/currie australia/darwin
            australia/eucla australia/hobart australia/lhi australia/lindeman australia/lord_howe
            australia/melbourne australia/north australia/nsw australia/perth australia/queensland
            australia/south australia/sydney australia/tasmania australia/victoria australia/west
            australia/yancowinna brazil/acre brazil/denoronha brazil/east brazil/west canada/atlantic
            canada/central canada/eastern canada/mountain canada/newfoundland canada/pacific
            canada/saskatchewan canada/yukon cet chile/continental chile/easterisland cst6cdt cuba eet
            egypt eire est est5edt etc/gmt etc/gmt+0 etc/gmt+1 etc/gmt+10 etc/gmt+11 etc/gmt+12 etc/gmt+2
            etc/gmt+3 etc/gmt+4 etc/gmt+5 etc/gmt+6 etc/gmt+7 etc/gmt+8 etc/gmt+9 etc/gmt-0 etc/gmt-1
            etc/gmt-10 etc/gmt-11 etc/gmt-12 etc/gmt-13 etc/gmt-14 etc/gmt-2 etc/gmt-3 etc/gmt-4 etc/gmt-5
            etc/gmt-6 etc/gmt-7 etc/gmt-8 etc/gmt-9 etc/gmt0 etc/greenwich etc/uct etc/universal etc/utc
            etc/zulu europe/amsterdam europe/andorra europe/astrakhan europe/athens europe/belfast
            europe/belgrade europe/berlin europe/bratislava europe/brussels europe/bucharest
            europe/budapest europe/busingen europe/chisinau europe/copenhagen europe/dublin
            europe/gibraltar europe/guernsey europe/helsinki europe/isle_of_man europe/istanbul
            europe/jersey europe/kaliningrad europe/kiev europe/kirov europe/kyiv europe/lisbon
            europe/ljubljana europe/london europe/luxembourg europe/madrid europe/malta europe/mariehamn
            europe/minsk europe/monaco europe/moscow europe/nicosia europe/oslo europe/paris
            europe/podgorica europe/prague europe/riga europe/rome europe/samara europe/san_marino
            europe/sarajevo europe/saratov europe/simferopol europe/skopje europe/sofia europe/stockholm
            europe/tallinn europe/tirane europe/tiraspol europe/ulyanovsk europe/uzhgorod europe/vaduz
            europe/vatican europe/vienna europe/vilnius europe/volgograd europe/warsaw europe/zagreb
            europe/zaporozhye europe/zurich gb gb-eire gmt gmt+0 gmt-0 gmt0 greenwich hongkong hst iceland
            indian/antananarivo indian/chagos indian/christmas indian/cocos indian/comoro indian/kerguelen
            indian/mahe indian/maldives indian/mauritius indian/mayotte indian/reunion iran israel jamaica
            japan kwajalein libya met mexico/bajanorte mexico/bajasur mexico/general mst mst7mdt navajo nz
            nz-chat pacific/apia pacific/auckland pacific/bougainville pacific/chatham pacific/chuuk
            pacific/easter pacific/efate pacific/enderbury pacific/fakaofo pacific/fiji pacific/funafuti
            pacific/galapagos pacific/gambier pacific/guadalcanal pacific/guam pacific/honolulu
            pacific/johnston pacific/kanton pacific/kiritimati pacific/kosrae pacific/kwajalein
            pacific/majuro pacific/marquesas pacific/midway pacific/nauru pacific/niue pacific/norfolk
            pacific/noumea pacific/pago_pago pacific/palau pacific/pitcairn pacific/pohnpei pacific/ponape
            pacific/port_moresby pacific/rarotonga pacific/saipan pacific/samoa pacific/tahiti
            pacific/tarawa pacific/tongatapu pacific/truk pacific/wake pacific/wallis pacific/yap poland
            portugal prc pst8pdt roc rok singapore turkey uct universal us/alaska us/aleutian us/arizona
            us/central us/east-indiana us/eastern us/hawaii us/indiana-starke us/michigan us/mountain
            us/pacific us/samoa utc w-su wet zulu
            """).toArray(new String[0]));

    // ------------------------------------------------------------------------------------------
    // Private helpers

    /** {@code Dialect[name]} / {@code Dialect.get_or_raise(name)}; null means the base dialect. */
    private static Dialect resolveDialect(String name) {
        if (name == null) {
            return Dialects.base();
        }
        Dialect d = Dialects.find(name);
        if (d == null) {
            throw new IllegalArgumentException("Unknown dialect '" + name + "'.");
        }
        return d;
    }

    /** Python's {@code str(expression)}: SQL in the base dialect ("None" for null). */
    private static String pyStr(Expression e) {
        return e == null ? "None" : e.sql(Dialects.base());
    }

    /** sqlglot's {@code Expr._binop(klass, other)} ({@code a + b}, {@code a - b}, {@code a.eq(b)}). */
    private static Expression binop(ExprType klass, Expression self, Object other) {
        Expression thisExpr = self.copy();
        Expression otherExpr = other instanceof Expression o ? o.copy() : Exp.convert(other);
        if (!thisExpr.is(klass) && !otherExpr.is(klass)) {
            thisExpr = Exp.wrap(thisExpr, ExprType.BINARY);
            otherExpr = Exp.wrap(otherExpr, ExprType.BINARY);
        }
        return Expression.of(klass, Args.THIS, thisExpr, Args.EXPRESSION, otherExpr);
    }

    private static Expression copyOrNull(Expression e) {
        return e == null ? null : e.copy();
    }

    private static Object first(Object value, Object fallback) {
        return Expression.truthy(value) ? value : fallback;
    }

    /** {@code dialect.format_time(fmt)} for a quoted string, an expression or null. */
    private static Expression formatTimeWith(Dialect dialect, Object fmt) {
        if (fmt instanceof String s) {
            return dialect.formatTime(s);
        }
        return dialect.formatTime((Expression) fmt);
    }

    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-?(\\d{2})-?(\\d{2})");
    private static final Pattern ISO_TIME = Pattern.compile(
            "^(\\d{2})(?::?(\\d{2})(?::?(\\d{2})(?:[.,](\\d+))?)?)?"
                    + "(Z|[+-]\\d{2}(?::?\\d{2}(?::?\\d{2}(?:[.,]\\d+)?)?)?)?$");

    /**
     * sqlglot's {@code time.subsecond_precision}: the subsecond precision (0, 3 or 6) of an ISO-8601
     * timestamp literal, following Python's {@code datetime.fromisoformat}.
     */
    private static int subsecondPrecision(String timestampLiteral) {
        Matcher dm = ISO_DATE.matcher(timestampLiteral);
        if (!dm.find()) {
            return 0;
        }
        String datePart = dm.group();
        boolean extended = datePart.length() == 10;
        if (datePart.length() != 8 && !extended) {
            return 0;
        }
        try {
            LocalDate.of(Integer.parseInt(dm.group(1)), Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(3)));
        } catch (DateTimeException e) {
            return 0;
        }
        String rest = timestampLiteral.substring(datePart.length());
        if (rest.isEmpty()) {
            return 0;
        }
        // Any single separator character is accepted between the date and the time
        Matcher tm = ISO_TIME.matcher(rest.substring(1));
        if (!tm.matches()) {
            return 0;
        }
        int hour = Integer.parseInt(tm.group(1));
        int minute = tm.group(2) == null ? 0 : Integer.parseInt(tm.group(2));
        int second = tm.group(3) == null ? 0 : Integer.parseInt(tm.group(3));
        if (hour > 23 || minute > 59 || second > 59) {
            return 0;
        }
        String fraction = tm.group(4);
        if (fraction == null) {
            return 0;
        }
        // fromisoformat truncates the fraction to microseconds; this is
        // len(str(parsed.microsecond).zfill(6).rstrip("0"))
        String micros = fraction.length() >= 6 ? fraction.substring(0, 6) : fraction + "0".repeat(6 - fraction.length());
        int count = micros.length();
        while (count > 0 && micros.charAt(count - 1) == '0') {
            count--;
        }
        if (count > 3) {
            return 6;
        }
        if (count > 0) {
            return 3;
        }
        return 0;
    }

    /** Python {@code int(text)} for a JSON path subscript. */
    private static Object pyInt(String text, int delta) {
        BigInteger v = new BigInteger(text.strip().replace("_", "")).add(BigInteger.valueOf(delta));
        return v.bitLength() < 32 ? (Object) v.intValue() : v;
    }

    // ------------------------------------------------------------------------------------------
    // Module-level helpers defined before rename_func

    /**
     * sqlglot's {@code _with_strict_time_inverse(inverse_mapping)}: completes an inverse time mapping
     * with the strict/lax format pairs. Mutates and returns {@code inverseMapping}.
     */
    public static Map<String, String> withStrictTimeInverse(Map<String, String> inverseMapping) {
        for (Map.Entry<String, String> entry : STRICT_TIME_FORMATS.entrySet()) {
            String strictFormat = entry.getKey();
            String laxFormat = entry.getValue();
            if (inverseMapping.containsKey(strictFormat)) {
                // In strict dialects, a foreign lax %m formats the same padded way as %mstrict (MM)
                inverseMapping.putIfAbsent(laxFormat, inverseMapping.get(strictFormat));
            } else {
                // Elsewhere, the strict format degrades to its lax counterpart so it never leaks
                inverseMapping.putIfAbsent(strictFormat, inverseMapping.getOrDefault(laxFormat, laxFormat));
            }
        }
        return inverseMapping;
    }

    // ------------------------------------------------------------------------------------------
    // Generator helpers

    /** {@code rename_func(name)(self, expression)}. */
    public static String renameFunc(Generator self, Expression expression, String name) {
        return self.renameFunc(name, expression);
    }

    public static String bracketToElementAtSql(Generator self, Expression expression) {
        Object offsetObj = expression.get(Args.OFFSET);
        int offset = offsetObj instanceof Number n ? n.intValue() : 0;
        Expression index = Py.seqGet(
                Exp.applyIndexOffset(expression.getThis(), expression.getExpressions(), 1 - offset, self.dialect()), 0);
        return self.func("ELEMENT_AT", expression.getThis(), index);
    }

    private static final BigInteger INT32_MAX = BigInteger.valueOf((1L << 31) - 1);

    private static boolean inInt32Range(Expression e) {
        if (e == null || !e.isInt()) {
            return false;
        }
        BigInteger v = (BigInteger) e.toPy();
        return v.signum() >= 0 && v.compareTo(INT32_MAX) <= 0;
    }

    public static String arraySliceToSliceSql(Generator self, Expression expression) {
        Expression start = expression.expr(Args.START);
        Expression end = expression.expr(Args.END);

        // Preserve unmarked slices and bounds that require runtime normalization or narrowing.
        if (Boolean.FALSE.equals(expression.get(Args.IS_LENGTH))
                && expression.get(Args.STEP) == null
                && inInt32Range(start)
                && end != null
                && inInt32Range(end)) {
            BigInteger startValue = ((BigInteger) start.toPy()).max(BigInteger.ONE);
            BigInteger length = ((BigInteger) end.toPy()).subtract(startValue).add(BigInteger.ONE).max(BigInteger.ZERO);
            return self.func("SLICE", expression.getThis(), Exp.number(startValue), Exp.number(length));
        }

        return self.renameFunc("SLICE", expression);
    }

    public static String approxCountDistinctSql(Generator self, Expression expression) {
        self.unsupportedArgs(expression, Args.ACCURACY);
        return self.func("APPROX_COUNT_DISTINCT", expression.getThis());
    }

    /**
     * {@code if_sql(name, false_value)(self, expression)}. {@code falseValue} is an Expression, a
     * string or null.
     */
    public static String ifSql(Generator self, Expression expression, String name, Object falseValue) {
        return self.func(
                name,
                expression.getThis(),
                expression.get(Args.TRUE),
                first(expression.get(Args.FALSE), falseValue));
    }

    /** {@code if_sql()(self, expression)}: {@code name="IF"}, {@code false_value=None}. */
    public static String ifSql(Generator self, Expression expression) {
        return ifSql(self, expression, "IF", null);
    }

    public static String arrowJsonExtractSql(Generator self, Expression expression, String op) {
        Expression thisExpr = expression.getThis();
        if (self.dialect().generatorData().JSON_TYPE_REQUIRED_FOR_EXTRACTION
                && thisExpr != null && thisExpr.is(ExprType.LITERAL) && thisExpr.isString()) {
            thisExpr.replace(Exp.cast(thisExpr, DType.JSON));
        }

        Expression expr = expression.getExpression();
        if (expr != null && expr.is(ExprType.BINARY, ExprType.PREDICATE, ExprType.NOT)) {
            expression.set(Args.EXPRESSION, Exp.paren(expr));
        }

        if (op == null || op.isEmpty()) {
            op = expression.is(ExprType.JSON_EXTRACT) ? "->" : "->>";
        }
        return self.binary(expression, op);
    }

    public static String arrowJsonExtractSql(Generator self, Expression expression) {
        return arrowJsonExtractSql(self, expression, null);
    }

    public static String inlineArraySql(Generator self, Expression expression) {
        return "[" + self.expressions(Generator.opts(expression).dynamic().newLine().skipFirst().skipLast()) + "]";
    }

    public static String inlineArrayUnlessQuery(Generator self, Expression expression) {
        List<Expression> exprs = expression.getExpressions();
        Expression elem = Py.seqGet(exprs, 0);
        if (exprs.size() == 1
                && elem != null && elem.is(ExprType.QUERY)
                // value_constructor=True keeps scalar subqueries as array elements. In [(SELECT …)], the
                // subquery supplies one scalar value; in ARRAY(SELECT …) (BigQuery), the query supplies rows.
                && !expression.has(Args.VALUE_CONSTRUCTOR)) {
            return self.func("ARRAY", elem);
        }
        return inlineArraySql(self, expression);
    }

    public static String noIlikeSql(Generator self, Expression expression) {
        return self.likeSqlImpl(Expression.of(ExprType.LIKE,
                Args.THIS, Expression.of(ExprType.LOWER, Args.THIS, expression.getThis()),
                Args.EXPRESSION, Expression.of(ExprType.LOWER, Args.THIS, expression.getExpression()),
                Args.NEGATE, expression.get(Args.NEGATE)));
    }

    public static String noParenCurrentDateSql(Generator self, Expression expression) {
        String zone = self.sql(expression, Args.THIS);
        return !zone.isEmpty() ? "CURRENT_DATE AT TIME ZONE " + zone : "CURRENT_DATE";
    }

    public static String noRecursiveCteSql(Generator self, Expression expression) {
        if (expression.has(Args.RECURSIVE)) {
            self.unsupported("Recursive CTEs are unsupported");
            expression.set(Args.RECURSIVE, Boolean.FALSE);
        }
        return self.withSql(expression);
    }

    public static String noTablesampleSql(Generator self, Expression expression) {
        self.unsupported("TABLESAMPLE unsupported");
        return self.sql(expression.getThis());
    }

    public static String noPivotSql(Generator self, Expression expression) {
        self.unsupported("PIVOT unsupported");
        return "";
    }

    public static String noTrycastSql(Generator self, Expression expression) {
        return self.castSql(expression);
    }

    public static String noCommentColumnConstraintSql(Generator self, Expression expression) {
        self.unsupported("CommentColumnConstraint unsupported");
        return "";
    }

    public static String noMapFromEntriesSql(Generator self, Expression expression) {
        self.unsupported("MAP_FROM_ENTRIES unsupported");
        return "";
    }

    public static String noFilterSql(Generator self, Expression expression) {
        // Rewrite `AGG(x) FILTER (WHERE cond)` into an equivalent conditional aggregation, i.e. wrap
        // the input values in an IF, which works because aggregate functions skip NULLs
        Expression agg = expression.getThis();
        // IGNORE NULLS can wrap the aggregate, but the condition still belongs to its arguments
        if (agg.is(ExprType.IGNORE_NULLS)) {
            agg = agg.getThis();
        }

        Expression aggArg = agg.is(ExprType.ANONYMOUS) ? Py.seqGet(agg.getExpressions(), 0) : agg.getThis();
        Expression cond = expression.getExpression().getThis();

        if (agg.is(ExprType.WITHIN_GROUP)) {
            // Ordered-set aggregates take their input from the ORDER BY key, so the
            // condition has to wrap that instead of the aggregate's own argument
            if (aggArg != null && aggArg.is(ExprType.MODE, ExprType.PERCENTILE_CONT, ExprType.PERCENTILE_DISC)) {
                for (Expression ordered : new ArrayList<>(agg.getExpression().getExpressions())) {
                    Expression key = ordered.getThis();
                    key.replace(Expression.of(ExprType.IF,
                            Args.THIS, cond.copy(), Args.TRUE, key.copy(), Args.FALSE, Exp.nul()));
                }
                return self.sql(expression.getThis());
            }

            // Besides the ordered-set aggregates, these are the only functions that take WITHIN GROUP
            // and the input values as arguments, so anything else can't be rewritten correctly
            if (aggArg != null && aggArg.is(ExprType.ARRAY_AGG, ExprType.GROUP_CONCAT)) {
                aggArg = aggArg.getThis();
            } else {
                self.unsupported("Unable to rewrite FILTER into the aggregate's arguments");
                return self.sql(expression.getThis());
            }
        }

        // Folding the condition into COUNT_IF's predicate avoids an IF that returns a predicate, which
        // dialects without a boolean type (e.g. T-SQL) reject
        if (agg.is(ExprType.COUNT_IF) && !(aggArg != null && aggArg.is(ExprType.DISTINCT))) {
            return self.sql(Expression.of(ExprType.COUNT_IF, Args.THIS, Exp.and(copyOrNull(cond), copyOrNull(aggArg))));
        }

        // `COUNT(*/t.*) FILTER (WHERE cond)` counts qualifying rows, but a star can't be an IF
        // argument: `IF(cond, *, NULL)` expands to multiple columns once the table has 2+ of
        // them. Each dialect generates CountIf in its own way instead, e.g. COUNT_IF or COUNTIF.
        if (agg.is(ExprType.COUNT) && (aggArg == null || aggArg.isStar())) {
            return self.sql(Expression.of(ExprType.COUNT_IF, Args.THIS, cond));
        }

        // `DISTINCT` and `ORDER BY` are part of the aggregate's own argument list, so the
        // condition has to wrap the values underneath them rather than the whole clause --
        // `IF(cond, DISTINCT x, NULL)` is not a call any dialect accepts.
        if (aggArg != null && aggArg.is(ExprType.ORDER)) {
            aggArg = aggArg.getThis();
        }

        List<Expression> targets;
        if (aggArg != null && aggArg.is(ExprType.DISTINCT)) {
            targets = new ArrayList<>(aggArg.getExpressions());
        } else {
            targets = new ArrayList<>(Collections.singletonList(aggArg));
        }

        for (Expression target : targets) {
            target.replace(Expression.of(ExprType.IF,
                    Args.THIS, cond.copy(), Args.TRUE, target.copy(), Args.FALSE, Exp.nul()));
        }

        // In dialects where ARRAY_AGG keeps NULLs (e.g. BigQuery, which even fails on them), the
        // rows that don't satisfy the condition have to be dropped explicitly
        if (agg.is(ExprType.ARRAY_AGG) && Expression.truthy(self.dialect().data().ARRAY_AGG_INCLUDES_NULLS)) {
            // IGNORE NULLS already drops them, so no extra FILTER (WHERE x IS NOT NULL) is needed
            agg.set(Args.NULLS_EXCLUDED, null);
            if (agg == expression.getThis()) {
                return self.sql(Expression.of(ExprType.IGNORE_NULLS, Args.THIS, agg));
            }
        }

        return self.sql(expression.getThis());
    }

    public static String propertySql(Generator self, Expression expression) {
        return self.propertyName(expression, true) + "=" + self.sql(expression, Args.VALUE);
    }

    /**
     * {@code strposition_sql(self, expression, func_name, supports_position, supports_occurrence,
     * use_ansi_position)}.
     */
    public static String strpositionSql(Generator self, Expression expression, String funcName,
            boolean supportsPosition, boolean supportsOccurrence, boolean useAnsiPosition) {
        Expression string = expression.getThis();
        Expression substr = expression.expr(Args.SUBSTR);
        Expression position = expression.expr(Args.POSITION);
        Expression occurrence = expression.expr(Args.OCCURRENCE);
        Expression zero = Exp.number(0);
        Expression one = Exp.number(1);

        if (supportsOccurrence && occurrence != null && supportsPosition && position == null) {
            position = one;
        }

        boolean transpilePosition = position != null && !supportsPosition;
        if (transpilePosition) {
            string = Expression.of(ExprType.SUBSTRING, Args.THIS, string, Args.START, position);
        }

        Expression func;
        if (funcName.equals("POSITION") && useAnsiPosition) {
            func = Expression.of(ExprType.ANONYMOUS, Args.THIS, funcName,
                    Args.EXPRESSIONS, List.of(Expression.of(ExprType.IN, Args.THIS, substr, Args.FIELD, string)));
        } else {
            List<Object> args = funcName.equals("LOCATE") || funcName.equals("CHARINDEX")
                    ? new ArrayList<>(Arrays.asList(substr, string))
                    : new ArrayList<>(Arrays.asList(string, substr));
            if (supportsPosition) {
                args.add(position);
            }
            if (occurrence != null) {
                if (supportsOccurrence) {
                    args.add(occurrence);
                } else {
                    self.unsupported(funcName + " does not support the occurrence parameter.");
                }
            }
            func = Expression.of(ExprType.ANONYMOUS, Args.THIS, funcName, Args.EXPRESSIONS, args);
        }

        if (transpilePosition) {
            Expression funcWithOffset = Expression.of(ExprType.SUB,
                    Args.THIS, binop(ExprType.ADD, func, position), Args.EXPRESSION, one);
            Expression funcWrapped = Expression.of(ExprType.IF,
                    Args.THIS, binop(ExprType.EQ, func, zero), Args.TRUE, zero, Args.FALSE, funcWithOffset);
            return self.sql(funcWrapped);
        }

        return self.sql(func);
    }

    /** {@code strposition_sql(self, expression)} with all defaults ({@code func_name="STRPOS"}). */
    public static String strpositionSql(Generator self, Expression expression) {
        return strpositionSql(self, expression, "STRPOS", false, false, true);
    }

    public static String structExtractSql(Generator self, Expression expression) {
        return self.sql(expression, Args.THIS) + "." + self.sql(Exp.toIdentifier(expression.getExpression().name()));
    }

    /**
     * {@code array_append_sql(name, swap_params)(self, expression)}: transpiles ARRAY_APPEND /
     * ARRAY_PREPEND between dialects with different NULL propagation semantics.
     *
     * <p>Some dialects (Databricks, Spark, Snowflake) return NULL when the input array is NULL.
     * Others (DuckDB, Postgres) create a new single-element array instead. With {@code swapParams}
     * the call is generated as (element, array) instead of (array, element).
     */
    public static String arrayAppendSql(Generator self, Expression expression, String name, boolean swapParams) {
        Expression thisExpr = expression.getThis();
        Expression element = expression.getExpression();
        Object[] args = swapParams ? new Object[] {element, thisExpr} : new Object[] {thisExpr, element};
        String funcSql = self.func(name, args);

        boolean sourceNullPropagation = expression.has(Args.NULL_PROPAGATION);
        boolean targetNullPropagation = self.dialect().data().ARRAY_FUNCS_PROPAGATES_NULLS;

        // No transpilation needed when source and target have matching NULL semantics
        if (sourceNullPropagation == targetNullPropagation) {
            return funcSql;
        }

        // Source propagates NULLs, target doesn't: wrap in conditional to return NULL explicitly
        if (sourceNullPropagation) {
            return self.sql(Expression.of(ExprType.IF,
                    Args.THIS, Expression.of(ExprType.IS, Args.THIS, thisExpr, Args.EXPRESSION, Exp.nul()),
                    Args.TRUE, Exp.nul(),
                    Args.FALSE, funcSql));
        }

        // Source doesn't propagate NULLs, target does: use COALESCE to convert NULL to empty array
        thisExpr = Expression.of(ExprType.COALESCE,
                Args.EXPRESSIONS, Arrays.asList(thisExpr, Exp.array(List.of())));
        args = swapParams ? new Object[] {element, thisExpr} : new Object[] {thisExpr, element};
        return self.func(name, args);
    }

    /** {@code array_append_sql(name)(self, expression)} ({@code swap_params=False}). */
    public static String arrayAppendSql(Generator self, Expression expression, String name) {
        return arrayAppendSql(self, expression, name, false);
    }

    /** {@code generate_series_sql(func_name, exclusive_func_name)(self, expression)}. */
    public static String generateSeriesSql(Generator self, Expression expression, String funcName, String exclusiveFuncName) {
        Object start = expression.get(Args.START);
        Object end = expression.get(Args.END);
        Object step = expression.get(Args.STEP);

        if (expression.has(Args.IS_END_EXCLUSIVE)) {
            if (exclusiveFuncName != null && !exclusiveFuncName.isEmpty()) {
                return self.func(exclusiveFuncName, start, end, step);
            }
            Expression adjustedEnd = Expression.of(ExprType.SUB, Args.THIS, end, Args.EXPRESSION, Exp.number(1));
            return self.func(funcName, start, adjustedEnd, step);
        }

        return self.func(funcName, start, end, step);
    }

    /** {@code generate_series_sql(func_name)(self, expression)}. */
    public static String generateSeriesSql(Generator self, Expression expression, String funcName) {
        return generateSeriesSql(self, expression, funcName, null);
    }

    /** {@code _build_func_call} of {@code array_concat_sql}: variadic vs binary nesting. */
    private static String arrayConcatFuncCall(Generator self, String funcName, List<Expression> args) {
        if (self.dialect().generatorData().ARRAY_CONCAT_IS_VAR_LEN) {
            return self.func(funcName, args.toArray());
        } else if (args.size() == 1) {
            // Single arg gets empty array to preserve semantics
            return self.func(funcName, args.get(0), Exp.array(List.of()));
        } else {
            // Snowflake/PostgreSQL/Redshift require binary nesting: ARRAY_CAT(a, ARRAY_CAT(b, c))
            // Build right-deep tree recursively to avoid creating new ArrayConcat expressions
            String result = self.func(funcName, args.get(args.size() - 2), args.get(args.size() - 1));
            for (int i = args.size() - 3; i >= 0; i--) {
                result = funcName + "(" + self.sql(args.get(i)) + ", " + result + ")";
            }
            return result;
        }
    }

    /**
     * {@code array_concat_sql(name)(self, expression)}: transpiles ARRAY_CONCAT / ARRAY_CAT between
     * dialects with different NULL propagation semantics.
     *
     * <p>Some dialects (Redshift, Snowflake, Spark) return NULL when ANY input array is NULL. Others
     * (DuckDB, PostgreSQL) skip NULL arrays and continue concatenation.
     */
    public static String arrayConcatSql(Generator self, Expression expression, String name) {
        Expression thisExpr = expression.getThis();
        List<Expression> exprs = expression.getExpressions();
        List<Expression> allArgs = new ArrayList<>();
        allArgs.add(thisExpr);
        allArgs.addAll(exprs);

        boolean sourceNullPropagation = expression.has(Args.NULL_PROPAGATION);
        boolean targetNullPropagation = self.dialect().data().ARRAY_FUNCS_PROPAGATES_NULLS;

        // Skip wrapper when source and target have matching NULL semantics,
        // or when the first argument is an array literal (which can never be NULL),
        // or when it's a single-argument call (empty array is added, preserving NULL semantics)
        if (sourceNullPropagation == targetNullPropagation
                || (thisExpr != null && thisExpr.is(ExprType.ARRAY))
                || exprs.isEmpty()) {
            return arrayConcatFuncCall(self, name, allArgs);
        }

        // Case 1: Source propagates NULLs, target doesn't (Snowflake → DuckDB)
        // Check if ANY argument is NULL and return NULL explicitly
        if (sourceNullPropagation) {
            // Build OR-chain: a IS NULL OR b IS NULL OR c IS NULL
            Expression combinedCheck = null;
            for (Expression arg : allArgs) {
                Expression check = Expression.of(ExprType.IS, Args.THIS, arg.copy(), Args.EXPRESSION, Exp.nul());
                combinedCheck = combinedCheck == null
                        ? check
                        : Expression.of(ExprType.OR, Args.THIS, combinedCheck, Args.EXPRESSION, check);
            }

            String funcSql = arrayConcatFuncCall(self, name, allArgs);

            return self.sql(Expression.of(ExprType.IF,
                    Args.THIS, combinedCheck, Args.TRUE, Exp.nul(), Args.FALSE, funcSql));
        }

        // Case 2: Source doesn't propagate NULLs, target does (DuckDB → Snowflake)
        // Wrap ALL arguments in COALESCE to convert NULL → empty array
        List<Expression> wrappedArgs = new ArrayList<>();
        for (Expression arg : allArgs) {
            wrappedArgs.add(Expression.of(ExprType.COALESCE,
                    Args.EXPRESSIONS, Arrays.asList(arg.copy(), Exp.array(List.of()))));
        }

        return arrayConcatFuncCall(self, name, wrappedArgs);
    }

    public static String varMapSql(Generator self, Expression expression, String mapFuncName) {
        Object keys = expression.get(Args.KEYS);
        Object values = expression.get(Args.VALUES);

        if (!Expression.isInstance(keys, ExprType.ARRAY) || !Expression.isInstance(values, ExprType.ARRAY)) {
            self.unsupported("Cannot convert array columns into map.");
            return self.func(mapFuncName, keys, values);
        }

        List<Expression> keyList = ((Expression) keys).getExpressions();
        List<Expression> valueList = ((Expression) values).getExpressions();
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < Math.min(keyList.size(), valueList.size()); i++) {
            args.add(self.sql(keyList.get(i)));
            args.add(self.sql(valueList.get(i)));
        }

        return self.func(mapFuncName, args.toArray());
    }

    /** {@code var_map_sql(self, expression)} ({@code map_func_name="MAP"}). */
    public static String varMapSql(Generator self, Expression expression) {
        return varMapSql(self, expression, "MAP");
    }

    /**
     * Transpiles MONTHS_BETWEEN to dialects that don't have native support.
     *
     * <p>Snowflake's MONTHS_BETWEEN returns whole months + fractional part where the fractional part
     * is (DAY(date1) - DAY(date2)) / 31, or 0 if both dates are the last day of their month.
     */
    public static String monthsBetweenSql(Generator self, Expression expression) {
        Expression date1 = expression.getThis();
        Expression date2 = expression.getExpression();

        // Cast to DATE to ensure consistent behavior
        Expression date1Cast = Exp.cast(date1, DType.DATE, false, null);
        Expression date2Cast = Exp.cast(date2, DType.DATE, false, null);

        // Whole months: DATEDIFF('month', date2, date1)
        Expression wholeMonths = Expression.of(ExprType.DATE_DIFF,
                Args.THIS, date1Cast, Args.EXPRESSION, date2Cast, Args.UNIT, Exp.var("month"));

        // Day components
        Expression day1 = Expression.of(ExprType.DAY, Args.THIS, date1Cast.copy());
        Expression day2 = Expression.of(ExprType.DAY, Args.THIS, date2Cast.copy());

        // Last day of month components
        Expression lastDayOfMonth1 = Expression.of(ExprType.LAST_DAY, Args.THIS, date1Cast.copy());
        Expression lastDayOfMonth2 = Expression.of(ExprType.LAST_DAY, Args.THIS, date2Cast.copy());

        Expression dayOfLastDay1 = Expression.of(ExprType.DAY, Args.THIS, lastDayOfMonth1);
        Expression dayOfLastDay2 = Expression.of(ExprType.DAY, Args.THIS, lastDayOfMonth2);

        // Check if both are last day of month
        Expression lastDay1 = Expression.of(ExprType.EQ, Args.THIS, day1.copy(), Args.EXPRESSION, dayOfLastDay1);
        Expression lastDay2 = Expression.of(ExprType.EQ, Args.THIS, day2.copy(), Args.EXPRESSION, dayOfLastDay2);
        Expression bothLastDay = Expression.of(ExprType.AND, Args.THIS, lastDay1, Args.EXPRESSION, lastDay2);

        // Fractional part: (DAY(date1) - DAY(date2)) / 31.0
        Expression fractional = Expression.of(ExprType.DIV,
                Args.THIS, Expression.of(ExprType.PAREN, Args.THIS,
                        Expression.of(ExprType.SUB, Args.THIS, day1.copy(), Args.EXPRESSION, day2.copy())),
                Args.EXPRESSION, Exp.number("31.0"));

        // If both are last day of month, fractional = 0, else calculate fractional
        Expression fractionalWithCheck = Expression.of(ExprType.IF,
                Args.THIS, bothLastDay, Args.TRUE, Exp.number("0"), Args.FALSE, fractional);

        // Final result: whole_months + fractional
        Expression result = Expression.of(ExprType.ADD, Args.THIS, wholeMonths, Args.EXPRESSION, fractionalWithCheck);

        return self.sql(result);
    }

    /**
     * {@code time_format(dialect)(self, expression)}: the time format of {@code expression}, unless
     * it's equivalent to the default time format of {@code dialectName} (null: the base dialect),
     * in which case null.
     */
    public static String timeFormat(Generator self, Expression expression, String dialectName) {
        String timeFormat = self.formatTime(expression);
        return !Objects.equals(timeFormat, resolveDialect(dialectName).data().TIME_FORMAT) ? timeFormat : null;
    }

    /** {@code date_add_interval_sql(data_type, kind)(self, expression)}. */
    public static String dateAddIntervalSql(Generator self, Expression expression, String dataType, String kind) {
        String thisSql = self.sql(expression, Args.THIS);
        Expression interval = Expression.of(ExprType.INTERVAL,
                Args.THIS, expression.getExpression(), Args.UNIT, unitToVar(expression));
        return dataType + "_" + kind + "(" + thisSql + ", " + self.sql(interval) + ")";
    }

    /** {@code timestamptrunc_sql(func, zone)(self, expression)}. */
    public static String timestamptruncSql(Generator self, Expression expression, String func, boolean zone) {
        List<Object> args = new ArrayList<>();
        args.add(weekstartUnitToStr(self, expression));
        args.add(expression.getThis());
        if (zone) {
            args.add(expression.get(Args.ZONE));
        }
        return self.func(func, args.toArray());
    }

    /** {@code timestamptrunc_sql()(self, expression)}: {@code func="DATE_TRUNC"}, {@code zone=False}. */
    public static String timestamptruncSql(Generator self, Expression expression) {
        return timestamptruncSql(self, expression, "DATE_TRUNC", false);
    }

    /** {@code timestamptrunc_sql(zone=zone)(self, expression)}. */
    public static String timestamptruncSql(Generator self, Expression expression, boolean zone) {
        return timestamptruncSql(self, expression, "DATE_TRUNC", zone);
    }

    public static String noTimestampSql(Generator self, Expression expression) {
        Expression zone = expression.expr(Args.ZONE);
        if (zone == null) {
            Expression annotated = TypeAnnotator.annotateTypes(expression, self.dialect()).dataType();
            Object targetType = annotated != null ? annotated : DType.TIMESTAMP;
            return self.sql(Exp.cast(expression.getThis(), targetType));
        }
        if (TIMEZONES.contains(Py.lower(zone.name()))) {
            return self.sql(Expression.of(ExprType.AT_TIME_ZONE,
                    Args.THIS, Exp.cast(expression.getThis(), DType.TIMESTAMP),
                    Args.ZONE, zone));
        }
        return self.func("TIMESTAMP", expression.getThis(), zone);
    }

    public static String noTimeSql(Generator self, Expression expression) {
        // Transpile BQ's TIME(timestamp, zone) to CAST(TIMESTAMPTZ <timestamp> AT TIME ZONE <zone> AS TIME)
        Expression thisExpr = Exp.cast(expression.getThis(), DType.TIMESTAMPTZ);
        Expression expr = Exp.cast(
                Expression.of(ExprType.AT_TIME_ZONE, Args.THIS, thisExpr, Args.ZONE, expression.get(Args.ZONE)),
                DType.TIME);
        return self.sql(expr);
    }

    public static String noDatetimeSql(Generator self, Expression expression) {
        Expression thisExpr = expression.getThis();
        Expression expr = expression.getExpression();

        if (TIMEZONES.contains(Py.lower(expr.name()))) {
            // Transpile BQ's DATETIME(timestamp, zone) to CAST(TIMESTAMPTZ <timestamp> AT TIME ZONE <zone> AS TIMESTAMP)
            thisExpr = Exp.cast(thisExpr, DType.TIMESTAMPTZ);
            thisExpr = Exp.cast(Expression.of(ExprType.AT_TIME_ZONE, Args.THIS, thisExpr, Args.ZONE, expr), DType.TIMESTAMP);
            return self.sql(thisExpr);
        }

        thisExpr = Exp.cast(thisExpr, DType.DATE);
        expr = Exp.cast(expr, DType.TIME);

        return self.sql(Exp.cast(Expression.of(ExprType.ADD, Args.THIS, thisExpr, Args.EXPRESSION, expr), DType.TIMESTAMP));
    }

    public static String leftToSubstringSql(Generator self, Expression expression) {
        return self.sql(Expression.of(ExprType.SUBSTRING,
                Args.THIS, expression.getThis(),
                Args.START, Exp.number(1),
                Args.LENGTH, expression.getExpression()));
    }

    public static String rightToSubstringSql(Generator self, Expression expression) {
        Expression length = Expression.of(ExprType.LENGTH, Args.THIS, expression.getThis());
        Expression paren = Exp.paren(binop(ExprType.SUB, expression.getExpression(), 1).copy());
        return self.sql(Expression.of(ExprType.SUBSTRING,
                Args.THIS, expression.getThis(),
                Args.START, binop(ExprType.SUB, length, paren)));
    }

    public static String timestrtotimeSql(Generator self, Expression expression, boolean includePrecision) {
        DType builder = expression.has(Args.ZONE) ? DType.TIMESTAMPTZ : DType.TIMESTAMP;
        Expression datatype = DataTypes.of(builder);

        Expression thisExpr = expression.getThis();
        if (thisExpr != null && thisExpr.is(ExprType.LITERAL) && includePrecision) {
            int precision = subsecondPrecision(thisExpr.name());
            if (precision > 0) {
                datatype = Expression.of(ExprType.DATA_TYPE,
                        Args.THIS, datatype.thisValue(),
                        Args.EXPRESSIONS, List.of(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.number(precision))));
            }
        }

        return self.sql(Exp.cast(thisExpr, datatype, true, self.dialect()));
    }

    /** {@code timestrtotime_sql(self, expression)} ({@code include_precision=False}). */
    public static String timestrtotimeSql(Generator self, Expression expression) {
        return timestrtotimeSql(self, expression, false);
    }

    public static String datestrtodateSql(Generator self, Expression expression) {
        return self.sql(Exp.cast(expression.getThis(), DType.DATE));
    }

    /**
     * {@code encode_decode_sql(self, expression, name, replace)}: used for Presto and DuckDB, which
     * use functions that don't support charset and assume utf-8.
     */
    public static String encodeDecodeSql(Generator self, Expression expression, String name, boolean replace) {
        Expression charset = expression.expr(Args.CHARSET);
        if (charset != null) {
            String cs = Py.lower(charset.name());
            if (!cs.equals("utf-8") && !cs.equals("utf8")) {
                self.unsupported("Expected utf-8 character set, got " + pyStr(charset) + ".");
            }
        }

        return self.func(name, expression.getThis(), replace ? expression.get(Args.REPLACE) : null);
    }

    /** {@code encode_decode_sql(self, expression, name)} ({@code replace=True}). */
    public static String encodeDecodeSql(Generator self, Expression expression, String name) {
        return encodeDecodeSql(self, expression, name, true);
    }

    public static String minOrLeast(Generator self, Expression expression) {
        String name = !expression.getExpressions().isEmpty() ? "LEAST" : "MIN";
        return self.renameFunc(name, expression);
    }

    public static String maxOrGreatest(Generator self, Expression expression) {
        String name = !expression.getExpressions().isEmpty() ? "GREATEST" : "MAX";
        return self.renameFunc(name, expression);
    }

    public static String countIfToSum(Generator self, Expression expression) {
        Expression cond = expression.getThis();

        if (expression.getThis() != null && expression.getThis().is(ExprType.DISTINCT)) {
            cond = expression.getThis().getExpressions().get(0);
            self.unsupported("DISTINCT is not supported when converting COUNT_IF to SUM");
        }

        return self.func("sum", Exp.func("if", Dialects.base(), cond, 1, 0));
    }

    /** {@code trim_sql(self, expression, default_trim_type)}. */
    public static String trimSql(Generator self, Expression expression, String defaultTrimType) {
        String removeChars = self.sql(expression, Args.EXPRESSION);

        // Use TRIM/LTRIM/RTRIM syntax if the expression isn't database-specific
        if (removeChars.isEmpty()) {
            return self.trimSql(expression);
        }

        String target = self.sql(expression, Args.THIS);
        String trimType = self.sql(expression, Args.POSITION);
        if (trimType.isEmpty()) {
            trimType = defaultTrimType;
        }
        String collation = self.sql(expression, Args.COLLATION);

        trimType = !trimType.isEmpty() ? trimType + " " : "";
        removeChars = !removeChars.isEmpty() ? removeChars + " " : "";
        String fromPart = !trimType.isEmpty() || !removeChars.isEmpty() ? "FROM " : "";
        collation = !collation.isEmpty() ? " COLLATE " + collation : "";
        return "TRIM(" + trimType + removeChars + fromPart + target + collation + ")";
    }

    /** {@code trim_sql(self, expression)} ({@code default_trim_type=""}). */
    public static String trimSql(Generator self, Expression expression) {
        return trimSql(self, expression, "");
    }

    public static String concatToDpipeSql(Generator self, Expression expression) {
        List<Expression> args = self.convertConcatArgs(expression);
        if (args.isEmpty()) {
            throw new IllegalArgumentException("reduce() of empty iterable with no initial value");
        }
        Expression acc = args.get(0);
        for (int i = 1; i < args.size(); i++) {
            acc = Expression.of(ExprType.D_PIPE, Args.THIS, acc, Args.EXPRESSION, args.get(i));
        }
        return self.sql(acc);
    }

    public static String concatWsToDpipeSql(Generator self, Expression expression) {
        List<Expression> exprs = expression.getExpressions();
        Expression delim = exprs.get(0);
        List<Expression> restArgs = new ArrayList<>(exprs.subList(1, exprs.size()));
        if (restArgs.isEmpty()) {
            throw new IllegalArgumentException("reduce() of empty iterable with no initial value");
        }
        Expression acc = restArgs.get(0);
        for (int i = 1; i < restArgs.size(); i++) {
            acc = Expression.of(ExprType.D_PIPE,
                    Args.THIS, acc,
                    Args.EXPRESSION, Expression.of(ExprType.D_PIPE, Args.THIS, delim, Args.EXPRESSION, restArgs.get(i)));
        }
        return self.sql(acc);
    }

    public static String regexpExtractSql(Generator self, Expression expression) {
        self.unsupportedArgs(expression, Args.POSITION, Args.OCCURRENCE, Args.PARAMETERS);
        Expression group = expression.expr(Args.GROUP);

        // Do not render group if it's the default value for this dialect
        if (group != null && group.name().equals(String.valueOf(self.dialect().data().REGEXP_EXTRACT_DEFAULT_GROUP))) {
            group = null;
        }

        return self.func(expression.type().sqlName(), expression.getThis(), expression.getExpression(), group);
    }

    public static String regexpReplaceSql(Generator self, Expression expression) {
        self.unsupportedArgs(expression, Args.POSITION, Args.OCCURRENCE, Args.MODIFIERS);
        return self.func("REGEXP_REPLACE", expression.getThis(), expression.getExpression(), expression.get(Args.REPLACEMENT));
    }

    public static String anyValueToMaxSql(Generator self, Expression expression) {
        return self.func("MAX", expression.getThis());
    }

    public static String boolXorSql(Generator self, Expression expression) {
        String a = self.sql(expression.get(Args.THIS));
        String b = self.sql(expression.get(Args.EXPRESSION));
        return "(" + a + " AND (NOT " + b + ")) OR ((NOT " + a + ") AND " + b + ")";
    }

    public static String generatedasidentitycolumnconstraintSql(Generator self, Expression expression) {
        String start = self.sql(expression, Args.START);
        if (start.isEmpty()) {
            start = "1";
        }
        String increment = self.sql(expression, Args.INCREMENT);
        if (increment.isEmpty()) {
            increment = "1";
        }
        return "IDENTITY(" + start + ", " + increment + ")";
    }

    /** {@code arg_max_or_min_no_count(name)(self, expression)}. */
    public static String argMaxOrMinNoCount(Generator self, Expression expression, String name) {
        self.unsupportedArgs(expression, Args.COUNT);
        return self.func(name, expression.getThis(), expression.getExpression());
    }

    /**
     * {@code remove_ts_or_ds_to_date(to_sql, args)(self, expression)}: unwraps TsOrDsToDate /
     * TsOrDsToTimestamp (without format) in {@code args}, then renders with {@code toSql} or, if
     * null, {@code function_fallback_sql}.
     */
    public static String removeTsOrDsToDate(Generator self, Expression expression,
            BiFunction<Generator, Expression, String> toSql, List<Arg> args) {
        for (Arg argKey : args) {
            Object arg = expression.get(argKey);
            if (arg instanceof Expression a && a.is(ExprType.TS_OR_DS_TO_DATE, ExprType.TS_OR_DS_TO_TIMESTAMP)
                    && !a.has(Args.FORMAT)) {
                expression.set(argKey, a.get(Args.THIS));
            }
        }

        return toSql != null ? toSql.apply(self, expression) : self.functionFallbackSql(expression);
    }

    /** {@code remove_ts_or_ds_to_date(to_sql)(self, expression)} ({@code args=("this",)}). */
    public static String removeTsOrDsToDate(Generator self, Expression expression,
            BiFunction<Generator, Expression, String> toSql) {
        return removeTsOrDsToDate(self, expression, toSql, List.of(Args.THIS));
    }

    /** {@code date_delta_sql(name, cast)(self, expression)}. */
    public static String dateDeltaSql(Generator self, Expression expression, String name, boolean cast) {
        if (cast && expression.is(ExprType.TS_OR_DS_ADD)) {
            expression = tsOrDsAddCast(expression);
        }

        return self.func(name, unitToVar(expression), expression.getExpression(), expression.getThis());
    }

    /** {@code date_delta_sql(name)(self, expression)} ({@code cast=False}). */
    public static String dateDeltaSql(Generator self, Expression expression, String name) {
        return dateDeltaSql(self, expression, name, false);
    }

    /** {@code date_delta_to_binary_interval_op(cast)(self, expression)}. */
    public static String dateDeltaToBinaryIntervalOp(Generator self, Expression expression, boolean cast) {
        Expression thisExpr = expression.getThis();
        Expression unit = unitToVar(expression);
        String op = expression.is(ExprType.DATE_ADD, ExprType.TIME_ADD, ExprType.DATETIME_ADD,
                ExprType.TS_OR_DS_ADD, ExprType.TIMESTAMP_ADD) ? "+" : "-";

        Object toType = null;
        if (cast) {
            if (expression.is(ExprType.TS_OR_DS_ADD)) {
                toType = returnType(expression);
            } else if (thisExpr.isString()) {
                // Cast string literals (i.e function parameters) to the appropriate type for +/- interval to work
                toType = expression.is(ExprType.DATETIME_ADD, ExprType.DATETIME_SUB) ? DType.DATETIME : DType.DATE;
            }
        }

        thisExpr = toType != null ? Exp.cast(thisExpr, toType) : thisExpr;

        Expression expr = expression.getExpression();
        Expression interval = expr != null && expr.is(ExprType.INTERVAL)
                ? expr
                : Expression.of(ExprType.INTERVAL, Args.THIS, expr, Args.UNIT, unit);

        return self.sql(thisExpr) + " " + op + " " + self.sql(interval);
    }

    /** {@code date_delta_to_binary_interval_op()(self, expression)} ({@code cast=True}). */
    public static String dateDeltaToBinaryIntervalOp(Generator self, Expression expression) {
        return dateDeltaToBinaryIntervalOp(self, expression, true);
    }

    /** {@code weekstart_unit_to_str(self, expression, default)}. */
    public static Expression weekstartUnitToStr(Generator self, Expression expression, String default_) {
        Object unit = expression.get(Args.UNIT);
        if (unit instanceof Expression u && u.is(ExprType.WEEK_START)) {
            return Exp.string(self.weekstartName(u));
        }

        return unitToStr(expression, default_);
    }

    public static Expression weekstartUnitToStr(Generator self, Expression expression) {
        return weekstartUnitToStr(self, expression, "DAY");
    }

    public static String noLastDaySql(Generator self, Expression expression) {
        Expression truncCurrDate = Exp.func("date_trunc", Dialects.base(), "month", expression.getThis());
        Expression plusOneMonth = Exp.func("date_add", Dialects.base(), truncCurrDate, 1, "month");
        Expression minusOneDay = Exp.func("date_sub", Dialects.base(), plusOneMonth, 1, "day");

        return self.sql(Exp.cast(minusOneDay, DType.DATE));
    }

    private static String normalizeName(Generator self, Object identifier) {
        if (!(identifier instanceof Expression id)) {
            return null;
        }
        return self.dialect().normalizeIdentifier(id).name();
    }

    /** Removes table refs from columns in when statements. */
    public static String mergeWithoutTargetSql(Generator self, Expression expression) {
        Object alias = expression.getThis().get(Args.ALIAS);

        Set<String> targets = new HashSet<>();
        targets.add(normalizeName(self, expression.getThis().get(Args.THIS)));

        if (Expression.truthy(alias)) {
            targets.add(normalizeName(self, ((Expression) alias).get(Args.THIS)));
        }

        for (Expression when : expression.expr(Args.WHENS).getExpressions()) {
            // only remove the target table names from certain parts of WHEN MATCHED / WHEN NOT MATCHED
            // they are still valid in the <condition>, the right hand side of each UPDATE and the VALUES part
            // (not the column list) of the INSERT
            Expression then = when.expr(Args.THEN);
            if (then != null) {
                if (then.is(ExprType.UPDATE)) {
                    for (Expression equals : Scopes.findAllInScope(then, ExprType.EQ)) {
                        Expression equalLhs = equals.getThis();
                        if (equalLhs != null && equalLhs.is(ExprType.COLUMN)
                                && targets.contains(normalizeName(self, equalLhs.get(Args.TABLE)))) {
                            equalLhs.replace(Exp.column(equalLhs.getThis()));
                        }
                    }
                }
                if (then.is(ExprType.INSERT)) {
                    Expression columnList = then.getThis();
                    if (columnList != null && columnList.is(ExprType.TUPLE)) {
                        for (Expression column : new ArrayList<>(columnList.getExpressions())) {
                            if (targets.contains(normalizeName(self, column.get(Args.TABLE)))) {
                                column.replace(Exp.column(column.getThis()));
                            }
                        }
                    }
                }
            }
        }

        return self.mergeSql(expression);
    }

    /** {@code json_extract_segments(name, quoted_index, op)(self, expression)}. */
    public static String jsonExtractSegments(Generator self, Expression expression, String name, boolean quotedIndex, String op) {
        Expression path = expression.getExpression();
        if (path == null || !path.is(ExprType.JSON_PATH)) {
            return self.renameFunc(name, expression);
        }

        List<Object> segments = new ArrayList<>();
        for (Expression segment : path.getExpressions()) {
            String p = self.sql(segment);
            if (!p.isEmpty()) {
                if (segment.is(ExprType.JSON_PATH_PART) && (quotedIndex || !segment.is(ExprType.JSON_PATH_SUBSCRIPT))) {
                    p = self.escapeStr(p);
                    p = self.dialect().data().QUOTE_START + p + self.dialect().data().QUOTE_END;
                }
                segments.add(p);
            }
        }

        if (op != null && !op.isEmpty()) {
            StringBuilder sb = new StringBuilder(self.sql(expression.getThis()));
            for (Object s : segments) {
                sb.append(" ").append(op).append(" ").append(s);
            }
            return sb.toString();
        }
        List<Object> funcArgs = new ArrayList<>();
        funcArgs.add(expression.getThis());
        funcArgs.addAll(segments);
        return self.func(name, funcArgs.toArray());
    }

    /** {@code json_extract_segments(name)(self, expression)} ({@code quoted_index=True, op=None}). */
    public static String jsonExtractSegments(Generator self, Expression expression, String name) {
        return jsonExtractSegments(self, expression, name, true, null);
    }

    public static String jsonPathKeyOnlyName(Generator self, Expression expression) {
        Expression thisExpr = expression.getThis();
        if (thisExpr != null && thisExpr.is(ExprType.JSON_PATH_WILDCARD)) {
            self.unsupported("Unsupported wildcard in JSONPathKey expression");
        }

        return expression.name();
    }

    public static String filterArrayUsingUnnest(Generator self, Expression expression) {
        Expression cond = expression.getExpression();
        Object alias;
        if (cond != null && cond.is(ExprType.LAMBDA) && cond.getExpressions().size() == 1) {
            alias = cond.getExpressions().get(0);
            cond = cond.getThis();
        } else if (cond != null && cond.is(ExprType.PREDICATE)) {
            alias = "_u";
        } else if (expression.is(ExprType.ARRAY_REMOVE)) {
            alias = "_u";
            cond = Expression.of(ExprType.NEQ, Args.THIS, alias, Args.EXPRESSION, expression.getExpression());
        } else {
            self.unsupported("Unsupported filter condition");
            return "";
        }

        Expression unnest = Expression.of(ExprType.UNNEST, Args.EXPRESSIONS, List.of(expression.getThis()));
        Expression filtered = Exp.select(alias)
                .from(Exp.tableAlias(unnest, null, List.of(alias)), true)
                .where(true, true, cond);
        return self.sql(Exp.array(List.of(filtered)));
    }

    public static String arrayCompactSql(Generator self, Expression expression) {
        Expression lambdaId = Exp.toIdentifier("_u");
        Expression cond = Exp.not(Expression.of(ExprType.IS, Args.THIS, lambdaId, Args.EXPRESSION, Exp.nul()));
        return self.sql(Expression.of(ExprType.ARRAY_FILTER,
                Args.THIS, expression.getThis(),
                Args.EXPRESSION, Expression.of(ExprType.LAMBDA, Args.THIS, cond, Args.EXPRESSIONS, List.of(lambdaId))));
    }

    public static String removeFromArrayUsingFilter(Generator self, Expression expression) {
        Expression lambdaId = Exp.toIdentifier("_u");
        Expression cond = Expression.of(ExprType.NEQ, Args.THIS, lambdaId, Args.EXPRESSION, expression.getExpression());

        String filterSql = self.sql(Expression.of(ExprType.ARRAY_FILTER,
                Args.THIS, expression.getThis(),
                Args.EXPRESSION, Expression.of(ExprType.LAMBDA, Args.THIS, cond, Args.EXPRESSIONS, List.of(lambdaId))));

        // Handle NULL propagation for ArrayRemove
        boolean sourceNullPropagation = expression.has(Args.NULL_PROPAGATION);
        boolean targetNullPropagation = self.dialect().data().ARRAY_FUNCS_PROPAGATES_NULLS;

        // Source propagates NULLs (Snowflake), target doesn't (DuckDB):
        // When removal value is NULL, return NULL instead of applying filter
        if (sourceNullPropagation && !targetNullPropagation) {
            Expression removalValue = expression.getExpression();

            // Optimization: skip wrapper if removal value is a non-NULL literal
            // (e.g., 5, 'a', TRUE) or an array literal (e.g., [1, 2])
            if (removalValue != null
                    && ((removalValue.is(ExprType.LITERAL) && !removalValue.is(ExprType.NULL))
                            || removalValue.is(ExprType.ARRAY))) {
                return filterSql;
            }

            return self.sql(Expression.of(ExprType.IF,
                    Args.THIS, Expression.of(ExprType.IS, Args.THIS, removalValue, Args.EXPRESSION, Exp.nul()),
                    Args.TRUE, Exp.nul(),
                    Args.FALSE, filterSql));
        }

        return filterSql;
    }

    public static String toNumberWithNlsParam(Generator self, Expression expression) {
        return self.func("TO_NUMBER", expression.getThis(), expression.get(Args.FORMAT), expression.get(Args.NLSPARAM));
    }

    public static String sha256Sql(Generator self, Expression expression) {
        String length = expression.text(Args.LENGTH);
        return self.func("SHA" + (length.isEmpty() ? "256" : length), expression.getThis());
    }

    public static String sha2DigestSql(Generator self, Expression expression) {
        String length = expression.text(Args.LENGTH);
        return self.func("SHA" + (length.isEmpty() ? "256" : length), expression.getThis());
    }

    public static String sequenceSql(Generator self, Expression expression) {
        Expression start = expression.expr(Args.START);
        Expression end = expression.expr(Args.END);
        Expression step = expression.expr(Args.STEP);

        Expression targetType;
        if (start != null && start.is(ExprType.CAST)) {
            targetType = start.expr(Args.TO);
        } else if (end != null && end.is(ExprType.CAST)) {
            targetType = end.expr(Args.TO);
        } else {
            targetType = null;
        }

        if (start != null && end != null) {
            if (targetType != null && targetType.isType(DType.DATE, DType.TIMESTAMP)) {
                if (start.is(ExprType.CAST) && targetType == start.expr(Args.TO)) {
                    end = Exp.cast(end, targetType);
                } else {
                    start = Exp.cast(start, targetType);
                }
            }

            if (expression.has(Args.IS_END_EXCLUSIVE)) {
                Expression stepValue = step != null ? step : Exp.number(1);
                end = Exp.paren(Expression.of(ExprType.SUB, Args.THIS, end, Args.EXPRESSION, stepValue));

                List<Expression> seqArgs = new ArrayList<>();
                for (Expression e : new Expression[] {start, end, step}) {
                    if (e != null) {
                        seqArgs.add(e);
                    }
                }
                Expression sequenceCall = Exp.anonymous("SEQUENCE", seqArgs);
                Expression zero = Exp.number(0);
                Expression shouldReturnEmpty = Exp.or(
                        Expression.of(ExprType.EQ, Args.THIS, stepValue.copy(), Args.EXPRESSION, zero.copy()),
                        Exp.and(
                                Expression.of(ExprType.GT, Args.THIS, stepValue.copy(), Args.EXPRESSION, zero.copy()),
                                Expression.of(ExprType.GT, Args.THIS, start.copy(), Args.EXPRESSION, end.copy())),
                        Exp.and(
                                Expression.of(ExprType.LT, Args.THIS, stepValue.copy(), Args.EXPRESSION, zero.copy()),
                                Expression.of(ExprType.LT, Args.THIS, start.copy(), Args.EXPRESSION, end.copy())));
                Expression emptyArrayOrSequence = Expression.of(ExprType.IF,
                        Args.THIS, shouldReturnEmpty,
                        Args.TRUE, Exp.array(List.of()),
                        Args.FALSE, sequenceCall);
                return self.sql(self.simplifyUnlessLiteral(emptyArrayOrSequence));
            }
        }

        return self.func("SEQUENCE", start, end, step);
    }

    public static String explodeToUnnestSql(Generator self, Expression expression) {
        Expression thisExpr = expression.getThis();
        Expression alias = expression.expr(Args.ALIAS);

        Expression crossJoinExpr = null;
        if (thisExpr != null && thisExpr.is(ExprType.POSEXPLODE) && alias != null) {
            // Spark's `FROM x LATERAL VIEW POSEXPLODE(y) t AS pos, col` has the following semantics:
            // - The first column is the position and the rest (1 for array, 2 for maps) are the exploded values
            // - The position is 0-based whereas WITH ORDINALITY is 1-based
            // For that matter, we must (1) subtract 1 from the ORDINALITY position and (2) rearrange the columns accordingly, returning:
            // `FROM x CROSS JOIN LATERAL (SELECT pos - 1 AS pos, col FROM UNNEST(y) WITH ORDINALITY AS t(col, pos))
            List<Expression> columns = alias.exprs(Args.COLUMNS);
            Expression pos = columns.get(0);
            List<Expression> cols = new ArrayList<>(columns.subList(1, columns.size()));

            List<Object> selects = new ArrayList<>();
            selects.add(Exp.alias(binop(ExprType.SUB, pos, 1), pos));
            selects.addAll(cols);
            List<Expression> aliasColumns = new ArrayList<>(cols);
            aliasColumns.add(pos);
            Expression lateralSubquery = Exp.select(selects.toArray()).from(
                    Expression.of(ExprType.UNNEST,
                            Args.EXPRESSIONS, List.of(thisExpr.getThis()),
                            Args.OFFSET, Boolean.TRUE,
                            Args.ALIAS, Expression.of(ExprType.TABLE_ALIAS,
                                    Args.THIS, alias.get(Args.THIS), Args.COLUMNS, aliasColumns)),
                    true);

            crossJoinExpr = Expression.of(ExprType.LATERAL, Args.THIS, lateralSubquery.subquery(null, true));
        } else if (thisExpr != null && thisExpr.is(ExprType.EXPLODE)) {
            crossJoinExpr = Expression.of(ExprType.UNNEST,
                    Args.EXPRESSIONS, List.of(thisExpr.getThis()),
                    Args.ALIAS, alias);
        }

        if (crossJoinExpr != null) {
            return self.sql(Expression.of(ExprType.JOIN, Args.THIS, crossJoinExpr, Args.KIND, "cross"));
        }

        return self.lateralSql(expression);
    }

    public static String timestampdiffSql(Generator self, Expression expression) {
        return self.func("TIMESTAMPDIFF", expression.get(Args.UNIT), expression.getExpression(), expression.getThis());
    }

    /** {@code no_make_interval_sql(self, expression, sep)}. */
    public static String noMakeIntervalSql(Generator self, Expression expression, String sep) {
        List<Object> args = new ArrayList<>();
        for (Map.Entry<Arg, Object> entry : expression.argEntries()) {
            Object value = entry.getValue();
            if (value instanceof Expression v && v.is(ExprType.KWARG)) {
                value = v.getExpression();
            }

            args.add((value instanceof Expression v ? pyStr(v) : String.valueOf(value)) + " " + entry.getKey().name());
        }

        return "INTERVAL '" + self.formatArgsSep(sep, args.toArray()) + "'";
    }

    /** {@code no_make_interval_sql(self, expression)} ({@code sep=", "}). */
    public static String noMakeIntervalSql(Generator self, Expression expression) {
        return noMakeIntervalSql(self, expression, ", ");
    }

    public static String lengthOrCharLengthSql(Generator self, Expression expression) {
        String lengthFunc = expression.has(Args.BINARY) ? "LENGTH" : "CHAR_LENGTH";
        return self.func(lengthFunc, expression.getThis());
    }

    /** {@code groupconcat_sql(self, expression, func_name, sep, within_group, on_overflow)}. */
    public static String groupconcatSql(Generator self, Expression expression, String funcName, String sep,
            boolean withinGroup, boolean onOverflow) {
        Expression thisExpr = expression.getThis();
        Object separatorValue = first(expression.get(Args.SEPARATOR),
                sep != null && !sep.isEmpty() ? Exp.string(sep) : null);
        String separator = self.sql(separatorValue);

        String onOverflowSql = self.sql(expression, Args.ON_OVERFLOW);
        onOverflowSql = onOverflow && !onOverflowSql.isEmpty() ? " ON OVERFLOW " + onOverflowSql : "";

        Expression limit;
        if (thisExpr != null && thisExpr.is(ExprType.LIMIT) && thisExpr.getThis() != null) {
            limit = thisExpr;
            thisExpr = limit.getThis().pop();
        } else {
            limit = null;
        }

        Expression order = thisExpr.find(ExprType.ORDER);

        if (order != null && order.getThis() != null) {
            thisExpr = order.getThis().pop();
        }

        String args = self.formatArgs(thisExpr,
                !separator.isEmpty() || !onOverflowSql.isEmpty() ? separator + onOverflowSql : null);

        Expression listagg = Expression.of(ExprType.ANONYMOUS, Args.THIS, funcName, Args.EXPRESSIONS, List.of(args));

        String modifiers = self.sql(limit);

        if (order != null) {
            if (withinGroup) {
                listagg = Expression.of(ExprType.WITHIN_GROUP, Args.THIS, listagg, Args.EXPRESSION, order);
            } else {
                modifiers = self.sql(order) + modifiers;
            }
        }

        if (!modifiers.isEmpty()) {
            listagg.set(Args.EXPRESSIONS, List.of(args + modifiers));
        }

        return self.sql(listagg);
    }

    /** {@code groupconcat_sql(self, expression)} with all defaults ({@code func_name="LISTAGG"}, {@code sep=","}). */
    public static String groupconcatSql(Generator self, Expression expression) {
        return groupconcatSql(self, expression, "LISTAGG", ",", true, false);
    }

    public static String nthValueFromSql(Generator self, Expression expression) {
        String thisSql = self.func("NTH_VALUE", expression.getThis(), expression.get(Args.OFFSET));
        Object fromFirst = expression.get(Args.FROM_FIRST);

        if (fromFirst == null) {
            return thisSql;
        }

        return thisSql + " FROM " + (Expression.truthy(fromFirst) ? "FIRST" : "LAST");
    }

    /**
     * Generates SQL for Getbit according to DuckDB and Postgres, transpiling it if either the zero
     * index corresponds to the least-significant bit or the input type is an integer value.
     */
    public static String getbitSql(Generator self, Expression expression) {
        Expression value = expression.getThis();
        Expression position = expression.getExpression();

        List<Object> intTypes = new ArrayList<>(DataTypes.SIGNED_INTEGER_TYPES);
        intTypes.addAll(DataTypes.UNSIGNED_INTEGER_TYPES);
        if (!expression.has(Args.ZERO_IS_MSB) && expression.isType(intTypes.toArray())) {
            // Use bitwise operations: (value >> position) & 1
            Expression shifted = Expression.of(ExprType.BITWISE_RIGHT_SHIFT, Args.THIS, value, Args.EXPRESSION, position);
            Expression masked = Expression.of(ExprType.BITWISE_AND, Args.THIS, shifted, Args.EXPRESSION, Exp.number(1));
            return self.sql(masked);
        }

        return self.func("GET_BIT", value, position);
    }

    /** {@code jarowinkler_similarity(func)(self, expression)}. */
    public static String jarowinklerSimilarity(Generator self, Expression expression, String func) {
        Expression thisExpr = expression.getThis();
        Expression expr = expression.getExpression();
        if (expression.has(Args.CASE_INSENSITIVE)) {
            thisExpr = Expression.of(ExprType.UPPER, Args.THIS, thisExpr);
            expr = Expression.of(ExprType.UPPER, Args.THIS, expr);
        }

        return self.func(func, thisExpr, expr);
    }

    // ------------------------------------------------------------------------------------------
    // Parser builders

    /**
     * {@code build_formatted_time(exp_class, dialect_override, default)(args, dialect)}: builds a time
     * expression whose format is converted with {@code dialectName}'s (or, if null, the parsing
     * dialect's) TIME_MAPPING. {@code defaultFmt} is {@code Boolean.TRUE} (the target dialect's
     * TIME_FORMAT), a quoted format string, or null.
     */
    public static Expression buildFormattedTime(ExprType expClass, String dialectName, Object defaultFmt,
            List<Expression> args, Dialect dialect) {
        Dialect targetDialect = dialectName != null ? resolveDialect(dialectName) : dialect;

        Object fmt = Py.seqGet(args, 1);
        if (!Expression.truthy(fmt)) {
            fmt = Boolean.TRUE.equals(defaultFmt)
                    ? targetDialect.data().TIME_FORMAT
                    : (Expression.truthy(defaultFmt) ? defaultFmt : null);
        }

        return Expression.of(expClass, Args.THIS, Py.seqGet(args, 0), Args.FORMAT, formatTimeWith(targetDialect, fmt));
    }

    /** {@code build_formatted_time(exp_class, dialect_override)(args, dialect)} ({@code default=None}). */
    public static Expression buildFormattedTime(ExprType expClass, String dialectName, List<Expression> args, Dialect dialect) {
        return buildFormattedTime(expClass, dialectName, null, args, dialect);
    }

    /**
     * {@code build_date_delta(exp_class, unit_mapping, default_unit, supports_timezone)(args)}.
     */
    public static Expression buildDateDelta(ExprType expClass, Map<String, String> unitMapping, String defaultUnit,
            boolean supportsTimezone, List<Expression> args, Dialect dialect) {
        boolean unitBased = args.size() >= 3;
        boolean hasTimezone = args.size() == 4;
        Expression thisExpr = unitBased ? args.get(2) : Py.seqGet(args, 0);
        Expression unit = null;
        if (unitBased || (defaultUnit != null && !defaultUnit.isEmpty())) {
            unit = unitBased ? args.get(0) : Exp.string(defaultUnit);
            if (unitMapping != null && !unitMapping.isEmpty()) {
                unit = Exp.var(unitMapping.getOrDefault(Py.lower(unit.name()), unit.name()));
            }
        }
        Expression expression = Expression.of(expClass,
                Args.THIS, thisExpr, Args.EXPRESSION, Py.seqGet(args, 1), Args.UNIT, unit);
        if (supportsTimezone && hasTimezone) {
            expression.set(Args.ZONE, args.get(args.size() - 1));
        }
        return expression;
    }

    /** {@code build_date_delta(exp_class, unit_mapping)(args)} ({@code default_unit="DAY"}). */
    public static Expression buildDateDelta(ExprType expClass, Map<String, String> unitMapping, List<Expression> args, Dialect dialect) {
        return buildDateDelta(expClass, unitMapping, "DAY", false, args, dialect);
    }

    /** {@code build_date_delta(exp_class)(args)}. */
    public static Expression buildDateDelta(ExprType expClass, List<Expression> args, Dialect dialect) {
        return buildDateDelta(expClass, null, "DAY", false, args, dialect);
    }

    /**
     * {@code build_date_delta_with_interval(expression_class, default_unit)(args)}; returns null for
     * fewer than two args.
     */
    public static Expression buildDateDeltaWithInterval(ExprType expressionClass, String defaultUnit,
            List<Expression> args, Dialect dialect) {
        if (args.size() < 2) {
            return null;
        }

        Expression interval = args.get(1);

        if (interval == null || !interval.is(ExprType.INTERVAL)) {
            if (defaultUnit == null) {
                throw new ParseError("INTERVAL expression expected but got '" + pyStr(interval) + "'");
            }
            return Expression.of(expressionClass,
                    Args.THIS, args.get(0), Args.EXPRESSION, interval, Args.UNIT, Exp.string(defaultUnit));
        }

        return Expression.of(expressionClass,
                Args.THIS, args.get(0), Args.EXPRESSION, interval.getThis(), Args.UNIT, unitToStr(interval));
    }

    /** {@code build_date_delta_with_interval(expression_class)(args)} ({@code default_unit=None}). */
    public static Expression buildDateDeltaWithInterval(ExprType expressionClass, List<Expression> args, Dialect dialect) {
        return buildDateDeltaWithInterval(expressionClass, null, args, dialect);
    }

    /** {@code date_trunc_to_time(args)}. */
    public static Expression dateTruncToTime(List<Expression> args, Dialect dialect) {
        Expression unit = Py.seqGet(args, 0);
        Expression thisExpr = Py.seqGet(args, 1);

        if (thisExpr != null && thisExpr.is(ExprType.CAST) && thisExpr.isType(DType.DATE)) {
            return Expression.of(ExprType.DATE_TRUNC, Args.UNIT, unit, Args.THIS, thisExpr);
        }
        return Expression.of(ExprType.TIMESTAMP_TRUNC, Args.THIS, thisExpr, Args.UNIT, unit);
    }

    /** {@code binary_from_function(expr_type)(args)}. */
    public static Expression binaryFromFunction(ExprType exprType, List<Expression> args, Dialect dialect) {
        return Expression.of(exprType, Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION, Py.seqGet(args, 1));
    }

    /** {@code build_timestamp_trunc(args)}: DATE_TRUNC in Doris, Postgres and StarRocks. */
    public static Expression buildTimestampTrunc(List<Expression> args, Dialect dialect) {
        return Expression.of(ExprType.TIMESTAMP_TRUNC,
                Args.THIS, Py.seqGet(args, 1), Args.UNIT, Py.seqGet(args, 0), Args.ZONE, Py.seqGet(args, 2));
    }

    /**
     * {@code build_trunc(args, dialect, date_trunc_unabbreviate, default_date_trunc_unit,
     * date_trunc_requires_part, fractions_supported)}: builder for dialects with overloaded TRUNC
     * (Oracle, Snowflake, etc). Uses type annotation to distinguish date vs numeric truncation and
     * returns Anonymous if the type cannot be determined.
     */
    public static Expression buildTrunc(boolean dateTruncUnabbreviate, String defaultDateTruncUnit,
            boolean dateTruncRequiresPart, boolean fractionsSupported, List<Expression> args, Dialect dialect) {
        Expression thisExpr = Py.seqGet(args, 0);
        Expression second = Py.seqGet(args, 1);

        if (thisExpr != null && thisExpr.dataType() == null) {
            thisExpr = TypeAnnotator.annotateTypes(thisExpr, dialect);
        }
        if (second != null && second.dataType() == null) {
            second = TypeAnnotator.annotateTypes(second, dialect);
        }

        boolean hasDefaultUnit = defaultDateTruncUnit != null && !defaultDateTruncUnit.isEmpty();

        // Date truncation
        if ((thisExpr != null && thisExpr.isType(DataTypes.TEMPORAL_TYPES.toArray()) && (second != null || hasDefaultUnit))
                || (second != null && second.isType(DataTypes.TEXT_TYPES.toArray()))) {
            Expression unit = second != null ? second : Exp.string(defaultDateTruncUnit);
            return Exp.dateTrunc(unit, thisExpr, dateTruncUnabbreviate);
        }

        // Numeric truncation
        if ((thisExpr != null && thisExpr.isType(DataTypes.NUMERIC_TYPES.toArray()))
                || (second != null && second.isType(DataTypes.NUMERIC_TYPES.toArray()))
                || (!dateTruncRequiresPart && second == null)) {
            return Expression.of(ExprType.TRUNC,
                    Args.THIS, thisExpr, Args.DECIMALS, second, Args.FRACTIONS_SUPPORTED, fractionsSupported);
        }

        return Exp.anonymous("TRUNC", args);
    }

    /** {@code build_trunc(args, dialect)} with all defaults. */
    public static Expression buildTrunc(List<Expression> args, Dialect dialect) {
        return buildTrunc(true, null, true, false, args, dialect);
    }

    /** {@code isnull_to_is_null(args)}. */
    public static Expression isnullToIsNull(List<Expression> args, Dialect dialect) {
        return Expression.of(ExprType.PAREN, Args.THIS,
                Expression.of(ExprType.IS, Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION, Exp.nul()));
    }

    /**
     * {@code build_json_extract_path(expr_type, zero_based_indexing, arrow_req_json_type,
     * json_type)(args)}. Like sqlglot, this truncates {@code args} (which must be mutable) to two
     * items when it builds a JSON path, so the arity validation passes.
     */
    public static Expression buildJsonExtractPath(ExprType exprType, boolean zeroBasedIndexing, boolean arrowReqJsonType,
            String jsonType, List<Expression> args, Dialect dialect) {
        List<Expression> segments = new ArrayList<>();
        segments.add(Expression.of(ExprType.JSON_PATH_ROOT));
        for (int i = 1; i < args.size(); i++) {
            Expression arg = args.get(i);
            if (arg == null || !arg.is(ExprType.LITERAL)) {
                // We use the fallback parser because we can't really transpile non-literals safely
                List<Expression> rest = args.size() > 2 ? new ArrayList<>(args.subList(2, args.size())) : null;
                return Expression.of(exprType,
                        Args.THIS, Py.seqGet(args, 0),
                        Args.EXPRESSION, Py.seqGet(args, 1),
                        Args.EXPRESSIONS, rest);
            }

            String text = arg.name();
            if (Py.isInt(text) && (!arrowReqJsonType || !arg.isString())) {
                segments.add(Expression.of(ExprType.JSON_PATH_SUBSCRIPT,
                        Args.THIS, pyInt(text, zeroBasedIndexing ? 0 : -1)));
            } else {
                segments.add(Expression.of(ExprType.JSON_PATH_KEY, Args.THIS, text));
            }
        }

        // This is done to avoid failing in the expression validator due to the arg count
        if (args.size() > 2) {
            args.subList(2, args.size()).clear();
        }

        Map<Arg, Object> kwargs = new LinkedHashMap<>();
        kwargs.put(Args.THIS, Py.seqGet(args, 0));
        kwargs.put(Args.EXPRESSION, Expression.of(ExprType.JSON_PATH, Args.EXPRESSIONS, segments));

        boolean isJsonb = exprType.isA(ExprType.JSONB_EXTRACT) || exprType.isA(ExprType.JSONB_EXTRACT_SCALAR);
        if (!isJsonb) {
            kwargs.put(Args.ONLY_JSON_TYPES, arrowReqJsonType);
        }

        if (jsonType != null) {
            kwargs.put(Args.JSON_TYPE, jsonType);
        }

        return Expression.of(exprType, kwargs);
    }

    /** {@code build_json_extract_path(expr_type)(args)} with all defaults. */
    public static Expression buildJsonExtractPath(ExprType exprType, List<Expression> args, Dialect dialect) {
        return buildJsonExtractPath(exprType, true, false, null, args, dialect);
    }

    /** {@code build_timestamp_from_parts(args)}. */
    public static Expression buildTimestampFromParts(List<Expression> args, Dialect dialect) {
        if (args.size() == 2) {
            // Other dialects don't have the TIMESTAMP_FROM_PARTS(date, time) concept,
            // so we parse this into Anonymous for now instead of introducing complexity
            return Exp.anonymous("TIMESTAMP_FROM_PARTS", args);
        }

        return Exp.fromArgList(ExprType.TIMESTAMP_FROM_PARTS, args);
    }

    /** {@code build_like(expr_type, not_like)(args)}. */
    public static Expression buildLike(ExprType exprType, boolean notLike, List<Expression> args, Dialect dialect) {
        Expression likeExpr = Expression.of(exprType, Args.THIS, Py.seqGet(args, 0), Args.EXPRESSION, Py.seqGet(args, 1));

        Expression escape = Py.seqGet(args, 2);
        if (escape != null) {
            likeExpr = Expression.of(ExprType.ESCAPE, Args.THIS, likeExpr, Args.EXPRESSION, escape);
        }

        if (notLike) {
            likeExpr = Expression.of(ExprType.NOT, Args.THIS, likeExpr);
        }

        return likeExpr;
    }

    /** {@code build_like(expr_type)(args)} ({@code not_like=False}). */
    public static Expression buildLike(ExprType exprType, List<Expression> args, Dialect dialect) {
        return buildLike(exprType, false, args, dialect);
    }

    /** {@code build_regexp_extract(expr_type)(args, dialect)}. */
    public static Expression buildRegexpExtract(ExprType exprType, List<Expression> args, Dialect dialect) {
        // The "position" argument specifies the index of the string character to start matching from.
        // `null_if_pos_overflow` reflects the dialect's behavior when position is greater than the string
        // length. If true, returns NULL. If false, returns an empty string. `null_if_pos_overflow` is
        // only needed for exp.RegexpExtract - exp.RegexpExtractAll always returns an empty array if
        // position overflows.
        Expression group = Py.seqGet(args, 2);
        Expression e = Expression.of(exprType,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.GROUP, group != null ? group : Exp.number(dialect.data().REGEXP_EXTRACT_DEFAULT_GROUP),
                Args.PARAMETERS, Py.seqGet(args, 3));
        if (exprType == ExprType.REGEXP_EXTRACT) {
            e.set(Args.NULL_IF_POS_OVERFLOW, dialect.data().REGEXP_EXTRACT_POSITION_OVERFLOW_RETURNS_NULL);
        }
        return e;
    }

    /** {@code build_timetostr_or_tochar(args, dialect)}. */
    public static Expression buildTimetostrOrTochar(List<Expression> args, Dialect dialect) {
        if (args.size() == 2) {
            Expression thisExpr = args.get(0);
            if (thisExpr.dataType() == null) {
                TypeAnnotator.annotateTypes(thisExpr, dialect);
            }

            if (thisExpr.isType(DataTypes.TEMPORAL_TYPES.toArray())) {
                return buildFormattedTime(ExprType.TIME_TO_STR, null, Boolean.TRUE, args, dialect);
            }
        }

        return Exp.fromArgList(ExprType.TO_CHAR, args);
    }

    /** {@code build_replace_with_optional_replacement(args)}. */
    public static Expression buildReplaceWithOptionalReplacement(List<Expression> args, Dialect dialect) {
        Expression replacement = Py.seqGet(args, 2);
        return Expression.of(ExprType.REPLACE,
                Args.THIS, Py.seqGet(args, 0),
                Args.EXPRESSION, Py.seqGet(args, 1),
                Args.REPLACEMENT, replacement != null ? replacement : Exp.string(""));
    }

    /**
     * {@code build_default_decimal_type(precision, scale)(dtype)}: a DECIMAL(precision[, scale]) type
     * for parameterless DECIMAL types when {@code precision} is set.
     */
    public static Expression buildDefaultDecimalType(Integer precision, Integer scale, Expression dtype) {
        if (!dtype.getExpressions().isEmpty() || precision == null) {
            return dtype;
        }

        // exp.DataType.from_str(f"DECIMAL({precision}[, {scale}])")
        List<Expression> params = new ArrayList<>();
        params.add(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.number(precision)));
        if (scale != null) {
            params.add(Expression.of(ExprType.DATA_TYPE_PARAM, Args.THIS, Exp.number(scale)));
        }
        return Expression.of(ExprType.DATA_TYPE,
                Args.THIS, DType.DECIMAL, Args.EXPRESSIONS, params, Args.NESTED, Boolean.FALSE);
    }

    // ------------------------------------------------------------------------------------------
    // Pure helpers

    public static List<String> pivotColumnNames(Iterable<Expression> aggregations, Dialect dialect) {
        Dialect d = dialect != null ? dialect : Dialects.base();
        List<String> names = new ArrayList<>();
        for (Expression agg : aggregations) {
            if (agg.is(ExprType.ALIAS)) {
                names.add(agg.alias());
            } else {
                // This case corresponds to aggregations without aliases being used as suffixes
                // (e.g. col_avg(foo)). We need to unquote identifiers because they're going to
                // be quoted in the base parser's `_parse_pivot` method, due to `to_identifier`.
                // Otherwise, we'd end up with `col_avg(`foo`)` (notice the double quotes).
                Expression aggAllUnquoted = agg.transform(node -> node.is(ExprType.IDENTIFIER)
                        ? Expression.of(ExprType.IDENTIFIER, Args.THIS, node.name(), Args.QUOTED, Boolean.FALSE)
                        : node);
                names.add(d.generate(aggAllUnquoted, GeneratorOptions.builder().normalizeFunctions("lower").build()));
            }
        }
        return names;
    }

    public static boolean isParseJson(Expression expression) {
        return expression != null
                && (expression.is(ExprType.PARSE_JSON) || (expression.is(ExprType.CAST) && expression.isType(DType.JSON)));
    }

    /** {@code TsOrDsAdd.return_type}: {@code DataType.build(return_type or DATE)}. */
    private static Expression returnType(Expression tsOrDsAdd) {
        Object rt = tsOrDsAdd.get(Args.RETURN_TYPE);
        return DataTypes.build(Expression.truthy(rt) ? rt : DType.DATE);
    }

    public static Expression tsOrDsAddCast(Expression expression) {
        Expression thisExpr = expression.getThis().copy();

        Expression returnType = returnType(expression);
        if (returnType.isType(DType.DATE)) {
            // If we need to cast to a DATE, we cast to TIMESTAMP first to make sure we
            // can truncate timestamp strings, because some dialects can't cast them to DATE
            thisExpr = Exp.cast(thisExpr, DType.TIMESTAMP);
        }

        expression.getThis().replace(Exp.cast(thisExpr, returnType));
        return expression;
    }

    /** {@code unit_to_str(expression, default)}; a null or empty default means None. */
    public static Expression unitToStr(Expression expression, String default_) {
        Object unitValue = expression.get(Args.UNIT);
        if (!Expression.truthy(unitValue)) {
            return default_ != null && !default_.isEmpty() ? Exp.string(default_) : null;
        }
        Expression unit = (Expression) unitValue;

        if (unit.is(ExprType.WEEK_START)) {
            // WEEK(<day>) is BigQuery-only syntax, so it degrades to the plain WEEK unit. Unlike
            // Generator.weekstart_name, this can't warn about a changed week start (no generator
            // access here) - callers that need the warning should use weekstart_unit_to_str
            return Exp.string("WEEK");
        }

        if (unit.is(ExprType.PLACEHOLDER) || (unit.type() != ExprType.VAR && unit.type() != ExprType.LITERAL)) {
            return unit;
        }

        return Exp.string(unit.name());
    }

    public static Expression unitToStr(Expression expression) {
        return unitToStr(expression, "DAY");
    }

    /** {@code unit_to_var(expression, default)}; a null or empty default means None. */
    public static Expression unitToVar(Expression expression, String default_) {
        Object unitValue = expression.get(Args.UNIT);

        if (unitValue instanceof Expression unit
                && unit.is(ExprType.VAR, ExprType.PLACEHOLDER, ExprType.WEEK_START, ExprType.COLUMN)) {
            return unit;
        }

        String value = Expression.truthy(unitValue) ? ((Expression) unitValue).name() : default_;
        return value != null && !value.isEmpty() ? Expression.of(ExprType.VAR, Args.THIS, value) : null;
    }

    public static Expression unitToVar(Expression expression) {
        return unitToVar(expression, "DAY");
    }

    /** Converts a dialect's WEEK_OFFSET (days relative to Monday) to the ISO day number of its week start. */
    public static int weekOffsetToDow(int offset) {
        return Math.floorMod(offset, 7) + 1;
    }

    /**
     * The ISO 8601 week start day (Monday=1, Sunday=7) for a week-ish diff unit, e.g. BigQuery's
     * WEEK(&lt;day&gt;) or ISOWEEK, or null if it's not a week unit or the day is dynamic.
     */
    public static Integer weekUnitToDow(Expression unit) {
        if (unit != null && unit.is(ExprType.VAR)) {
            String name = Py.upper(unit.name());
            if (name.equals("WEEK") || name.equals("ISOWEEK")) {
                return 1;
            }
        }

        // Handle WeekStart expressions with explicit day
        if (unit != null && unit.is(ExprType.WEEK_START)) {
            return WEEK_START_DAY_TO_DOW.get(Py.upper(unit.name()));
        }

        return null;
    }

    /** {@code map_date_part(part, dialect)}; a null dialect means the base dialect. */
    public static Expression mapDatePart(Expression part, Dialect dialect) {
        Dialect d = dialect != null ? dialect : Dialects.base();
        String mapped = part != null && !(part.is(ExprType.COLUMN) && part.parts().size() != 1)
                ? d.data().DATE_PART_MAPPING.get(Py.upper(part.name()))
                : null;
        if (mapped != null && !mapped.isEmpty()) {
            return part.isString() ? Exp.string(mapped) : Exp.var(mapped);
        }

        return part;
    }

    /** {@code map_date_part(part)} with the base dialect. */
    public static Expression mapDatePart(Expression part) {
        return mapDatePart(part, null);
    }

    public static Expression regexpReplaceGlobalModifier(Expression expression) {
        Expression modifiers = expression.expr(Args.MODIFIERS);
        Object singleReplace = expression.get(Args.SINGLE_REPLACE);
        Expression occurrence = expression.expr(Args.OCCURRENCE);

        if (!Expression.truthy(singleReplace)
                && (occurrence == null || (occurrence.isInt() && ((BigInteger) occurrence.toPy()).signum() == 0))) {
            if (modifiers == null || modifiers.isString()) {
                // Append 'g' to the modifiers if they are not provided since
                // the semantics of REGEXP_REPLACE from the input dialect
                // is to replace all occurrences of the pattern.
                String value = modifiers == null ? "" : modifiers.name();
                modifiers = Exp.string(value + "g");
            }
        }

        return modifiers;
    }
}
