package com.openstudy.common.export;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * #127 (analysis-reuse-api.md "CSV 내보내기", ADR-M15-001 M15-D07·D10): one exported table as a file.
 *
 * <p>A header block (title, conditions, unit, currency, as-of, created, row count, truncation), a blank line,
 * then the table. UTF-8 with a BOM, CRLF, RFC 4180 quoting, at most 1,000 rows,
 * and a leading quote on text that a spreadsheet would run as a formula.
 */
public final class CsvFile {

    public static final int MAX_ROWS = 1000;
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private static final String LINE_END = "\r\n";
    private static final String SEPARATOR = ",";
    private static final String NO_VALUE = "-";

    /** M15-D07: text starting with one of these is run as a formula by a spreadsheet. */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private CsvFile() {}

    /** A numeric column writes numbers as they are; anything else is text and gets the formula guard. */
    public record Column(String name, boolean numeric) {}

    /**
     * One table to export. {@code total} is the size of the whole result, which may be larger than the rows given;
     * {@code money} says the amounts are won (KRW), otherwise the units are in the column names.
     */
    public record Table(
            String title,
            String conditions,
            boolean money,
            Instant asOf,
            List<Column> columns,
            List<List<Object>> rows,
            long total
    ) {
        public Table {
            columns = List.copyOf(columns);
            total = Math.max(total, rows.size());
        }

        public int written() {
            return Math.min(rows.size(), MAX_ROWS);
        }

        public boolean truncated() {
            return total > MAX_ROWS;
        }
    }

    public static byte[] write(Table table, Instant createdAt) {
        StringBuilder out = new StringBuilder();

        appendHeaderBlock(out, table, createdAt);
        out.append(LINE_END);
        appendTable(out, table);

        return withBom(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The download: {@code <name>_<YYYYMMDD-HHmm>.csv} in Seoul time, never cached. */
    public static ResponseEntity<byte[]> response(String name, Instant createdAt, byte[] file) {
        String filename = name + "_" + STAMP.format(createdAt.atZone(SEOUL)) + ".csv";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(file);
    }

    /** A Seoul time with its offset, to the second: 2026-11-11T09:30:00+09:00. */
    public static String time(Instant instant) {
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), SEOUL)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * A period as Seoul dates, both ends included ("2026-10-01~2026-10-31");
     * an end at midnight belongs to the day before, any other end is shown as a time.
     */
    public static String period(Instant from, Instant toExclusive) {
        if (from == null || toExclusive == null) {
            return NO_VALUE;
        }

        ZonedDateTime start = from.atZone(SEOUL);
        ZonedDateTime end = toExclusive.atZone(SEOUL);

        String first = start.toLocalTime().equals(LocalTime.MIDNIGHT)
                ? start.toLocalDate().toString()
                : time(from);

        if (!end.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return first + "~" + time(toExclusive) + " 전";
        }

        LocalDate last = end.toLocalDate().minusDays(1);

        return first + "~" + last;
    }

    /** RFC 4180 quoting, after the formula guard (M15-D07: =, +, -, @, tab, carriage return). */
    static String toCell(Object value) {
        if (value == null) {
            return "";
        }

        String text = value instanceof Instant instant ? time(instant) : value.toString();

        // A lone "-" (no value) is not a formula; anything longer that starts with a trigger is.
        boolean formula = !text.isEmpty()
                && !text.equals(NO_VALUE)
                && FORMULA_TRIGGERS.indexOf(text.charAt(0)) >= 0;
        if (formula) {
            text = "'" + text;
        }

        boolean needsQuotes = text.contains(",")
                || text.contains("\"")
                || text.contains("\n")
                || text.contains("\r");
        if (needsQuotes) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }

        return text;
    }

    private static void appendHeaderBlock(StringBuilder out, Table table, Instant createdAt) {
        String rowCount = table.truncated()
                ? String.format("%,d / 전체 %,d", table.written(), table.total())
                : String.format("%,d", table.written());

        appendLine(out, List.of("항목", "값"));
        appendLine(out, List.of("제목", table.title()));
        appendLine(out, List.of("조회 조건", table.conditions()));
        appendLine(out, List.of("단위", table.money() ? "원" : "열 이름 참고"));
        appendLine(out, List.of("통화", table.money() ? "KRW" : NO_VALUE));
        appendLine(out, List.of("기준 시각", table.asOf() == null ? NO_VALUE : time(table.asOf())));
        appendLine(out, List.of("생성 시각", time(createdAt)));
        appendLine(out, List.of("행 수", rowCount));
        appendLine(out, List.of("잘림", table.truncated() ? "예 (최대 1,000행)" : "아니오"));
    }

    private static void appendTable(StringBuilder out, Table table) {
        List<String> header = table.columns().stream()
                .map(column -> toCell(column.name()))
                .toList();
        out.append(String.join(SEPARATOR, header)).append(LINE_END);

        for (List<Object> row : table.rows().subList(0, table.written())) {
            String[] cells = new String[table.columns().size()];

            for (int i = 0; i < cells.length; i++) {
                Object value = i < row.size() ? row.get(i) : null;
                boolean numeric = table.columns().get(i).numeric();

                cells[i] = numeric && value instanceof Number number ? toNumberCell(number) : toCell(value);
            }

            out.append(String.join(SEPARATOR, cells)).append(LINE_END);
        }
    }

    private static void appendLine(StringBuilder out, List<String> cells) {
        List<String> quoted = cells.stream()
                .map(CsvFile::toCell)
                .toList();

        out.append(String.join(SEPARATOR, quoted)).append(LINE_END);
    }

    private static String toNumberCell(Number number) {
        if (number instanceof Double || number instanceof Float) {
            return BigDecimal.valueOf(number.doubleValue()).toPlainString();
        }

        if (number instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }

        return number.toString();
    }

    private static byte[] withBom(byte[] body) {
        byte[] file = new byte[BOM.length + body.length];

        System.arraycopy(BOM, 0, file, 0, BOM.length);
        System.arraycopy(body, 0, file, BOM.length, body.length);

        return file;
    }
}
