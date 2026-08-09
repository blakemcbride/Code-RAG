package org.kissweb.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Identifier-aware tokenizer for the lexical half of hybrid search.
 * <br><br>
 * This lives in precompiled/ rather than in the indexer because the exact same
 * tokenization must be applied when <b>indexing</b> a chunk (RAGIndexer, Groovy)
 * and when <b>querying</b> (RAGSearch, Java). If the two ever diverge, the
 * lexical leg silently stops matching and nothing fails loudly — so there is
 * one implementation and both call it.
 * <br><br>
 * PostgreSQL's own parser is the wrong tool here: it does not split
 * {@code camelCase}, and the {@code english} dictionary would stem and
 * stopword-strip identifiers into mush ({@code Users} → {@code user},
 * {@code as} dropped entirely). We tokenize here, where the rules are explicit
 * and testable, and hand Postgres a plain word list under the {@code simple}
 * configuration, which does nothing but lowercase.
 * <br><br>
 * Rules: split on non-alphanumerics, then at camelCase and letter/digit
 * boundaries; lowercase; emit <b>both</b> the whole identifier and its parts, so
 * {@code getUserName} is found by "user name" and by "getUserName" alike.
 */
public final class RAGTokenizer {

    /** Tokens longer than this are almost certainly minified junk or base64. */
    private static final int MAX_TOKEN_LEN = 64;

    private RAGTokenizer() {
        // utility class
    }

    /** Tokenize to a space-separated word list suitable for {@code to_tsvector('simple', ...)}. */
    public static String tokenize(String s) {
        return String.join(" ", tokens(s));
    }

    /** Tokenize to an ordered, de-duplicated token set. */
    public static Set<String> tokens(String s) {
        final Set<String> out = new LinkedHashSet<>();
        if (s == null || s.isEmpty())
            return out;
        final int n = s.length();
        int i = 0;
        while (i < n) {
            // Skip anything that is not a letter or digit.
            while (i < n && !Character.isLetterOrDigit(s.charAt(i)))
                i++;
            if (i >= n)
                break;
            final int start = i;
            while (i < n && Character.isLetterOrDigit(s.charAt(i)))
                i++;
            final String raw = s.substring(start, i);
            if (raw.length() <= MAX_TOKEN_LEN)
                out.add(raw.toLowerCase());
            // Sub-tokens from camelCase / digit boundaries.
            final List<String> parts = splitCamel(raw);
            if (parts.size() > 1) {
                for (String p : parts) {
                    // Single characters carry no signal and bloat the index.
                    if (p.length() > 1 && p.length() <= MAX_TOKEN_LEN)
                        out.add(p.toLowerCase());
                }
            }
        }
        return out;
    }

    /**
     * Split an identifier at camelCase and letter/digit boundaries.
     * {@code parseHTTPResponse2} → [parse, HTTP, Response, 2].
     */
    static List<String> splitCamel(String s) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            boolean boundary = false;
            if (i > 0) {
                final char prev = s.charAt(i - 1);
                if (Character.isUpperCase(ch) && !Character.isUpperCase(prev)) {
                    // lower/digit -> upper:  parseHttp -> parse | Http
                    boundary = true;
                } else if (Character.isUpperCase(ch) && Character.isUpperCase(prev)
                        && i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1))) {
                    // run of caps followed by a word:  HTTPResponse -> HTTP | Response
                    boundary = true;
                } else if (Character.isDigit(ch) != Character.isDigit(prev)) {
                    boundary = true;
                }
            }
            if (boundary && cur.length() > 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(ch);
        }
        if (cur.length() > 0)
            parts.add(cur.toString());
        return parts;
    }

    /**
     * Terms dropped from QUERIES (never from the index).
     * <br><br>
     * The {@code simple} text-search configuration does no stopword removal by
     * design — we need it to leave identifiers alone. The cost is that a
     * natural-language query like "where do we enforce the user inactivity
     * timeout" contributes {@code where}, {@code do}, {@code we}, {@code the}
     * as OR terms, and every chunk in the corpus matches them. Since
     * {@code ts_rank_cd} carries no IDF weighting, those chunks then crowd out
     * genuine matches.
     * <br><br>
     * Two groups: English function words and interrogatives, plus the handful
     * of Java/JS keywords that appear in essentially every chunk and therefore
     * carry no discriminating power. Compound identifiers are unaffected —
     * dropping {@code get} still leaves {@code getenvironment} to match on.
     * <br><br>
     * The index keeps every token, so lowering this list never requires a
     * reindex.
     */
    private static final Set<String> QUERY_STOPWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            // English function words / interrogatives
            "the", "and", "are", "for", "from", "into", "its", "not", "our", "out",
            "that", "their", "them", "then", "there", "these", "they", "this", "was",
            "were", "what", "when", "where", "which", "while", "who", "why", "will",
            "with", "you", "your", "how", "does", "did", "has", "have", "had", "been",
            "but", "can", "could", "should", "would", "any", "all", "some", "one",
            "two", "way", "via", "per", "use", "used", "uses", "using", "make",
            "makes", "made", "need", "needs", "want", "wants", "let", "lets", "get",
            "gets", "put", "puts", "set", "sets", "give", "take", "come", "goes",
            "only", "also", "just", "than", "such", "each", "own", "same", "over",
            "under", "about", "after", "before", "between", "both", "more", "most",
            // ubiquitous language keywords — present in nearly every chunk
            "public", "private", "protected", "static", "final", "void", "return",
            "new", "null", "true", "false", "import", "package", "class", "function",
            "const", "var", "let", "def", "end", "com", "org"));

    /**
     * Build an OR'd tsquery expression from free text, for
     * {@code to_tsquery('simple', ?)}.
     * <br><br>
     * OR rather than AND: a natural-language query shares only a couple of terms
     * with the target chunk, and AND would return nothing at all.
     * {@code ts_rank_cd} then does the discriminating, rewarding chunks that
     * match more terms and match them closer together — but only once the
     * non-discriminating terms have been removed by {@link #QUERY_STOPWORDS}.
     * <br><br>
     * Output is safe to bind: tokens are {@code [a-z0-9]+} by construction, so
     * no tsquery metacharacter can survive.
     *
     * @param text     the raw query
     * @param maxTerms cap on term count, to bound worst-case query cost
     * @return the expression, or "" when the query has no usable terms
     */
    public static String toTsQueryOr(String text, int maxTerms) {
        final Set<String> toks = tokens(text);
        final StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String t : toks) {
            if (used >= maxTerms)
                break;
            if (!isUsefulQueryTerm(t))
                continue;
            if (used > 0)
                sb.append(" | ");
            sb.append(t);
            used++;
        }
        return sb.toString();
    }

    /**
     * Whether a token discriminates enough to be worth searching for.
     * Short tokens are dropped because 1–2 character fragments (loop variables,
     * {@code i}, {@code id}, camelCase debris) match nearly everything.
     */
    private static boolean isUsefulQueryTerm(String t) {
        return t.length() >= 3 && !QUERY_STOPWORDS.contains(t);
    }
}
