package io.github.lordship.shared;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The marks an author may put inside a clause body, and what they print as.
 *
 * <p>A body is plain text with four exceptions. Bold, italic and underline,
 * because the lease this system replaces uses all three inside sentences and
 * they carry meaning there -- "payment must be received by <b>midnight</b>" is
 * not decoration, it is the part a tenant is meant to see. And a rule, because
 * a line somebody writes on is a thing a lease needs and no amount of plain
 * text draws one that holds its width.
 *
 * <p>Three line-level blocks as well: a line starting {@code - } is a bullet,
 * {@code # } a numbered item, and {@code | a | b |} a table row. Their contents
 * go through the same inline scan, so they add places to put text, not markup.
 *
 * <p>Deliberately four. The allowlist is what makes rendering a stored body
 * safe, and it is safe because nothing in it takes a URL, a style, a class or
 * an event -- the one attribute there is is read as an integer and thrown away.
 * A mark that took any of those would turn a clause body into a way to put
 * arbitrary markup on a lease, and the office worker typing a sentence onto one
 * agreement is not the person who should be able to do that. Nothing gets added
 * here without that sentence still being true afterwards.
 *
 * <p>One parse, two callers. {@link #validate} is the save path and refuses
 * what an author got wrong; {@link #toHtml} is the render path and refuses
 * nothing, because by then the document exists and printing it oddly beats not
 * printing it. They walk the same scan, so what saves is what prints.
 */
public final class ClauseMarkup {

    private ClauseMarkup() {}

    /** The inline marks, by the tag an author types. */
    private static final Set<String> MARKS = Set.of("b", "i", "u");

    /**
     * How wide a rule may be asked to be, in characters.
     *
     * <p>Characters rather than millimetres because an author is writing in a
     * text box, not a page layout, and "about forty characters" is a thing they
     * can picture. It survives a font change too: CSS {@code ch} is the width of
     * a zero in whatever face the document ends up in, so a rule stays the same
     * share of the line rather than the same number of millimetres.
     *
     * <p>The cap is the margin. A rule wider than a line cannot wrap -- it is
     * one box -- so an uncapped width is a body that prints off the edge of the
     * paper, and the page it runs off is not one anybody checks.
     */
    public static final int MIN_RULE_WIDTH = 1;
    public static final int MAX_RULE_WIDTH = 90;

    /** What a scan found: text to print, a mark to open or close, or a rule. */
    private sealed interface Piece {
        record Text(String value) implements Piece {}
        record Open(String tag) implements Piece {}
        record Close(String tag) implements Piece {}
        record Rule(int width) implements Piece {}
    }

    /**
     * Refuses a body whose marks an author got wrong, before it is saved.
     *
     * <p>Wrong at save is a sentence somebody retypes. Wrong at generate is a
     * lease that prints with a stray {@code </b>} in the middle of it, found by
     * whoever reads the paper.
     */
    public static void validate(String body) {
        if (body == null) {
            return;
        }

        List<DomainProblem.Problem> problems = new ArrayList<>();
        Deque<String> open = new ArrayDeque<>();

        for (Piece piece : scan(body, problems)) {
            switch (piece) {
                case Piece.Open(String tag) -> {
                    if (open.contains(tag)) {
                        problems.add(DomainProblem.Problem.of("markup.already_open", tag(tag)));
                    }
                    open.push(tag);
                }
                case Piece.Close(String tag) -> {
                    if (open.isEmpty() || !open.peek().equals(tag)) {
                        problems.add(DomainProblem.Problem.of("markup.not_open", tag("/" + tag)));
                    } else {
                        open.pop();
                    }
                }
                default -> { }
            }
        }

        while (!open.isEmpty()) {
            problems.add(DomainProblem.Problem.of("markup.never_closed", tag(open.pop())));
        }

        // A table row with a cell more or less than the one above it prints a
        // column out of line with its heading.
        Integer width = null;
        for (String line : body.split("\\n", -1)) {
            if (kindOf(line) != Line.ROW) {
                width = kindOf(line) == Line.BLANK ? width : null;
                continue;
            }
            int cells = cells(line).size();
            if (width != null && cells != width) {
                problems.add(DomainProblem.Problem.of("markup.table_ragged", width, cells));
            }
            width = cells;
        }

        if (!problems.isEmpty()) {
            throw InvalidRequest.withDetails("clause.body_has_bad_markup", problems);
        }
    }

    /** The same check where the body arrives inside a patch map. */
    public static void validate(Map<String, Object> changes) {
        if (!changes.containsKey("body")) {
            return;
        }
        Object raw = changes.get("body");
        validate(raw == null ? null : String.valueOf(raw));
    }

    /**
     * A body as HTML: everything escaped, then the four marks let back through.
     *
     * <p>That order is the whole safety argument. Escaping first means a tenant
     * named "Smith &amp; Sons" prints as their name and a body that says
     * {@code <script>} prints those eight characters, and only then does a
     * second pass put back the handful of tags this class emits itself. The
     * author's text never becomes markup; it only ever decides which of our
     * tags we write.
     *
     * <p>Nothing here throws. A body reaching this method is already frozen onto
     * an instrument, so a mark that does not parse -- an older seed, a row
     * written before this class existed -- prints as the characters the author
     * typed rather than stopping a document that already exists.
     */
    public static String toHtml(String body) {
        if (body == null) {
            return "";
        }
        if (!hasBlocks(body)) {
            return inline(body);
        }
        return blocks(body);
    }

    // ---- line-level blocks: bullets, numbered items, tables --------------------
    //
    // "- " starts a bullet, "# " a numbered item, and a line written | a | b | is
    // a table row. Consecutive lines of one kind make one list or table; blank
    // lines between them do not break it. The inside of every item and cell goes
    // through the same inline scan, so nothing new can reach the page here.

    private enum Line { TEXT, BULLET, NUMBERED, ROW, BLANK }

    private static Line kindOf(String line) {
        if (line.isBlank()) return Line.BLANK;
        if (line.startsWith("- ")) return Line.BULLET;
        if (line.startsWith("# ")) return Line.NUMBERED;
        String t = line.strip();
        if (t.startsWith("|") && t.endsWith("|") && t.length() > 1) return Line.ROW;
        return Line.TEXT;
    }

    /** Whether this body has a list or a table in it -- a renderer wraps those in a block, not a paragraph. */
    public static boolean hasBlocks(String body) {
        if (body == null) {
            return false;
        }
        for (String line : body.split("\n", -1)) {
            Line kind = kindOf(line);
            if (kind != Line.TEXT && kind != Line.BLANK) {
                return true;
            }
        }
        return false;
    }

    private static String blocks(String body) {
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder(body.length() + 64);
        List<String> text = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            Line kind = kindOf(lines[i]);
            if (kind == Line.TEXT || kind == Line.BLANK) {
                text.add(lines[i]);
                i++;
                continue;
            }
            flushText(out, text);

            List<String> items = new ArrayList<>();
            while (i < lines.length) {
                Line here = kindOf(lines[i]);
                if (here == kind) {
                    items.add(lines[i]);
                    i++;
                } else if (here == Line.BLANK && nextNonBlank(lines, i) == kind) {
                    i++;
                } else {
                    break;
                }
            }
            switch (kind) {
                case BULLET -> list(out, "ul", "bullets", items);
                case NUMBERED -> list(out, "ol", "numbered", items);
                default -> table(out, items);
            }
        }
        flushText(out, text);
        return out.toString();
    }

    private static Line nextNonBlank(String[] lines, int from) {
        for (int j = from; j < lines.length; j++) {
            Line kind = kindOf(lines[j]);
            if (kind != Line.BLANK) return kind;
        }
        return Line.BLANK;
    }

    /** Plain lines between blocks, trimmed of the blank lines that only separated them. */
    private static void flushText(StringBuilder out, List<String> text) {
        int from = 0;
        int to = text.size();
        while (from < to && text.get(from).isBlank()) from++;
        while (to > from && text.get(to - 1).isBlank()) to--;
        if (from < to) {
            out.append("<div class=\"seg\">").append(inline(String.join("\n", text.subList(from, to)))).append("</div>");
        }
        text.clear();
    }

    private static void list(StringBuilder out, String tag, String cls, List<String> items) {
        out.append('<').append(tag).append(" class=\"").append(cls).append("\">");
        for (String item : items) {
            out.append("<li>").append(inline(item.substring(2))).append("</li>");
        }
        out.append("</").append(tag).append('>');
    }

    private static void table(StringBuilder out, List<String> rows) {
        out.append("<table class=\"grid\">");
        for (String row : rows) {
            out.append("<tr>");
            for (String cell : cells(row)) {
                out.append("<td>").append(inline(cell)).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private static List<String> cells(String row) {
        String t = row.strip();
        t = t.substring(1, t.length() - 1);
        List<String> out = new ArrayList<>();
        for (String cell : t.split("\\|", -1)) {
            out.add(cell.strip());
        }
        return out;
    }

    /** The inline marks alone -- one stretch of text, closing whatever it opened. */
    private static String inline(String body) {
        StringBuilder out = new StringBuilder(body.length() + 32);
        Deque<String> open = new ArrayDeque<>();

        for (Piece piece : scan(body, null)) {
            switch (piece) {
                case Piece.Text(String value) -> out.append(escape(value));
                case Piece.Open(String tag) -> {
                    if (!open.contains(tag)) {
                        open.push(tag);
                        out.append('<').append(tag).append('>');
                    }
                }
                case Piece.Close(String tag) -> {
                    if (!open.isEmpty() && open.peek().equals(tag)) {
                        open.pop();
                        out.append("</").append(tag).append('>');
                    }
                }
                // The width is re-emitted from the integer we parsed, never from
                // the author's string -- which is what keeps the one attribute
                // in this allowlist from being an attribute an author writes.
                case Piece.Rule(int width) ->
                        out.append("<span class=\"rule\" style=\"width:").append(width).append("ch\"></span>");
            }
        }

        // A mark left open would otherwise leak into the rest of the document,
        // so the paragraph closes its own.
        while (!open.isEmpty()) {
            out.append("</").append(open.pop()).append('>');
        }

        return out.toString();
    }

    /**
     * Escapes, and nothing else. The single seam: everything that reaches HTML
     * from a clause body goes through here first.
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Walks a body once, splitting it into text and marks.
     *
     * <p>A {@code <} that does not begin a mark this class knows is text. That
     * is not leniency -- a lease body says "less than" sometimes, and an author
     * typing it should not have to know they are writing markup.
     *
     * @param problems collects what is wrong, or null on the render path, which
     *                 has nothing to do about it
     */
    private static List<Piece> scan(String body, List<DomainProblem.Problem> problems) {
        List<Piece> pieces = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        int at = 0;
        while (at < body.length()) {
            char here = body.charAt(at);
            if (here != '<') {
                text.append(here);
                at++;
                continue;
            }

            int end = body.indexOf('>', at);
            String inside = end < 0 ? null : body.substring(at + 1, end);
            Piece piece = inside == null ? null : parse(inside, problems);
            if (piece == null) {
                text.append(here);
                at++;
                continue;
            }

            if (!text.isEmpty()) {
                pieces.add(new Piece.Text(text.toString()));
                text.setLength(0);
            }
            pieces.add(piece);
            at = end + 1;
        }

        if (!text.isEmpty()) {
            pieces.add(new Piece.Text(text.toString()));
        }
        return pieces;
    }

    /** One {@code <...>}, or null if it is not a mark and should print as text. */
    private static Piece parse(String inside, List<DomainProblem.Problem> problems) {
        if (MARKS.contains(inside)) {
            return new Piece.Open(inside);
        }
        if (inside.startsWith("/") && MARKS.contains(inside.substring(1))) {
            return new Piece.Close(inside.substring(1));
        }
        if (inside.equals("rule") || inside.startsWith("rule ")) {
            return rule(inside, problems);
        }

        // Looks like one of ours and is not. A near miss is worth saying out
        // loud, because "<bold>" prints as the word rather than as bold text
        // and the author has no reason to suspect the difference.
        if (problems != null && inside.matches("/?[A-Za-z][A-Za-z0-9]*")) {
            problems.add(DomainProblem.Problem.of("markup.unknown_mark", tag(inside)));
        }
        return null;
    }

    /** {@code <rule w="40">}, or the default width when it is written bare. */
    private static Piece rule(String inside, List<DomainProblem.Problem> problems) {
        String rest = inside.substring("rule".length()).trim();
        if (rest.isEmpty()) {
            return new Piece.Rule(MAX_RULE_WIDTH / 2);
        }

        if (!rest.matches("w\\s*=\\s*\"?\\d{1,3}\"?")) {
            if (problems != null) {
                problems.add(DomainProblem.Problem.of("markup.rule_width_unreadable", tag(inside)));
            }
            return null;
        }

        int width = Integer.parseInt(rest.replaceAll("\\D", ""));
        if (width < MIN_RULE_WIDTH || width > MAX_RULE_WIDTH) {
            if (problems != null) {
                problems.add(DomainProblem.Problem.of("markup.rule_width_out_of_range",
                        width, MIN_RULE_WIDTH, MAX_RULE_WIDTH));
            }
            return new Piece.Rule(Math.clamp(width, MIN_RULE_WIDTH, MAX_RULE_WIDTH));
        }
        return new Piece.Rule(width);
    }

    /** Quoted the way ClauseBodyRules quotes a token, so a message reads the same. */
    private static String tag(String name) {
        return "<" + name + ">";
    }
}
