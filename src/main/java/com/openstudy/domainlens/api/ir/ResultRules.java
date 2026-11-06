package com.openstudy.domainlens.api.ir;

import java.math.*;
import java.time.*;
import java.util.*;

/** Typed result invariants shared by JSON decoding and ViewBinder. */
public final class ResultRules {
    private ResultRules() {}
    public static void columns(List<TypedDataset.Column> columns) {
        var aliases=new HashSet<String>();
        for(var c:columns)ContractRules.require(c.alias()!=null && c.alias().matches("[A-Za-z][A-Za-z0-9_]*")
                && aliases.add(c.alias()) && c.type()!=null && (c.precision()==null || c.precision()>=0),"Invalid result column");
    }
    public static void dataset(TypedDataset ds) {
        ContractRules.require(ContractRules.text(ds.id()) && ds.snapshotAt()!=null && ds.asOf()!=null,"Missing dataset metadata");
        ContractRules.require(ds.page()>=0 && ds.size()>0 && ds.size()<=100 && ds.totalElements()>=ds.rows().size()
                && ds.rows().size()<=ds.size(),"Invalid result pagination");
        columns(ds.columns());
        var aliases=new HashSet<String>();ds.columns().forEach(c->aliases.add(c.alias()));
        for(var row:ds.rows()) {
            ContractRules.require(row.keySet().equals(aliases),"Result schema mismatch");
            for(var c:ds.columns())ContractRules.require(valid(c.type(),row.get(c.alias())),"Result value type mismatch");
        }
    }
    public static void envelope(ResultEnvelope result) {
        ContractRules.require(ContractRules.text(result.requestId()) && ContractRules.text(result.status()),"Missing result metadata");
        var ids=new HashSet<String>();
        for(var ds:result.datasets()){dataset(ds);ContractRules.require(ids.add(ds.id()),"Duplicate result dataset");}
        ContractRules.require(ids.size()<=6,"Too many result datasets");
        if(result.view()!=null)ContractRules.view(result.view(),ids);
        var formulaCells=new HashSet<String>();for(var binding:result.formulaBindings()) {
            var ds=result.datasets().stream().filter(d->d.id().equals(binding.datasetId())).findFirst().orElse(null);
            ContractRules.require(ds!=null && ds.columns().stream().anyMatch(c->c.alias().equals(binding.column())) && formulaCells.add(binding.datasetId()+"/"+binding.column()),"Formula result binding mismatch");
            ContractRules.require(binding.formula()!=null && ContractRules.text(binding.formula().id()) && ContractRules.text(binding.formula().version()) && binding.formula().hash().matches("[a-f0-9]{64}"),"Formula result provenance mismatch");
        }
        var slots=new HashSet<String>();for(var display:result.displayValues()){
            var ds=result.datasets().stream().filter(d->d.id().equals(display.datasetId())).findFirst().orElse(null);
            ContractRules.require(ds!=null && ds.rows().size()==display.values().size() && slots.add(display.datasetId()+"/"+display.slot()),"Display result binding mismatch");
            ContractRules.require(result.formulaBindings().stream().anyMatch(b->b.datasetId().equals(display.datasetId()) && b.formula().equals(display.formula())),"Display provenance mismatch");
        }
    }
    private static boolean valid(ValueType type,Object value) {
        if(value==null)return true;
        return switch(type) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger;
            case DECIMAL -> value instanceof Number n && finite(n);
            case BOOLEAN -> value instanceof Boolean;
            case DATE -> value instanceof LocalDate;
            case TIMESTAMP -> value instanceof Instant || value instanceof LocalDateTime || value instanceof OffsetDateTime;
        };
    }
    private static boolean finite(Number n){try{new BigDecimal(n.toString());return true;}catch(NumberFormatException ex){return false;}}
    /** Interpret only date/timestamp cells according to server-owned columns; no numeric/string coercion. */
    static TypedDataset decode(TypedDataset dataset) {
        var rows=new ArrayList<Map<String,Object>>();
        for(var row:dataset.rows()) {
            var copy=new LinkedHashMap<>(row);
            for(var column:dataset.columns()) {
                Object value=row.get(column.alias());
                if(value instanceof String s)switch(column.type()) {
                    case DATE -> copy.put(column.alias(),LocalDate.parse(s));
                    case TIMESTAMP -> copy.put(column.alias(),timestamp(s));
                    default -> { }
                }
            }
            rows.add(copy);
        }
        var decoded=new TypedDataset(dataset.id(),dataset.columns(),rows,dataset.totalElements(),dataset.page(),dataset.size(),dataset.snapshotAt(),dataset.asOf());
        dataset(decoded);return decoded;
    }
    private static Object timestamp(String value) {
        if(value.endsWith("Z"))return Instant.parse(value);
        try{return OffsetDateTime.parse(value);}catch(java.time.format.DateTimeParseException ex){return LocalDateTime.parse(value);}
    }
}
