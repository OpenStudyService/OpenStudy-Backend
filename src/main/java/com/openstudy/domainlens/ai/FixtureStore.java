package com.openstudy.domainlens.ai;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.error.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;

/** Loads consumer-supplied resources; indexed only by exact text and explicit aliases. */
public final class FixtureStore {
    public record Bundle(String version,List<Entry> fixtures){public Bundle {fixtures=List.copyOf(fixtures);}}
    public record Entry(String ask,List<String> aliases,IrDocument document){public Entry {aliases=List.copyOf(aliases);}}
    private final Map<String,String> documents;
    private FixtureStore(Bundle bundle) {
        ContractRules.require("1".equals(bundle.version()),"Unsupported fixture version");var indexed=new HashMap<String,String>();
        for(var entry:bundle.fixtures()) {
            ContractRules.validate(entry.document());String json=ContractJson.write(entry.document());
            var keys=new ArrayList<>(entry.aliases());keys.add(entry.ask());
            for(String key:keys)ContractRules.require(key!=null && !key.isBlank() && indexed.putIfAbsent(key,json)==null,"Duplicate/missing playback key");
        }
        documents=Map.copyOf(indexed);
    }
    public static FixtureStore json(String json){return new FixtureStore(ContractJson.read(json,Bundle.class));}
    public static FixtureStore load(Path file) {try{return json(Files.readString(file));}catch(IOException ex){throw missing();}}
    public static FixtureStore resource(ClassLoader loader,String path) {
        try(var input=loader.getResourceAsStream(path)){if(input==null)throw missing();return json(new String(input.readAllBytes(),StandardCharsets.UTF_8));}catch(IOException ex){throw missing();}
    }
    public Optional<IrDocument> find(String exact){String json=documents.get(exact);return json==null?Optional.empty():Optional.of(ContractJson.document(json));}
    private static EngineException missing(){return new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Playback fixture resource unavailable");}
}
