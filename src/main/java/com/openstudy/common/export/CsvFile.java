package com.openstudy.common.export;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * #127 (analysis-reuse-api.md "CSV 내보내기", ADR-M15-001 M15-D07·D10): one exported table as a file. A header block
 * (title, conditions, unit, currency, as-of, created, row count, truncation), a blank line, then the table. UTF-8 with a
 * BOM, CRLF, RFC 4180 quoting, at most 1,000 rows, and a leading quote on text that a spreadsheet would run as a formula.
 */
public final class CsvFile {
    public static final int MAX_ROWS = 1000;
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private CsvFile() {}

    /** A numeric column writes numbers as they are; anything else is text and gets the formula guard. */
    public record Column(String name, boolean numeric) {}

    /**
     * One table to export. {@code total} is the size of the whole result, which may be larger than the rows given;
     * {@code money} says the amounts are won (KRW), otherwise the units are in the column names.
     */
    public record Table(String title, String conditions, boolean money, Instant asOf, List<Column> columns,
            List<List<Object>> rows, long total) {
        public Table {
            columns = List.copyOf(columns);
            total = Math.max(total, rows.size());
        }

        public int written() { return Math.min(rows.size(), MAX_ROWS); }

        public boolean truncated() { return total > MAX_ROWS; }
    }

    public static byte[] write(Table table, Instant createdAt) {
        var out = new StringBuilder();
        line(out, List.of("항목", "값"));
        line(out, List.of("제목", table.title()));
        line(out, List.of("조회 조건", table.conditions()));
        line(out, List.of("단위", table.money() ? "원" : "열 이름 참고"));
        line(out, List.of("통화", table.money() ? "KRW" : "-"));
        line(out, List.of("기준 시각", table.asOf() == null ? "-" : time(table.asOf())));
        line(out, List.of("생성 시각", time(createdAt)));
        line(out, List.of("행 수", table.truncated() ? String.format("%,d / 전체 %,d", table.written(), table.total())
                : String.format("%,d", table.written())));
        line(out, List.of("잘림", table.truncated() ? "예 (최대 1,000행)" : "아니오"));
        out.append("\r\n");
        out.append(String.join(",", table.columns().stream().map(column -> text(column.name())).toList())).append("\r\n");
        for (var row : table.rows().subList(0, table.written())) {
            var cells = new String[table.columns().size()];
            for (int i = 0; i < cells.length; i++) {
                Object value = i < row.size() ? row.get(i) : null;
                cells[i] = table.columns().get(i).numeric() && value instanceof Number number ? number(number) : text(value);
            }
            out.append(String.join(",", cells)).append("\r\n");
        }
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] file = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, file, 0, BOM.length);
        System.arraycopy(body, 0, file, BOM.length, body.length);
        return file;
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
        return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), SEOUL).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * A period as Seoul dates, both ends included ("2026-10-01~2026-10-31"); an end at midnight belongs to the day
     * before, any other end is shown as a time.
     */
    public static String period(Instant from, Instant toExclusive) {
        if (from == null || toExclusive == null) return "-";
        var start = from.atZone(SEOUL);
        var end = toExclusive.atZone(SEOUL);
        String first = start.toLocalTime().equals(LocalTime.MIDNIGHT) ? start.toLocalDate().toString() : time(from);
        if (!end.toLocalTime().equals(LocalTime.MIDNIGHT)) return first + "~" + time(toExclusive) + " 전";
        LocalDate last = end.toLocalDate().minusDays(1);
        return first + "~" + last;
    }

    private static void line(StringBuilder out, List<String> cells) {
        out.append(String.join(",", cells.stream().map(CsvFile::text).toList())).append("\r\n");
    }

    private static String number(Number number) {
        return number instanceof Double || number instanceof Float ? BigDecimal.valueOf(number.doubleValue()).toPlainString()
                : number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
    }

    /** RFC 4180 quoting, after the formula guard (M15-D07: =, +, -, @, tab, carriage return). */
    static String text(Object value) {
        if (value == null) return "";
        String text = value instanceof Instant instant ? time(instant) : value.toString();
        // A lone "-" (no value) is not a formula; anything longer that starts with a trigger is.
        if (!text.isEmpty() && !text.equals("-") && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
