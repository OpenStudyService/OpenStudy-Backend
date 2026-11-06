package com.openstudy.domainlens.catalog;
import com.openstudy.domainlens.api.retrieval.*;
import com.openstudy.domainlens.api.error.*;
import java.util.*;
/** Immutable index built from a complete compatible set. No partial publication. */
public record EmbeddingIndex(String version,String model,int dimensions,List<Entry> entries) {
    public record Entry(SearchDocument document,List<Double> vector){public Entry{vector=List.copyOf(vector);}}
    public EmbeddingIndex { entries=List.copyOf(entries); }
    public static EmbeddingIndex build(List<SearchDocument> documents,EmbeddingGateway gateway,EmbeddingStore store) {
        var docs=documents.stream().sorted(Comparator.comparing(SearchDocument::id)).toList();
        var vectors=new HashMap<String,EmbeddingStore.Vector>();var missing=new ArrayList<SearchDocument>();
        for(var d:docs){var found=store.find(key(d,gateway));if(found.isPresent() && found.get().model().equals(gateway.model()) && found.get().dimensions()==gateway.dimensions())vectors.put(d.id(),found.get());else missing.add(d);}
        if(!missing.isEmpty()) {
            final List<List<Double>> result;
            try {result=gateway.embedDocuments(missing.stream().map(d->SearchText.sanitize(d.text())).toList());}
            catch(RuntimeException ex){throw new EngineException(EngineErrorCode.NOT_READY,"Compatible embedding index unavailable");}
            if(result.size()!=missing.size())throw new EngineException(EngineErrorCode.NOT_READY,"Embedding response count mismatch");
            var batch=new ArrayList<EmbeddingStore.Vector>();
            for(int i=0;i<missing.size();i++){var d=missing.get(i);var v=new EmbeddingStore.Vector(key(d,gateway),gateway.model(),gateway.dimensions(),result.get(i));batch.add(v);vectors.put(d.id(),v);}
            store.save(batch);
        }
        String version=SearchText.hash(String.join("\n",docs.stream().map(d->d.id()+":"+key(d,gateway)).toList()));
        return new EmbeddingIndex(version,gateway.model(),gateway.dimensions(),docs.stream().map(d->new Entry(new SearchDocument(d.id(),SearchText.sanitize(d.text()),d.references()),vectors.get(d.id()).values())).toList());
    }
    private static String key(SearchDocument d,EmbeddingGateway g){return SearchText.hash(SearchText.VERSION+"\n"+g.model()+"\n"+g.dimensions()+"\n"+SearchText.sanitize(d.text()));}
}
