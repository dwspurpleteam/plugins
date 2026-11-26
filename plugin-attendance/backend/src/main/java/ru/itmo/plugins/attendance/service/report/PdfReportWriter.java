package ru.itmo.plugins.attendance.service.report;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

/** Three quiet columns; headers repeat on new pages and participant rows stay together when possible. */
final class PdfReportWriter implements AutoCloseable {
    private static final float MARGIN = 42;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float[] COLUMNS = { 145, WIDTH - 245, 100 };
    private static final float LINE_HEIGHT = 15;
    private static final Color INK = new Color(30, 35, 43);
    private static final Color MUTED = new Color(103, 110, 121);
    private static final Color RULE = new Color(225, 228, 232);
    private final PDDocument document;
    private final PDFont font;
    private PDPageContentStream content;
    private float y;
    private boolean table;

    PdfReportWriter(PDDocument document, PDFont font) {
        this.document = document;
        this.font = font;
    }

    void space(float amount) {
        y -= amount;
    }

    void text(String value, float size, boolean muted) throws IOException {
        for (String line : wrap(value, size, WIDTH)) {
            if (content == null || y - size * 1.6f < MARGIN) newPage();
            draw(line, MARGIN, y, size, muted);
            y -= size * 1.6f;
        }
    }

    void beginTable() throws IOException {
        table = true;
        if (content == null || y - 45 < MARGIN) newPage();
        else header();
    }

    void endTable() {
        table = false;
    }

    void row(ReportRows.Row row) throws IOException {
        var cells = new ArrayList<List<String>>();
        cells.add(wrap(row.participant(), 10, COLUMNS[0] - 14));
        var periods = new ArrayList<String>();
        for (String period : row.periods()) periods.addAll(wrap(period, 9, COLUMNS[1] - 14));
        cells.add(periods);
        cells.add(wrap(row.duration(), 10, COLUMNS[2] - 8));
        int count = cells.stream().mapToInt(List::size).max().orElse(1);
        float height = count * LINE_HEIGHT + 16;
        if (height <= PDRectangle.A4.getHeight() - 2 * MARGIN - 30 && y - height < MARGIN) newPage();
        int offset = 0;
        while (offset < count) {
            int available = (int) ((y - MARGIN - 16) / LINE_HEIGHT);
            if (available < 1) {
                newPage();
                continue;
            }
            int lines = Math.min(count - offset, available);
            float x = MARGIN;
            for (int column = 0; column < cells.size(); column++) {
                List<String> cell = cells.get(column);
                if (column == 0 && offset >= cell.size()) {
                    draw(continuation(cell.getFirst(), 10, COLUMNS[0] - 14), x, y - 10, 10, false);
                } else if (column == 2 && offset >= cell.size()) {
                    // Keep the total visible when a participant's periods continue on another page.
                    draw(cell.getFirst(), x, y - 10, 10, false);
                }
                for (int line = 0; line < lines && offset + line < cell.size(); line++) {
                    draw(cell.get(offset + line), x, y - 10 - line * LINE_HEIGHT,
                            column == 1 ? 9 : 10, false);
                }
                x += COLUMNS[column];
            }
            y -= lines * LINE_HEIGHT + 16;
            rule(y + 4);
            offset += lines;
        }
    }

    private void header() throws IOException {
        float x = MARGIN;
        for (int column = 0; column < COLUMNS.length; column++) {
            draw(ReportRows.HEADER.get(column), x, y, 9, true);
            x += COLUMNS[column];
        }
        y -= 14;
        rule(y + 3);
        y -= 9;
    }

    private void newPage() throws IOException {
        if (content != null) content.close();
        document.addPage(new PDPage(PDRectangle.A4));
        content = new PDPageContentStream(document, document.getPage(document.getNumberOfPages() - 1));
        y = PDRectangle.A4.getHeight() - MARGIN;
        draw(Integer.toString(document.getNumberOfPages()), PDRectangle.A4.getWidth() / 2, 22, 8, true);
        if (table) header();
    }

    private void rule(float at) throws IOException {
        content.setStrokingColor(RULE);
        content.setLineWidth(0.5f);
        content.moveTo(MARGIN, at);
        content.lineTo(MARGIN + WIDTH, at);
        content.stroke();
    }

    private void draw(String text, float x, float at, float size, boolean muted) throws IOException {
        content.beginText();
        content.setNonStrokingColor(muted ? MUTED : INK);
        content.setFont(font, size);
        content.newLineAtOffset(x, at);
        content.showText(text);
        content.endText();
    }

    private List<String> wrap(String value, float size, float width) throws IOException {
        var clean = new StringBuilder();
        for (int codePoint : value.codePoints().toArray()) {
            String character = Character.isISOControl(codePoint) ? " " : new String(Character.toChars(codePoint));
            try {
                font.encode(character);
            } catch (IllegalArgumentException exception) {
                character = "?";
            }
            clean.append(character);
        }
        var lines = new ArrayList<String>();
        String safe = clean.toString();
        int start = 0;
        while (start < safe.length()) {
            int end = start;
            int lastSpace = -1;
            float measured = 0;
            while (end < safe.length()) {
                int next = end + Character.charCount(safe.codePointAt(end));
                float characterWidth = font.getStringWidth(safe.substring(end, next)) / 1000 * size;
                if (end > start && measured + characterWidth > width) break;
                measured += characterWidth;
                if (safe.charAt(end) == ' ') lastSpace = end;
                end = next;
            }
            if (end < safe.length() && lastSpace > start) end = lastSpace;
            lines.add(safe.substring(start, end).stripTrailing());
            start = end;
            while (start < safe.length() && Character.isWhitespace(safe.codePointAt(start))) {
                start += Character.charCount(safe.codePointAt(start));
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    private String continuation(String firstLine, float size, float width) throws IOException {
        String result = firstLine;
        while (!result.isEmpty() && font.getStringWidth(result + " …") / 1000 * size > width) {
            result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
        }
        return result + " …";
    }

    @Override
    public void close() throws IOException {
        if (content != null) content.close();
    }
}
