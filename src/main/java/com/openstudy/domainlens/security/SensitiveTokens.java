package com.openstudy.domainlens.security;

import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.catalog.*;
import java.util.*;
import java.util.regex.Pattern;

/** One generation request's non-serializable, closeable vault. Never shared or persisted. */
public final class SensitiveTokens implements AutoCloseable {
    public static final String PREFIX="[DLT:";
    private static final Pattern TOKEN=Pattern.compile("\\[DLT:(EMAIL|PHONE|SECRET):[a-p]{32}\\]");
    private record Entry(SearchText.Kind kind,String value) {
        @Override public String toString(){return "Entry[redacted]";}
    }
    private final String owner;
    private final Map<String,Entry> originals=new HashMap<>();
    private boolean closed;
    public SensitiveTokens(UserContext user){owner=Identity.require(user).subjectId();}

    /** Idempotent within this vault, but pre-existing/forged tokens are rejected. */
    public synchronized String protect(String text) {
        check();checkKnownTokens(text);
        return SearchText.replace(text,(kind,value)-> {
            var entry=new Entry(kind,value.strip());
            for(var existing:originals.entrySet())if(existing.getValue().equals(entry))return existing.getKey();
            // Alphabet-only nonce prevents phone detection from touching a token's identifier.
            String hex=UUID.randomUUID().toString().replace("-","");var nonce=new StringBuilder();
            for(char digit:hex.toCharArray())nonce.append((char)('a'+Character.digit(digit,16)));
            String token=PREFIX+kind+":"+nonce+"]";originals.put(token,entry);return token;
        });
    }
    public synchronized Filter protect(Filter filter) {
        check();
        if(filter instanceof Filter.Group group)return new Filter.Group(group.junction(),group.children().stream().map(this::protect).toList());
        if(filter instanceof Filter.Leaf leaf)return new Filter.Leaf(leaf.field(),leaf.operator(),leaf.values().stream().map(v->v instanceof String s?protect(s):v).toList());
        return null;
    }

    /** Only exact tokens in authorized STRING filter operands can be restored. */
    public synchronized IrDocument restore(IrDocument ir,SemanticRegistry registry,PolicyEngine policies,UserContext user) {
        check();Identity.owner(user,owner);ContractRules.validate(ir);
        var queries=new ArrayList<QuerySpec>();var stripped=new ArrayList<QuerySpec>();
        for(var q:ir.queries()) {
            queries.add(withFilter(q,restore(q.filter(),registry,policies,user)));
            stripped.add(withFilter(q,withoutValues(q.filter())));
        }
        // Tokens in IDs, labels, view options, clarification, or any other location are invalid.
        var remainder=new IrDocument(ir.schemaVersion(),ir.status(),ir.clarification(),ir.clarificationOptions(),ir.unsupportedReason(),stripped,ir.view(),ir.contextRefs(),ir.reasonCode());
        if(ContractJson.write(remainder).contains(PREFIX))throw invalid();
        return new IrDocument(ir.schemaVersion(),ir.status(),ir.clarification(),ir.clarificationOptions(),ir.unsupportedReason(),queries,ir.view(),ir.contextRefs(),ir.reasonCode());
    }
    private Filter restore(Filter filter,SemanticRegistry registry,PolicyEngine policies,UserContext user) {
        if(filter instanceof Filter.Group group)return new Filter.Group(group.junction(),group.children().stream().map(f->restore(f,registry,policies,user)).toList());
        if(!(filter instanceof Filter.Leaf leaf))return null;
        var values=new ArrayList<Object>();
        for(Object value:leaf.values()) {
            if(value instanceof String text && text.contains(PREFIX)) {
                var entry=originals.get(text);var field=registry.fields().get(leaf.field());
                if(entry==null || entry.kind()==SearchText.Kind.SECRET || field==null || field.type()!=ValueType.STRING
                        || !field.operations().contains(leaf.operator().name()))throw invalid();
                policies.field(leaf.field(),"filter",user);values.add(entry.value());
            }else values.add(value);
        }
        return new Filter.Leaf(leaf.field(),leaf.operator(),values);
    }
    private Filter withoutValues(Filter filter) {
        if(filter instanceof Filter.Group group)return new Filter.Group(group.junction(),group.children().stream().map(this::withoutValues).toList());
        if(filter instanceof Filter.Leaf leaf)return new Filter.Leaf(leaf.field(),leaf.operator(),List.of());
        return null;
    }
    private QuerySpec withFilter(QuerySpec q,Filter filter) {
        return new QuerySpec(q.id(),q.source(),q.grain(),q.mode(),q.dimensions(),q.metrics(),filter,q.orderBy(),q.pagination(),q.limit(),q.analysisId());
    }
    private void checkKnownTokens(String text) {
        var matcher=TOKEN.matcher(text);int end=0;
        while(matcher.find()) {
            if(text.substring(end,matcher.start()).contains(PREFIX) || !originals.containsKey(matcher.group()))throw invalid();
            end=matcher.end();
        }
        if(text.substring(end).contains(PREFIX))throw invalid();
    }
    private void check(){if(closed)throw invalid();}
    private static EngineException invalid(){return new EngineException(EngineErrorCode.IR_INVALID,"Invalid sensitive token use");}
    @Override public synchronized void close(){originals.clear();closed=true;}
    @Override public String toString(){return "SensitiveTokens[redacted]";}
}
