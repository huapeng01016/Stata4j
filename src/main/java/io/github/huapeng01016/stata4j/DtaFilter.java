package io.github.huapeng01016.stata4j;

import java.util.*;

/**
 * An observation filter such as {@code age >= 18 & (state == "CA" | !(income < 1000))}.
 *
 * <pre>
 * expr       := and ('|' and)*
 * and        := unary ('&amp;' unary)*
 * unary      := '!' unary | '(' expr ')' | comparison
 * comparison := name op constant
 * op         := '&lt;' | '&lt;=' | '&gt;' | '&gt;=' | '==' | '!='
 * constant   := number | missing | "string"
 * missing    := '.' | '.a' ... '.z'
 * </pre>
 *
 * <p>Numeric comparisons follow Stata: missing values are greater than every
 * number and ordered {@code . < .a < ... < .z}, so {@code x > 5} is true for
 * missing {@code x}, and {@code x < .} selects non-missing values. String
 * variables support only {@code ==} and {@code !=}, compared exactly.
 */
final class DtaFilter {

    enum Op {
        LT("<"), LE("<="), GT(">"), GE(">="), EQ("=="), NE("!=");

        final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }
    }

    private sealed interface Node permits Or, And, Not, Cmp {}

    private record Or(Node a, Node b) implements Node {}

    private record And(Node a, Node b) implements Node {}

    private record Not(Node a) implements Node {}

    /** {@code number} is the sort key of a numeric constant; {@code string} is set instead for a string literal. */
    private record Cmp(String var, Op op, double number, String string, int pos) implements Node {}

    /** Compiled form, evaluated against one observation's filter inputs. */
    private interface Pred {
        boolean test(double[] nums, Object[] strs);
    }

    private final String source;
    private final Node root;

    private DtaFilter(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /**
     * Parses a filter expression.
     *
     * @throws IllegalArgumentException with the position of the first syntax error
     */
    static DtaFilter parse(String expression) {
        Objects.requireNonNull(expression, "expression");
        Parser p = new Parser(expression);
        Node root = p.expr();
        p.skipSpace();
        if (p.pos < expression.length()) {
            throw p.error("Unexpected '" + expression.charAt(p.pos) + "'");
        }
        return new DtaFilter(expression, root);
    }

    @Override
    public String toString() {
        return source;
    }

    // Sort keys for numeric values

    /**
     * Key for a missing value: {@code k} is 0 for {@code .}, 1-26 for {@code .a}-{@code .z}.
     * Keys lie above every valid Stata number (all below 2^1023) and keep their order.
     */
    static double missingKey(int k) {
        return DtaMissing.DOUBLE * (1 + Math.min(Math.max(k, 0), 26) / 32.0);
    }

    static double floatMissingKey(float f) {
        if (Float.isNaN(f)) {
            return missingKey(0);
        }
        return missingKey((Float.floatToRawIntBits(f) - Float.floatToRawIntBits(DtaMissing.FLOAT)) >>> 11);
    }

    static double doubleMissingKey(double d) {
        if (Double.isNaN(d)) {
            return missingKey(0);
        }
        return missingKey((int) ((Double.doubleToRawLongBits(d) - Double.doubleToRawLongBits(DtaMissing.DOUBLE)) >>> 40));
    }

    // Binding to a file's variables

    /**
     * The filter resolved against a file's variables. Each variable the filter
     * uses gets a slot in the numeric or string input arrays passed to {@link #test}.
     */
    final class Bound {
        private final int[] numSlot;
        private final int[] strSlot;
        private final int numCount;
        private final int strCount;
        private final boolean usesStrL;
        private final Pred pred;

        private Bound(List<String> names, List<StataVarType> types) {
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < names.size(); i++) {
                index.put(names.get(i), i);
            }
            numSlot = new int[names.size()];
            strSlot = new int[names.size()];
            Arrays.fill(numSlot, -1);
            Arrays.fill(strSlot, -1);
            int[] counts = new int[2];
            boolean[] strl = new boolean[1];
            pred = compile(root, index, types, counts, strl);
            numCount = counts[0];
            strCount = counts[1];
            usesStrL = strl[0];
        }

        private Pred compile(Node node, Map<String, Integer> index, List<StataVarType> types,
                             int[] counts, boolean[] strl) {
            if (node instanceof Or or) {
                Pred a = compile(or.a(), index, types, counts, strl);
                Pred b = compile(or.b(), index, types, counts, strl);
                return (n, s) -> a.test(n, s) || b.test(n, s);
            }
            if (node instanceof And and) {
                Pred a = compile(and.a(), index, types, counts, strl);
                Pred b = compile(and.b(), index, types, counts, strl);
                return (n, s) -> a.test(n, s) && b.test(n, s);
            }
            if (node instanceof Not not) {
                Pred a = compile(not.a(), index, types, counts, strl);
                return (n, s) -> !a.test(n, s);
            }
            Cmp c = (Cmp) node;
            Integer var = index.get(c.var());
            if (var == null) {
                throw bindError(c, "Variable not found: " + c.var());
            }
            StataVarType type = types.get(var);
            if (type.isAlias()) {
                throw bindError(c, c.var() + " is an alias variable; it has no data to filter on");
            }
            if (c.string() == null) {
                if (!type.isNumeric()) {
                    throw bindError(c, c.var() + " is a string variable; compare it with a \"string\"");
                }
                if (numSlot[var] < 0) {
                    numSlot[var] = counts[0]++;
                }
                int slot = numSlot[var];
                double k = c.number();
                switch (c.op()) {
                    case LT: return (n, s) -> n[slot] < k;
                    case LE: return (n, s) -> n[slot] <= k;
                    case GT: return (n, s) -> n[slot] > k;
                    case GE: return (n, s) -> n[slot] >= k;
                    case EQ: return (n, s) -> n[slot] == k;
                    default: return (n, s) -> n[slot] != k;
                }
            }
            if (!type.isString()) {
                throw bindError(c, c.var() + " is numeric; compare it with a number");
            }
            if (c.op() != Op.EQ && c.op() != Op.NE) {
                throw bindError(c, "String variable " + c.var() + " supports only == and !=, not " + c.op().symbol);
            }
            if (strSlot[var] < 0) {
                strSlot[var] = counts[1]++;
            }
            strl[0] |= type.isStrL();
            int slot = strSlot[var];
            String literal = c.string();
            // A binary strL (byte[]) never equals a string literal.
            return c.op() == Op.EQ ? (n, s) -> literal.equals(s[slot]) : (n, s) -> !literal.equals(s[slot]);
        }

        private IllegalArgumentException bindError(Cmp c, String message) {
            return new IllegalArgumentException(message + " (at position " + c.pos() + " in filter: " + source + ")");
        }

        /** Slot of file variable {@code var} in the numeric inputs, or -1. */
        int numSlot(int var) {
            return numSlot[var];
        }

        /** Slot of file variable {@code var} in the string inputs, or -1. */
        int strSlot(int var) {
            return strSlot[var];
        }

        int numCount() {
            return numCount;
        }

        int strCount() {
            return strCount;
        }

        /** True if the filter reads a strL variable, whose values are known only after {@code <strls>}. */
        boolean usesStrL() {
            return usesStrL;
        }

        boolean test(double[] nums, Object[] strs) {
            return pred.test(nums, strs);
        }
    }

    /**
     * Resolves variable names and checks types against a file.
     *
     * @throws IllegalArgumentException for an unknown or alias variable, or a comparison
     *                                  that doesn't fit the variable's type
     */
    Bound bind(List<String> names, List<StataVarType> types) {
        return new Bound(names, types);
    }

    // Parsing

    private static final class Parser {
        private final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        Node expr() {
            Node left = and();
            while (accept("|")) {
                left = new Or(left, and());
            }
            return left;
        }

        private Node and() {
            Node left = unary();
            while (accept("&")) {
                left = new And(left, unary());
            }
            return left;
        }

        private Node unary() {
            skipSpace();
            if (peek() == '!' && !s.startsWith("!=", pos)) {
                pos++;
                return new Not(unary());
            }
            if (accept("(")) {
                Node inner = expr();
                if (!accept(")")) {
                    throw error("Expected ')'");
                }
                return inner;
            }
            return comparison();
        }

        private Node comparison() {
            skipSpace();
            int start = pos;
            String var = name();
            if (var == null) {
                throw error("Expected a variable name, '(' or '!'");
            }
            skipSpace();
            Op op = op();
            if (op == null) {
                throw error("Expected a comparison operator (<, <=, >, >=, ==, !=) after " + var);
            }
            skipSpace();
            if (peek() == '"') {
                return new Cmp(var, op, 0, string(), start + 1);
            }
            return new Cmp(var, op, number(), null, start + 1);
        }

        private String name() {
            int start = pos;
            if (pos < s.length()) {
                int c = s.codePointAt(pos);
                if (c == '_' || Character.isLetter(c)) {
                    pos += Character.charCount(c);
                    while (pos < s.length()) {
                        c = s.codePointAt(pos);
                        if (c != '_' && !Character.isLetterOrDigit(c)) {
                            break;
                        }
                        pos += Character.charCount(c);
                    }
                }
            }
            return pos > start ? s.substring(start, pos) : null;
        }

        private Op op() {
            for (Op op : new Op[] {Op.LE, Op.GE, Op.EQ, Op.NE, Op.LT, Op.GT}) { // two-character operators first
                if (s.startsWith(op.symbol, pos)) {
                    pos += op.symbol.length();
                    return op;
                }
            }
            return null;
        }

        /** A number, or a missing value {@code .} / {@code .a}-{@code .z}; returns its sort key. */
        private double number() {
            int start = pos;
            if (peek() == '.' && !(pos + 1 < s.length() && Character.isDigit(s.charAt(pos + 1)))) {
                pos++;
                char c = peek();
                if (c >= 'a' && c <= 'z') {
                    pos++;
                    return missingKey(c - 'a' + 1);
                }
                return missingKey(0);
            }
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String text = s.substring(start, pos);
            double value;
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                pos = start;
                throw error("Expected a number, a missing value (. or .a-.z) or a \"string\"");
            }
            if (!(Math.abs(value) < DtaMissing.DOUBLE)) {
                pos = start;
                throw error(text + " is outside the range of Stata numbers");
            }
            return value;
        }

        /** A double-quoted string; {@code \"} and {@code \\} are escapes. */
        private String string() {
            int start = pos;
            pos++; // opening quote
            StringBuilder b = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\' && pos < s.length() && (s.charAt(pos) == '"' || s.charAt(pos) == '\\')) {
                    c = s.charAt(pos++);
                }
                b.append(c);
            }
            pos = start;
            throw error("Unterminated string");
        }

        private boolean accept(String token) {
            skipSpace();
            if (s.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + (pos + 1) + " in filter: " + s);
        }
    }
}
