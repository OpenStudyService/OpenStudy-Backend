package com.openstudy.domainlens.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Shared sensitive-text detection before providers, persistence and diagnostics. */
public final class SearchText {
    public static final String VERSION="sanitize-3";
    private static final Pattern DATE_TIME=Pattern.compile("(?<![\\w])\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})?(?![\\w:])");
    public enum Kind { EMAIL, PHONE, SECRET }
    private static final String QUOTED="\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'";
    private static final String VALUE="(?:(?:Bearer|Basic)\\s+[^\\s,;\"']+|"+QUOTED+"|[^\\s,;\"']+)";
    private static final Pattern SENSITIVE=Pattern.compile(
            "(?<SECRET>(?i:[\"']?(?:password|passwd|api[_-]?key|secret|authorization)[\"']?\\s*[:=]\\s*"+VALUE
            +"|Bearer\\s+[A-Za-z0-9._~+/=-]+|sk-(?:proj-)?[A-Za-z0-9_-]{16,}))"
            +"|(?<EMAIL>(?i:[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}))"
            +"|(?<PHONE>(?<![\\w])\\+?\\d(?:[ \\t().-]*\\d){8,}(?![\\w]))");
    private SearchText() {}
    public static String sanitize(String text) {
        return replace(text,(kind,value)->"["+kind+"]");
    }
    /** Replacement receives originals only on the server; callers must not log them. */
    public static String replace(String text,BiFunction<Kind,String,String> replacement) {
        var dates=new java.util.ArrayList<int[]>();
        var dateMatcher=DATE_TIME.matcher(text);
        while(dateMatcher.find()) {
            String value=dateMatcher.group().replace(' ','T');
            try {
                java.time.format.DateTimeFormatter.ISO_DATE_TIME.parseBest(value,
                        java.time.OffsetDateTime::from,java.time.LocalDateTime::from);
                dates.add(new int[]{dateMatcher.start(),dateMatcher.end()});
            } catch(java.time.DateTimeException invalid) { /* Not a date: retain sensitive detection. */ }
        }
        var matcher=SENSITIVE.matcher(text);var result=new StringBuilder();
        while(matcher.find()) {
            Kind kind=matcher.group("SECRET")!=null?Kind.SECRET:matcher.group("EMAIL")!=null?Kind.EMAIL:Kind.PHONE;
            // Only a parsed timestamp can exempt a phone-shaped substring. Secrets always win.
            if(kind==Kind.PHONE && dates.stream().anyMatch(span->matcher.start()>=span[0] && matcher.end()<=span[1])) {
                matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(matcher.group()));continue;
            }
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(replacement.apply(kind,matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").strip();
    }
    public static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
}
