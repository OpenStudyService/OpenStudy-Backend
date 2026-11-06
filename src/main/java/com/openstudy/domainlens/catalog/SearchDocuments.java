package com.openstudy.domainlens.catalog;
import com.openstudy.domainlens.api.retrieval.SearchDocument;
import java.util.*;
/** Enumerates approved metadata only; has no EntityManager or business DataSource. */
public final class SearchDocuments {
    public List<SearchDocument> build(SemanticRegistry registry) {
        var docs=new ArrayList<SearchDocument>();
        registry.sources().values().forEach(s->add(docs,s.id(),s.metadata(),Set.of(s.id())));
        registry.fields().values().forEach(f->add(docs,f.id(),f.metadata(),Set.of(f.source(),f.id())));
        registry.metrics().values().forEach(m->{
            var aliases=registry.terms().stream().filter(t->t.target().equals(m.id())).flatMap(t->t.aliases().stream()).sorted().toList();
            String measure=m.field()==null?"":registry.fields().get(m.field()).metadata().name();
            String context=m.contextFields().stream().sorted().map(id->registry.fields().get(id).metadata().name()).collect(java.util.stream.Collectors.joining(" "));
            docs.add(new SearchDocument(m.id(),SearchText.sanitize(m.id()+" "+registry.sources().get(m.source()).metadata().name()+" "+m.grain()+" "+m.aggregate()+" "+measure+" "+m.unit()+" "+String.join(" ",aliases)+" "+context),Set.of(m.source(),m.id())));
        });
        registry.relations().values().forEach(r->docs.add(new SearchDocument(r.id(),SearchText.sanitize(r.id()+" "+registry.sources().get(r.source()).metadata().name()+" "+r.meaning()+" "+registry.sources().get(r.target()).metadata().name()),java.util.stream.Stream.of(r.id(),r.source(),r.target()).collect(java.util.stream.Collectors.toUnmodifiableSet()))));
        registry.terms().forEach(t->docs.add(new SearchDocument(t.id(),SearchText.sanitize(t.id()+" "+String.join(" ",t.aliases())),Set.of(t.target()))));
        registry.examples().forEach(e->docs.add(new SearchDocument(e.id(),SearchText.sanitize(e.id()+" "+String.join(" ",new TreeSet<>(e.references()))),e.references())));
        return docs.stream().sorted(Comparator.comparing(SearchDocument::id)).toList();
    }
    private void add(List<SearchDocument> docs,String id,SemanticMetadata m,Set<String> refs){docs.add(new SearchDocument(id,SearchText.sanitize(id+" "+m.name()+" "+m.description()+" "+String.join(" ",m.aliases())),refs));}
}
