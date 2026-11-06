package com.openstudy.domainlens.query;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.error.*;
import java.util.*;
import java.math.BigDecimal;
import java.time.*;

public final class ViewBinder {
    public record Binding(ViewSpec view,List<String> warnings) {public Binding {warnings=List.copyOf(warnings);}}
    private final ComponentCatalog catalog;
    public ViewBinder(ComponentCatalog catalog){this.catalog=catalog;}
    public ComponentCatalog catalog(){return catalog;}
    /** Must succeed before any query is executed; injection and permissions cannot fall back. */
    public void prepare(ViewSpec view,Map<String,List<TypedDataset.Column>> schemas){catalog.validate(view,schemas);}
    public void prepare(ViewSpec view,com.openstudy.domainlens.api.spi.AnalysisRegistry.Definition analysis){prepare(view,analysis.outputs());}
    public Binding bind(ViewSpec view,List<TypedDataset> datasets) {
        var byId=new HashMap<String,TypedDataset>();for(var ds:datasets){ContractRules.require(byId.put(ds.id(),ds)==null,"Duplicate result dataset");validate(ds);}
        prepare(view,datasets.stream().collect(java.util.stream.Collectors.toMap(TypedDataset::id,TypedDataset::columns)));
        boolean fallback=false;
        for(var c:view.components()) {
            var ds=byId.get(c.datasetId());var def=catalog.components().stream().filter(d->d.id().equals(c.componentRef().id())).findFirst().orElseThrow();
            if(ds.rows().size()>def.limits().maxRows())fallback=true;
            for(var entry:def.dataSlots().entrySet())if(c.bindings().containsKey(entry.getKey())){var slot=entry.getValue();if(ds.rows().size()<slot.minRows() || ds.rows().size()>slot.maxRows())fallback=true;}
            if(c.componentRef().id().equals("DonutChart")) {
                String alias=(String)c.bindings().get("value");if(ds.rows().stream().anyMatch(row->row.get(alias)!=null && new BigDecimal(row.get(alias).toString()).signum()<0))fallback=true;
            }
        }
        if(!fallback)return new Binding(view,List.of());
        var table=catalog.components().stream().filter(d->d.id().equals("DataTable")||d.id().equals("DATA_TABLE")||d.id().equals("TABLE")).findFirst().orElseThrow(()->new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Safe table unavailable"));
        var components=new ArrayList<ViewSpec.Component>();var leaves=new ArrayList<Layout>();int n=0;
        for(var ds:datasets){String id="fallback"+(n++);components.add(new ViewSpec.Component(id,new ViewSpec.Ref(table.id(),table.version()),ds.id(),Map.of("columns",ds.columns().stream().map(TypedDataset.Column::alias).toList()),Map.of(),List.of()));leaves.add(Layout.leaf(id));}
        var safe=new ViewSpec(catalog.catalogVersion(),components,Layout.stack(leaves));prepare(safe,datasets.stream().collect(java.util.stream.Collectors.toMap(TypedDataset::id,TypedDataset::columns)));
        return new Binding(safe,List.of(EngineErrorCode.BINDING_FALLBACK.name()));
    }
    private void validate(TypedDataset ds) { ResultRules.dataset(ds); }
}
