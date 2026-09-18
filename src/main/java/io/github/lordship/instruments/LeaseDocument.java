package io.github.lordship.instruments;

import io.github.lordship.shared.ClauseMarkup;

import java.util.List;

/**
 * A frozen packet laid out as one self-contained HTML document.
 *
 * <p>Layout only. The renderer never writes a word the author did not: no
 * heading it invented, no "Landlord" it supplied, no number it assigned, no
 * blank where a token went missing. What it decides is where things sit on a
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
        StringBuilder out = new StringBuilder(8192);

        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<title>").append(escape(lease.documentName())).append("</title>\n")
                .append("<style>\n").append(stylesheet(serial)).append("</style>\n")
                .append("</head>\n<body>\n");

        provenance(out, lease, serial);
        draftNotice(out, lease);
        addendaChecklist(out, lease.frozen().sections());

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
     * The packet's list of what is attached, taken from the sections that asked
     * to be listed.
     *
     * <p>Assembling it here rather than asking an author to keep a list in a
     * clause is the point of the flag: a park that drops the septic addendum
     * drops its line from the checklist in the same motion, and the two cannot
     * disagree.
     */
    private static void addendaChecklist(StringBuilder out, List<DocumentFreeze.FrozenSection> sections) {
        List<DocumentFreeze.FrozenSection> listed = sections.stream()
                .filter(section -> section.listedAsAddendum() && !section.clauses().isEmpty())
                .toList();
        if (listed.isEmpty()) {
            return;
        }

        out.append("<ul class=\"addenda\">\n");
        for (DocumentFreeze.FrozenSection section : listed) {
            out.append("<li><span class=\"box\"></span>")
                    .append(escape(section.name()))
                    .append("</li>\n");
        }
        out.append("</ul>\n");
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
                .append("\">\n<h2>").append(escape(section.name())).append("</h2>\n");

        if (section.statuteRef() != null) {
            out.append("<p class=\"statute\">").append(escape(section.statuteRef())).append("</p>\n");
        }

        for (DocumentFreeze.FrozenClause clause : section.clauses()) {
            clause(out, clause);
        }

        out.append("</section>\n");
    }

    /**
     * A clause, printed with no number in front of it.
     *
     * <p>{@code ordinal} is where the clause sits, not what it is called. The
     * two look alike until a document has both: of the clauses on the Washington
     * lot lease, most open with a number the author typed ("1. Demised
     * Premises:"), and some of them cite each other by it -- "at the rate
     * indicated in Section 5a". Printing the assigned ordinal as a label would
     * number those twice, and renumbering them would leave every cross-reference
     * pointing at the wrong paragraph. Assigned numbering is the better design
     * and it needs two things this does not have yet: a way for a body to cite a
     * clause rather than a number, and labels that can say "5a".
     */
    private static void clause(StringBuilder out, DocumentFreeze.FrozenClause clause) {
        out.append("<div class=\"clause\">\n");

        if (clause.title() != null && !clause.title().isBlank()) {
            out.append("<h3>").append(escape(clause.title())).append("</h3>\n");
        }

        out.append("<p class=\"body\">").append(ClauseMarkup.toHtml(clause.body())).append("</p>\n");

        if (clause.statuteRef() != null) {
            out.append("<p class=\"statute\">").append(escape(clause.statuteRef())).append("</p>\n");
        }

        out.append("</div>\n");
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
                .clause { page-break-inside: avoid; margin: 0 0 4mm 0; }
                /* Frozen bodies carry their own line breaks, blank lines, tabs and
                   signature rules. pre-wrap prints them; break-word keeps a long
                   run of underscores inside the margin instead of off the page. */
                .body { margin: 0; white-space: pre-wrap; overflow-wrap: break-word; }
                /* A line somebody writes on. inline-block so the width holds:
                   an inline span would collapse to nothing, having no text. */
                .rule { display: inline-block; border-bottom: 1px solid #111; vertical-align: -0.4mm; }
                .statute { font-size: 8pt; color: #555; margin: 1mm 0 0 0; font-style: italic; }
                .addenda { list-style: none; padding: 0; margin: 0 0 8mm 0; }
                .addenda li { margin: 0 0 2mm 0; }
                .addenda .box { display: inline-block; width: 3.5mm; height: 3.5mm; border: 1px solid #111; margin-right: 3mm; vertical-align: -0.3mm; }
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
