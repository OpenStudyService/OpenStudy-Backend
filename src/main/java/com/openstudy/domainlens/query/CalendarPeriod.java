package com.openstudy.domainlens.query;

import java.time.*;
import java.util.regex.Pattern;

/** Calendar interpretation only. Data availability is checked separately by DateNormalizer. */
public final class CalendarPeriod {
    private CalendarPeriod() {}
    private static final Pattern YEAR=Pattern.compile("(?<![0-9])([0-9]{4})\\s*년(?![0-9]|대)");
    public static boolean explicit(String text) {
        // A duration-based rate does not specify a calendar population filter.
        String calendarText=text.replaceAll("기간\\s*내\\s*[^\\s]*[율률]", "");
        return specified(text) || calendarText.contains("기간") || unsupportedCalendar(text);
    }
    /** Inclusive named months, with an optional repeated end year; never infer a rollover. */
    public static DateNormalizer.Range months(String text,ZoneId zone) {
        if(unsupportedCalendar(text) || Pattern.compile("제외|빼고|말고|아닌|않|비교|대비|\\b(?:not|without|except)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find())return null;
        var match=Pattern.compile("(?<![0-9])([0-9]{4})\\s*년\\s*(0?[1-9]|1[0-2])\\s*월\\s*부터\\s*(?:([0-9]{4})\\s*년\\s*)?(0?[1-9]|1[0-2])\\s*월\\s*까지").matcher(text);
        if(!match.find())return null;
        String remainder=text.substring(0,match.start())+text.substring(match.end());
        if(specified(remainder) || Pattern.compile("[0-9]\\s*일|이상|미만|이후|이전").matcher(remainder).find())return null;
        try {
            var start=YearMonth.of(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)));
            var end=YearMonth.of(match.group(3)==null?start.getYear():Integer.parseInt(match.group(3)),Integer.parseInt(match.group(4)));
            if(end.isBefore(start))return null;
            return new DateNormalizer.Range(start.atDay(1).atStartOfDay(zone).toInstant(),end.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        }catch(DateTimeException invalid){return null;}
    }
    public static boolean specified(String text) {
        return Pattern.compile("(?<![0-9])[0-9]{4}\\s*(?:년|[-/])|[0-9]{1,2}\\s*월|이번\\s*달|지난\\s*달|올해|작년",Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
    public static boolean unsupportedCalendar(String text) {
        return Pattern.compile("회계\\s*연도|회계년|fiscal|음력",Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
    /** A sole explicit calendar year; partial dates, negation and comparisons remain unresolved. */
    public static DateNormalizer.Range year(String text,ZoneId zone) {
        if(unsupportedCalendar(text) || Pattern.compile("월|분기|반기|연초|연말|[0-9]{4}\\s*년\\s*(?:초|말)|quarter|\\bQ[1-4]\\b|일\\s*(?:이상|미만|부터|까지|이후|이전)|[0-9]{4}[-/]|제외|빼고|말고|아닌|않|비교|대비|이후|이전|부터|까지|\\b(?:not|without|except|after|before)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find())return null;
        var years=YEAR.matcher(text);if(!years.find())return null;
        int value=Integer.parseInt(years.group(1));if(years.find())return null;
        // An unmarked second year is not a sole-year request either.
        var numbers=Pattern.compile("(?<![0-9])[0-9]{4}(?![0-9])").matcher(text);int count=0;while(numbers.find())count++;
        if(count!=1)return null;
        try {var start=LocalDate.of(value,1,1).atStartOfDay(zone).toInstant();var end=LocalDate.of(value,1,1).plusYears(1).atStartOfDay(zone).toInstant();return new DateNormalizer.Range(start,end);}
        catch(DateTimeException invalid){return null;}
    }
}
