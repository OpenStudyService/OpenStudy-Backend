package com.openstudy.common.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #127: the CSV file format of analysis-reuse-api.md "CSV 내보내기" (M15-D07·D10). */
class CsvFileUnitTests {
    static final Instant AS_OF = Instant.parse("2026-11-11T00:30:00Z");
    static final Instant CREATED = Instant.parse("2026-11-11T00:30:02.123Z");
    static final List<CsvFile.Column> COLUMNS = List.of(new CsvFile.Column("지점", false), new CsvFile.Column("순매출(원)", true));

    static String text(byte[] file) { return new String(file, 3, file.length - 3, StandardCharsets.UTF_8); }

    static CsvFile.Table table(List<List<Object>> rows, long total) {
        return new CsvFile.Table("지점별 매출 비교", "기간 2026-10-01~2026-10-31 (Asia/Seoul) · 지점 강남점, 성수점", true, AS_OF,
                COLUMNS, rows, total);
    }

    @Test void aFileIsUtf8WithABomCrlfAndTheHeaderBlockBeforeTheTable() {
        byte[] file = CsvFile.write(table(List.of(List.of("강남점", 1_250_000L)), 1), CREATED);
        assertThat(Arrays.copyOf(file, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(text(file)).isEqualTo(String.join("\r\n",
                "항목,값",
                "제목,지점별 매출 비교",
                "조회 조건,\"기간 2026-10-01~2026-10-31 (Asia/Seoul) · 지점 강남점, 성수점\"",
                "단위,원",
                "통화,KRW",
                "기준 시각,2026-11-11T09:30:00+09:00",
                "생성 시각,2026-11-11T09:30:02+09:00",
                "행 수,1",
                "잘림,아니오",
                "",
                "지점,순매출(원)",
                "강남점,1250000",
                ""));
    }

    @Test void aResultWithoutAmountsNamesItsUnitsInTheColumns() {
        var seats = new CsvFile.Table("빈자리", "지점 강남점", false, null, List.of(new CsvFile.Column("좌석 수(석)", true)),
                List.of(List.of(3L)), 1);
        assertThat(text(CsvFile.write(seats, CREATED))).contains("단위,열 이름 참고\r\n", "통화,-\r\n", "기준 시각,-\r\n");
    }

    @Test void moreThanAThousandRowsAreCutAndSaidSo() {
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < 1_240; i++) rows.add(List.of("지점" + i, (long) i));
        var cut = table(rows, 1_240);
        assertThat(cut.written()).isEqualTo(1_000);
        String file = text(CsvFile.write(cut, CREATED));
        assertThat(file).contains("행 수,\"1,000 / 전체 1,240\"\r\n", "잘림,\"예 (최대 1,000행)\"\r\n", "지점999,999\r\n")
                .doesNotContain("지점1000,");
        // The total of the whole result counts even when fewer rows were handed over.
        assertThat(table(rows.subList(0, 1_000), 5_000).truncated()).isTrue();
    }

    @Test void textThatASpreadsheetWouldRunIsQuotedAndNumbersStayNumbers() {
        List<Object> formulas = new ArrayList<>(List.of("=SUM(A1)", "+1", "-1", "@cmd", "\tx", "\rx", "a,b", "say \"hi\"", "plain"));
        assertThat(formulas.stream().map(CsvFile::text).toList()).containsExactly("'=SUM(A1)", "'+1", "'-1", "'@cmd", "'\tx",
                "\"'\rx\"", "\"a,b\"", "\"say \"\"hi\"\"\"", "plain");
        // A negative amount in a numeric column is a number, not a formula; the same value as text is guarded.
        var negative = new CsvFile.Table("t", "c", true, AS_OF, COLUMNS, List.of(Arrays.asList("-환불", -3_000L)), 1);
        assertThat(text(CsvFile.write(negative, CREATED))).contains("'-환불,-3000\r\n");
        assertThat(CsvFile.text(null)).isEmpty();
        assertThat(CsvFile.text("-")).isEqualTo("-");
        assertThat(CsvFile.text(AS_OF)).isEqualTo("2026-11-11T09:30:00+09:00");
    }

    @Test void theDownloadIsNamedInSeoulTimeAndNeverCached() {
        var response = CsvFile.response("지점별 매출 비교", CREATED, new byte[] {1});
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename*=UTF-8''%EC%A7%80%EC%A0%90%EB%B3%84%20%EB%A7%A4%EC%B6%9C%20%EB%B9%84%EA%B5%90_20261111-0930.csv");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/csv;charset=UTF-8");
    }

    @Test void aPeriodIsShownAsInclusiveSeoulDates() {
        assertThat(CsvFile.period(Instant.parse("2026-09-30T15:00:00Z"), Instant.parse("2026-10-31T15:00:00Z")))
                .isEqualTo("2026-10-01~2026-10-31");
        assertThat(CsvFile.period(Instant.parse("2026-11-01T15:00:00Z"), Instant.parse("2026-11-11T00:30:00Z")))
                .isEqualTo("2026-11-02~2026-11-11T09:30:00+09:00 전");
        assertThat(CsvFile.period(null, null)).isEqualTo("-");
    }
}
