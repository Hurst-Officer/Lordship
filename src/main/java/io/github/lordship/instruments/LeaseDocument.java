package io.github.lordship.instruments;

import io.github.lordship.documenttemplate.DocumentStyle;
import io.github.lordship.shared.ClauseMarkup;
import io.github.lordship.shared.StyleRules;
import io.github.lordship.shared.StyleTarget;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A frozen packet laid out as one self-contained HTML document.
 *
 * <p>Layout only. The renderer never writes a word the author did not: no
 * heading it invented, no "Landlord" it supplied, no blank where a token went
 * missing. Clause numbers are the freeze's, printed as it handed them over. What it decides is where things sit on a
 * page -- margins, page breaks, the running footer -- and what a browser or a
 * PDF engine needs in order to agree with itself about that.
 *
 * <p>Self-contained on purpose. The stylesheet is inlined because this same
 * string is what goes to the PDF engine and into storage, and a document whose
 * appearance depends on a file it links to is a document that renders
 * differently in five years.
 */
public final class LeaseDocument {

    private LeaseDocument() {}

    /**
     * @param serial printed in the running footer of every page. The identity
     *               of the physical artifact rather than anything the lease
     *               says -- a scanned page 6 that came back without page 1 is
     *               still findable. Null on a preview, which has no serial yet;
     *               an author who wants it in the body writes
     *               {@code {{instrument.serial}}} in a clause.
     */
    public static String render(LeasePreview lease, String serial) {
        return render(lease, serial, List.of());
    }

    /**
     * @param styles the template's {@code document_style} rows. One with a target
     *               restyles that part of every page, after the built-in look so it
     *               wins; a named one is emitted only if something printed uses it.
     *               Any that fails {@link StyleRules} is dropped.
     */
    public static String render(LeasePreview lease, String serial, List<DocumentStyle> styles) {
        StringBuilder out = new StringBuilder(8192);

        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<title>").append(escape(lease.documentName())).append("</title>\n")
                .append("<style>\n").append(stylesheet(serial))
                .append(adminStyles(lease, styles)).append("</style>\n")
                .append("</head>\n<body>\n");

        provenance(out, lease, serial);
        draftNotice(out, lease);

        boolean first = true;
        for (DocumentFreeze.FrozenSection section : lease.frozen().sections()) {
            // A heading with nothing under it is not a sub-document. The freeze
            // already drops an empty section and reports it if a statute
            // required it -- except a signature block, which it exempts because
            // a signature block need not have clauses. This template marks every
            // section a signature block, so that exemption covers all of them
            // and an empty one arrives here intact.
            if (section.clauses().isEmpty()) {
                continue;
            }
            section(out, section, first);
            first = false;
        }

        return out.append("</body>\n</html>\n").toString();
    }

    /**
     * Which template this came off, and which serial it is.
     *
     * <p>On screen only. On paper the serial rides in the running footer, where
     * it survives a packet being separated, and the template's internal name is
     * not something a tenant needs to read.
     */
    private static void provenance(StringBuilder out, LeasePreview lease, String serial) {
        out.append("<div class=\"provenance\">")
                .append(escape(lease.documentName()));
        if (lease.documentVersion() != null) {
            out.append(" v").append(lease.documentVersion());
        }
        if (serial != null) {
            out.append(" &middot; ").append(escape(serial));
        }
        out.append("</div>\n");
    }

    /**
     * Said out loud when the packet still has holes in it.
     *
     * <p>Generation refuses an incomplete packet, so the only way one reaches
     * this renderer is a preview -- and a preview prints on the same paper as a
     * lease. The unresolved tokens do stand in the text, but a token in the
     * middle of page four is not something somebody notices before signing.
     * This is a stamp about the artifact, not a word about the agreement.
     */
    private static void draftNotice(StringBuilder out, LeasePreview lease) {
        if (lease.isComplete()) {
            return;
        }
        out.append("<div class=\"draft\">DRAFT &mdash; NOT FOR SIGNATURE</div>\n");
    }

    /**
     * One sub-document. Each starts a fresh page because each is signed on its
     * own -- the Pet Agreement coming half way down the page after the lease
     * ends is how a tenant signs something they did not know they were reading.
     *
     * <p>The first one does not break, or the document opens on a blank page.
     * Marked here rather than left to a {@code :first-of-type} rule, which
     * answers "first section element", not "first section we printed", and
     * quietly stops being the same thing the moment anything is emitted above.
     */
    private static void section(StringBuilder out, DocumentFreeze.FrozenSection section, boolean first) {
        out.append("<section class=\"sheet")
                .append(first ? " first" : "")
                .append(section.style() == null ? "" : " " + styleClass(section.style()))
                .append("\">\n<h2")
                .append(section.titleStyle() == null ? "" : " class=\"" + styleClass(section.titleStyle()) + "\"")
                .append(">").append(escape(section.name())).append("</h2>\n");

        if (section.statuteRef() != null) {
            out.append("<p class=\"statute\">").append(escape(section.statuteRef())).append("</p>\n");
        }

        // A run of clauses joined by keepWithNext shares one box, so a page
        // cannot break between the park-sale notice and the signature under it.
        boolean inKeep = false;
        for (DocumentFreeze.FrozenClause clause : section.clauses()) {
            if (clause.keepWithNext() && !inKeep) {
                out.append("<div class=\"keep\">\n");
                inKeep = true;
            }
            clause(out, clause);
            if (!clause.keepWithNext() && inKeep) {
                out.append("</div>\n");
                inKeep = false;
            }
        }
        if (inKeep) {
            // the last clause of a section asked to keep with a next that is not here;
            // the freeze has already reported that pair
            out.append("</div>\n");
        }

        out.append("</section>\n");
    }

    /**
     * A clause, with the number the freeze gave it hanging in front.
     *
     * <p>The number goes in front of the title when there is one, otherwise in
     * front of the body. Depth indents: "A." under "9." sits one step in.
     */
    private static void clause(StringBuilder out, DocumentFreeze.FrozenClause clause) {
        out.append("<div class=\"clause depth-").append(Math.min(Math.max(clause.depth(), 1), 3));
        if (clause.style() != null) {
            out.append(' ').append(styleClass(clause.style()));
        }
        out.append("\">\n");

        String number = clause.number() == null ? ""
                : "<span class=\"num\">" + escape(clause.number()) + "</span>";
        boolean titled = clause.title() != null && !clause.title().isBlank();

        if (titled) {
            out.append("<h3>").append(number).append(escape(clause.title())).append("</h3>\n");
        }

        boolean hangs = !titled && clause.number() != null;
        // A list or a table cannot sit inside a paragraph, so a body with one is a block.
        String tag = ClauseMarkup.hasBlocks(clause.body()) ? "div" : "p";
        out.append('<').append(tag).append(hangs ? " class=\"body hang\">" : " class=\"body\">")
                .append(hangs ? number : "")
                .append(ClauseMarkup.toHtml(clause.body()))
                .append("</").append(tag).append(">\n");

        if (clause.statuteRef() != null) {
            out.append("<p class=\"statute\">").append(escape(clause.statuteRef())).append("</p>\n");
        }

        out.append("</div>\n");
    }

    /**
     * The admin's css, after the built-in look so it wins. Page-wide styles
     * first, each under the selector for the part of the page it names; then one
     * rule per named style that something printed actually uses. Selectors are
     * written here, never by the admin.
     */
    private static String adminStyles(LeasePreview lease, List<DocumentStyle> styles) {
        Set<UUID> used = new LinkedHashSet<>();
        for (DocumentFreeze.FrozenSection section : lease.frozen().sections()) {
            if (section.style() != null) used.add(section.style());
            if (section.titleStyle() != null) used.add(section.titleStyle());
            for (DocumentFreeze.FrozenClause clause : section.clauses()) {
                if (clause.style() != null) used.add(clause.style());
            }
        }

        StringBuilder css = new StringBuilder();
        for (DocumentStyle style : styles) {
            if (style.isSoftDeleted() || !StyleRules.isSafe(style.css()) || style.css() == null || style.css().isBlank()) {
                continue;
            }
            String declarations = style.css().replace('\n', ' ').strip();
            if (style.target() != null) {
                css.append(SELECTORS.get(style.target())).append(" { ").append(declarations).append(" }\n");
            }
        }
        for (DocumentStyle style : styles) {
            if (style.target() == null && used.contains(style.uuid()) && !style.isSoftDeleted()
                    && style.css() != null && StyleRules.isSafe(style.css())) {
                css.append('.').append(styleClass(style.uuid()))
                        .append(" { ").append(style.css().replace('\n', ' ').strip()).append(" }\n");
            }
        }
        return css.toString();
    }

    // sql: document_style.target -- where each one lands in this stylesheet
    private static final Map<StyleTarget, String> SELECTORS = Map.of(
            StyleTarget.PAGE, "@page",
            StyleTarget.BODY, "body",
            StyleTarget.SECTION_TITLE, "h2",
            StyleTarget.CLAUSE_TITLE, "h3",
            StyleTarget.NUMBER, ".num",
            StyleTarget.STATUTE, ".statute");

    /** A class name from a uuid: letters and digits only, so nothing an admin typed reaches a selector. */
    static String styleClass(UUID style) {
        return "s-" + style.toString().replace("-", "");
    }

    /**
     * A heading, a name, a statute -- text that is not a clause body.
     *
     * <p>Escaped and nothing more. Marks are a thing an author writes inside a
     * clause, and a section called "Rules &lt;b&gt;and&lt;/b&gt; Regulations"
     * is a section somebody named badly, not a heading half in bold.
     *
     * <p>A body goes through {@link ClauseMarkup#toHtml} instead, which escapes
     * first and then lets four marks back through. That method is the only
     * place markup enters this document, which is what makes the allowlist an
     * allowlist rather than a suggestion.
     */
    private static String escape(String text) {
        return ClauseMarkup.escape(text);
    }

    /**
     * Paged media, not screen. {@code @page} owns the margins and the running
     * footer; a browser shows roughly what the PDF engine will produce, which is
     * the whole reason a preview and the paper can be trusted to agree.
     *
     * <p>The serial goes into the footer as a literal rather than through
     * {@code string-set}, which engines support unevenly. The stylesheet is
     * written per document anyway, so there is nothing to gain by making the
     * footer look it up.
     */
    private static String stylesheet(String serial) {
        return """
                @page {
                  size: letter;
                  margin: 22mm 20mm 20mm 20mm;
                  @bottom-center { content: "Page " counter(page) " of " counter(pages); font-size: 8pt; color: #555; }
                  @bottom-right { content: "%s"; font-family: "Courier New", monospace; font-size: 7.5pt; color: #777; }
                }
                body { font-family: Georgia, "Times New Roman", serif; font-size: 10.5pt; line-height: 1.45; color: #111; margin: 0; }
                h2 { font-size: 12.5pt; text-transform: uppercase; letter-spacing: 0.04em; margin: 0 0 6mm 0; padding-bottom: 2mm; border-bottom: 1px solid #111; }
                h3 { font-size: 10.5pt; margin: 0 0 1mm 0; }
                .sheet { page-break-before: always; }
                .sheet.first { page-break-before: auto; }
                /* A long clause may run onto the next page; a short one, a title and the
                   first lines of its text, and anything kept together may not. */
                .clause { margin: 0 0 4mm 0; orphans: 3; widows: 3; }
                h3 { page-break-after: avoid; }
                .keep { page-break-inside: avoid; }
                .depth-2 { margin-left: 8mm; }
                .depth-3 { margin-left: 16mm; }
                /* The number hangs in front of the text. */
                .num { display: inline-block; min-width: 8mm; font-weight: bold; text-indent: 0; }
                /* A wrapped line lines up with the text, not with the number. */
                .hang { padding-left: 8mm; text-indent: -8mm; }
                /* A body with a list or table is a block: its number floats beside the first line instead. */
                div.hang { text-indent: 0; }
                div.hang > .num { float: left; margin-left: -8mm; }
                /* Frozen bodies carry their own line breaks, blank lines, tabs and
                   signature rules. pre-wrap prints them; break-word keeps a long
                   run of underscores inside the margin instead of off the page. */
                .body { margin: 0; white-space: pre-wrap; overflow-wrap: break-word; }
                /* A line somebody writes on. inline-block so the width holds:
                   an inline span would collapse to nothing, having no text. */
                .rule { display: inline-block; border-bottom: 1px solid #111; vertical-align: -0.4mm; }
                .statute { font-size: 8pt; color: #555; margin: 1mm 0 0 0; font-style: italic; }
                /* Lists and tables inside a body: normal wrapping, not the body's pre-wrap. */
                .body ul, .body ol { margin: 1mm 0 2mm 0; padding-left: 7mm; white-space: normal; }
                .body li { margin: 0 0 1.5mm 0; }
                .grid { border-collapse: collapse; margin: 2mm 0; white-space: normal; }
                .grid td { border: 1px solid #111; padding: 1mm 3mm; text-align: center; }
                .grid tr:first-child td { font-weight: bold; }
                .seg + .seg, .seg + ul, .seg + ol, .seg + table, ul + .seg, ol + .seg, table + .seg { margin-top: 2mm; }
                .draft { border: 2px solid #b00; color: #b00; font-family: Arial, Helvetica, sans-serif; font-weight: bold; font-size: 11pt; letter-spacing: 0.08em; text-align: center; padding: 3mm; margin: 0 0 8mm 0; }
                .provenance { font-family: Arial, Helvetica, sans-serif; font-size: 8pt; color: #777; text-align: right; margin: 0 0 6mm 0; }
                @media print { .provenance { display: none; } }
                """.formatted(serial == null ? "" : cssString(serial));
    }

    /** A serial is Crockford base-32 and hyphens, but the footer is generated code. */
    private static String cssString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
