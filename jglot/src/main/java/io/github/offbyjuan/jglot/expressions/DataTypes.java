package io.github.offbyjuan.jglot.expressions;

import java.util.EnumSet;
import java.util.Set;

/** sqlglot's {@code DataType} class-level helpers and type groups. */
public final class DataTypes {
    private DataTypes() {}

    private static Set<DType> of(DType... ts) {
        return java.util.Collections.unmodifiableSet(EnumSet.of(ts[0], ts));
    }

    public static final Set<DType> STRUCT_TYPES = of(DType.FILE, DType.NESTED, DType.OBJECT, DType.STRUCT, DType.UNION);
    public static final Set<DType> ARRAY_TYPES = of(DType.ARRAY, DType.LIST);
    public static final Set<DType> NESTED_TYPES = of(DType.FILE, DType.NESTED, DType.OBJECT, DType.STRUCT, DType.UNION,
            DType.ARRAY, DType.LIST, DType.MAP);
    public static final Set<DType> TEXT_TYPES = of(DType.CHAR, DType.NCHAR, DType.NVARCHAR, DType.TEXT, DType.TINYTEXT,
            DType.MEDIUMTEXT, DType.LONGTEXT, DType.VARCHAR, DType.NAME);
    public static final Set<DType> BINARY_TYPES = of(DType.BINARY, DType.VARBINARY, DType.TINYBLOB, DType.BLOB,
            DType.MEDIUMBLOB, DType.LONGBLOB);
    public static final Set<DType> SIGNED_INTEGER_TYPES = of(DType.BIGINT, DType.INT, DType.INT128, DType.INT256,
            DType.MEDIUMINT, DType.SMALLINT, DType.TINYINT);
    public static final Set<DType> UNSIGNED_INTEGER_TYPES = of(DType.UBIGINT, DType.UINT, DType.UINT128, DType.UINT256,
            DType.UMEDIUMINT, DType.USMALLINT, DType.UTINYINT);
    public static final Set<DType> INTEGER_TYPES = of(DType.BIGINT, DType.INT, DType.INT128, DType.INT256,
            DType.MEDIUMINT, DType.SMALLINT, DType.TINYINT, DType.UBIGINT, DType.UINT, DType.UINT128, DType.UINT256,
            DType.UMEDIUMINT, DType.USMALLINT, DType.UTINYINT, DType.BIT);
    public static final Set<DType> FLOAT_TYPES = of(DType.DOUBLE, DType.FLOAT);
    public static final Set<DType> REAL_TYPES = of(DType.DOUBLE, DType.FLOAT, DType.BIGDECIMAL, DType.DECIMAL,
            DType.DECIMAL32, DType.DECIMAL64, DType.DECIMAL128, DType.DECIMAL256, DType.DECFLOAT, DType.MONEY,
            DType.SMALLMONEY, DType.UDECIMAL, DType.UDOUBLE);
    public static final Set<DType> NUMERIC_TYPES;
    public static final Set<DType> TEMPORAL_TYPES = of(DType.DATE, DType.DATE32, DType.DATETIME, DType.DATETIME2,
            DType.DATETIME64, DType.SMALLDATETIME, DType.TIME, DType.TIMESTAMP, DType.TIMESTAMPNTZ, DType.TIMESTAMPLTZ,
            DType.TIMESTAMPTZ, DType.TIMESTAMP_MS, DType.TIMESTAMP_NS, DType.TIMESTAMP_S, DType.TIMETZ);

    static {
        EnumSet<DType> n = EnumSet.copyOf(INTEGER_TYPES);
        n.addAll(REAL_TYPES);
        NUMERIC_TYPES = java.util.Collections.unmodifiableSet(n);
    }

    /** {@code DType.into_expr()} / {@code DataType(this=dtype)}. */
    public static Expression of(DType dtype) {
        return Expression.of(ExprType.DATA_TYPE, Args.THIS, dtype);
    }

    /**
     * {@code DataType.build(dtype, dialect=..., udt=..., copy=...)}: {@code dtype} is a DType, a type
     * string (parsed with {@code dialect}, or the base dialect if null), an identifier/dot (with
     * {@code udt}) or an existing DATA_TYPE node.
     */
    public static Expression build(Object dtype, io.github.offbyjuan.jglot.dialects.Dialect dialect, boolean udt, boolean copy) {
        if (dtype instanceof String s) {
            return fromStr(s, dialect, udt);
        }
        if (dtype instanceof DType t) {
            return of(t);
        }
        if (dtype instanceof Expression e && e.is(ExprType.IDENTIFIER, ExprType.DOT) && udt) {
            return Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.USERDEFINED, Args.KIND, e);
        }
        if (dtype instanceof Expression e && e.is(ExprType.DATA_TYPE)) {
            return copy ? e.copy() : e;
        }
        throw new IllegalArgumentException("Invalid data type: " + typeName(dtype) + ". Expected str or DType");
    }

    private static String typeName(Object o) {
        if (o == null) {
            return "<class 'NoneType'>";
        }
        if (o instanceof Expression e) {
            return "<class 'sqlglot.expressions." + e.type().className() + "'>";
        }
        return "<class '" + o.getClass().getSimpleName() + "'>";
    }

    /** {@code DataType.from_str(dtype, dialect, udt)}: parses a type string into a DATA_TYPE node. */
    public static Expression fromStr(String dtype, io.github.offbyjuan.jglot.dialects.Dialect dialect, boolean udt) {
        if (dtype.toUpperCase(java.util.Locale.ROOT).equals("UNKNOWN")) {
            return of(DType.UNKNOWN);
        }
        try {
            return Exp.parseOne(dtype, dialect, io.github.offbyjuan.jglot.parser.ParserOptions.DEFAULT
                    .withErrorLevel(io.github.offbyjuan.jglot.errors.ErrorLevel.IGNORE), ExprType.DATA_TYPE);
        } catch (io.github.offbyjuan.jglot.errors.ParseError e) {
            if (udt) {
                return Expression.of(ExprType.DATA_TYPE, Args.THIS, DType.USERDEFINED, Args.KIND, dtype);
            }
            throw e;
        }
    }

    public static Expression build(Object dtype) {
        return build(dtype, null, false, true);
    }

    /** {@code DataType.is_type(*dtypes, check_nullable=...)} for a DATA_TYPE node. */
    public static boolean isType(Expression dataType, boolean checkNullable, Object... dtypes) {
        Object selfNullable = dataType.get(Args.NULLABLE);
        for (Object dtype : dtypes) {
            Expression other = dtype instanceof DType t ? of(t) : build(dtype, null, true, false);
            Object otherNullable = other.get(Args.NULLABLE);
            boolean matches;
            if (!other.getExpressions().isEmpty()
                    || (checkNullable && (Expression.truthy(selfNullable) || Expression.truthy(otherNullable)))
                    || dataType.thisValue() == DType.USERDEFINED
                    || other.thisValue() == DType.USERDEFINED) {
                matches = dataType.equals(other);
            } else {
                matches = dataType.thisValue() == other.thisValue();
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
