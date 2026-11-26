package ru.itmo.plugins.attendance.service.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Service;

import ru.itmo.plugins.attendance.service.tracking.AttendanceReport;

@Service
public class AttendanceReportRenderer {
    public byte[] csv(AttendanceReport report, ZoneId zone) {
        var view = ReportRows.prepare(report, zone);
        StringBuilder output = new StringBuilder("\ufeff");
        csvRow(output, List.of("Начало отслеживания", view.trackingSince(), ""));
        csvRow(output, List.of("Сформирован", view.generatedAt(), view.timezone()));
        csvRow(output, List.of("", "", ""));
        csvRow(output, ReportRows.HEADER);
        for (var row : view.rows()) csvRow(output, row.cells());
        for (String note : view.notes()) csvRow(output, List.of(note, "", ""));
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void csvRow(StringBuilder out, List<String> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index != 0) out.append(';');
            String value = spreadsheetSafe(cells.get(index));
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    private static String spreadsheetSafe(String value) {
        int first = 0;
        while (first < value.length()) {
            int character = value.codePointAt(first);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)
                    && !Character.isISOControl(character) && Character.getType(character) != Character.FORMAT) break;
            first += Character.charCount(character);
        }
        boolean formula = first < value.length() && "=+-@".indexOf(value.charAt(first)) >= 0;
        boolean leadingControl = !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0;
        return formula || leadingControl ? "'" + value : value;
    }

    public byte[] pdf(AttendanceReport report, ZoneId zone) {
        var view = ReportRows.prepare(report, zone);
        try (PDDocument document = new PDDocument();
                InputStream fontFile = AttendanceReportRenderer.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf")) {
            if (fontFile == null) throw new IOException("Bundled report font is missing");
            PDFont font = PDType0Font.load(document, fontFile);
            document.getDocumentInformation().setTitle("Посещаемость");
            try (var pages = new PdfReportWriter(document, font)) {
                pages.text("Посещаемость", 19, false);
                pages.space(10);
                pages.text("Начало отслеживания: " + view.trackingSince(), 9, true);
                pages.text("Сформирован: " + view.generatedAt() + " · " + view.timezone(), 9, true);
                pages.space(20);
                if (view.rows().isEmpty()) {
                    pages.text("Нет участников за период отслеживания", 11, false);
                } else {
                    pages.beginTable();
                    for (var row : view.rows()) pages.row(row);
                    pages.endTable();
                }
                if (!view.notes().isEmpty()) pages.space(12);
                for (String note : view.notes()) pages.text(note, 8, true);
            }
            var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException exception) {
            throw new ReportGenerationException("Не удалось сформировать PDF", exception);
        }
    }
}
