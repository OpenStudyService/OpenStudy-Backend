package com.openstudy.domainlens.work;
import java.io.*;
import java.util.*;
/** RFC 4180 quoting; formula protection applies to strings, preserving typed numbers. */
public final class CsvWriter {
 private CsvWriter() {}
 public static void row(Writer writer,List<?> values) {
  try {for(int i=0;i<values.size();i++){if(i>0)writer.write(',');Object value=values.get(i);String text=value==null?"":value.toString();
   if(value instanceof CharSequence){String stripped=text.stripLeading();if(!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n"))text="'"+text;}
   writer.write('"');writer.write(text.replace("\"","\"\""));writer.write('"');}writer.write("\r\n");
  }catch(IOException ex){throw new UncheckedIOException(ex);}
 }
}
