package com.openstudy.domainlens.api.ir;

import com.openstudy.domainlens.api.error.*;
import java.util.*;

/** Structural validation shared by playback, storage and semantic validation. */
public final class ContractRules {
    private ContractRules() {}
    public static void require(boolean valid, String message) {
        if (!valid) throw new EngineException(EngineErrorCode.IR_INVALID,message);
    }
    public static boolean text(String value) { return value!=null && !value.isBlank(); }
    public static void semanticOption(String name) {
        require(text(name) && !Set.of("style","className","html","innerHTML","dangerouslySetInnerHTML","url","href","src","api","apiPath","code","script","onClick").contains(name)
                && !name.startsWith("on") && !name.startsWith("data-") && !name.startsWith("aria-"),"Unsafe option");
    }
    public static void event(ViewSpec.Event e) {
        require(e!=null && text(e.event()) && e.actionId()!=null
                && Set.of("page","sort","drillDetail","refineFilter","requestCsv").contains(e.actionId()),"Invalid action/event");
    }
    /** Structural validation. Registered-analysis output IDs are resolved separately against the server registry. */
    public static void validate(IrDocument ir) {
        require(ir != null && (IrDocument.VERSION.equals(ir.schemaVersion()) || IrDocument.LEGACY_VERSION.equals(ir.schemaVersion())),"Unsupported IR version");
        require(ir.status()!=null && ir.clarificationOptions().size()<=3,"Invalid status/options");
        if(ir.status()==IrStatus.READY)require(ir.reasonCode()==null,"READY has failure code");
        else require((IrDocument.LEGACY_VERSION.equals(ir.schemaVersion()) && ir.reasonCode()==null) || ir.reasonCode()!=null && ir.reasonCode().permits(ir.status()),"Invalid IR reason code/status");
        var choices=new HashSet<String>();
        for(var option:ir.clarificationOptions())require(text(option.id()) && text(option.label()) && choices.add(option.id()),"Invalid clarification option");
        require(ir.contextRefs().stream().allMatch(ContractRules::text),"Invalid context reference");
        require(ir.queries().size()<=6,"Too many datasets");
        var ids=new HashSet<String>();
        for(var q:ir.queries()) {
            require(q.id()!=null && !q.id().isBlank() && ids.add(q.id()),"Duplicate/missing dataset");
            require(text(q.source()) && text(q.grain()) && q.mode()!=null,"Missing query contract");
            require(q.pagination()!=null && q.pagination().page()>=0 && q.pagination().size()>0 && q.pagination().size()<=100,"Invalid page");
            require(q.limit()>0 && q.limit()<=50,"Invalid aggregate limit");
            require(q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS ? text(q.analysisId()) : q.analysisId()==null,"Missing analysis");
            var aliases=new HashSet<String>();
            for(var s:java.util.stream.Stream.concat(q.dimensions().stream(),q.metrics().stream()).toList())
                require(text(s.field()) && s.alias()!=null && s.alias().matches("[A-Za-z][A-Za-z0-9_]*") && aliases.add(s.alias()),"Duplicate/invalid alias");
            for(var o:q.orderBy()) require((q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS ? text(o.alias()) : aliases.contains(o.alias())) && o.direction()!=null,"Invalid order alias");
            if(q.filter()!=null) require(filter(q.filter(),1)<=16,"Too many filter leaves");
        }
        if(ir.status()==IrStatus.READY) require(!ir.queries().isEmpty() && ir.view()!=null,"READY needs queries/view");
        else require(ir.queries().isEmpty() && ir.view()==null,"Non-READY cannot execute");
        if(ir.status()==IrStatus.NEEDS_CLARIFICATION) require(ir.clarification()!=null && !ir.clarification().isBlank(),"Missing clarification");
        if(ir.status()==IrStatus.UNSUPPORTED) require(ir.unsupportedReason()!=null && !ir.unsupportedReason().isBlank(),"Missing unsupported reason");
        if(ir.status()!=IrStatus.NEEDS_CLARIFICATION)require(ir.clarification()==null && ir.clarificationOptions().isEmpty(),"Unexpected clarification");
        if(ir.status()!=IrStatus.UNSUPPORTED)require(ir.unsupportedReason()==null,"Unexpected unsupported reason");
        if(ir.view()!=null) {
            viewStructure(ir.view());
            if(ir.queries().stream().noneMatch(q->q.mode()==QuerySpec.Mode.REGISTERED_ANALYSIS))view(ir.view(),ids);
        }
    }
    /** Resolves all executable dataset names, including multi-output analyses, without enabling their executors. */
    public static Set<String> datasets(IrDocument ir, com.openstudy.domainlens.api.spi.AnalysisRegistry analyses) {
        validate(ir);
        var ids=new HashSet<String>();
        for(var q:ir.queries()) {
            if(q.mode()!=QuerySpec.Mode.REGISTERED_ANALYSIS)require(ids.add(q.id()),"Duplicate output dataset");
            else {
                var definition=analyses==null?null:analyses.definition(q.analysisId()).orElse(null);
                if(definition==null)throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Unknown analysis");
                require(definition.source().equals(q.source()) && definition.grain().equals(q.grain()),"Analysis source/grain mismatch");
                require(!definition.outputs().isEmpty(),"Missing analysis outputs");
                definition.outputs().forEach((id,columns)->{require(text(id) && ids.add(id),"Duplicate output dataset");ResultRules.columns(columns);});
                for(var order:q.orderBy())require(definition.outputs().values().stream().anyMatch(columns->columns.stream().anyMatch(c->c.alias().equals(order.alias()))),"Unknown analysis order alias");
            }
        }
        require(ids.size()<=6,"Too many output datasets");
        if(ir.view()!=null)view(ir.view(),ids);
        return Set.copyOf(ids);
    }
    private static int filter(Filter f,int depth) {
        require(depth<=4,"Filter too deep");
        if(f instanceof Filter.Leaf l) {
            require(text(l.field()) && l.operator()!=null,"Invalid filter");
            int n=l.values().size();
            require(switch(l.operator()) { case IS_NULL -> n==0; case BETWEEN -> n==2; case IN -> n>0 && n<=50; default -> n==1; },"Invalid filter values");
            require(l.values().stream().allMatch(v->v instanceof String || v instanceof Number || v instanceof Boolean),"Invalid scalar");
            return 1;
        }
        var g=(Filter.Group)f; require(g.junction()!=null && !g.children().isEmpty(),"Empty filter");
        return g.children().stream().mapToInt(c->filter(c,depth+1)).sum();
    }
    public static void view(ViewSpec view,Set<String> datasets) {
        viewStructure(view);
        require(datasets.size()<=6,"Too many datasets");
        for(var c:view.components())require(c.datasetId()!=null && datasets.contains(c.datasetId()),"Unknown dataset");
    }
    public static void viewStructure(ViewSpec view) {
        require(view!=null && view.catalogVersion()!=null && !view.catalogVersion().isBlank() && view.components().size()<=24 && !view.components().isEmpty(),"Invalid view");
        var ids=new HashSet<String>();
        for(var c:view.components()) {
            require(text(c.id()) && ids.add(c.id()) && c.componentRef()!=null && text(c.componentRef().id()) && text(c.componentRef().version()),"Invalid component");
            require(text(c.datasetId()),"Missing dataset");
            c.options().keySet().forEach(ContractRules::semanticOption);
            require(c.options().values().stream().allMatch(v->v instanceof String || v instanceof Number || v instanceof Boolean),"Invalid option value");
            for(var binding:c.bindings().entrySet()) {
                require(text(binding.getKey()),"Invalid binding slot");
                var value=binding.getValue();
                require(value instanceof String str && text(str) || value instanceof List<?> list && !list.isEmpty()
                        && list.stream().allMatch(v->v instanceof String str && text(str)) && new HashSet<>(list).size()==list.size(),"Invalid binding");
            }
            require(new HashSet<>(c.events()).size()==c.events().size(),"Duplicate event");
            c.events().forEach(ContractRules::event);
        }
        require(view.layout()!=null && view.layout().componentId()==null,"Root must be a container");
        var used=new HashSet<String>(); walk(view.layout(),1,ids,used);
        require(used.equals(ids),"Missing component reference");
    }
    private static void walk(Layout l,int depth,Set<String> ids,Set<String> used) {
        require(l!=null,"Missing layout");
        if(l.componentId()!=null) { require(l.kind()==null && l.children()==null && ids.contains(l.componentId()) && used.add(l.componentId()),"Duplicate/unknown layout reference"); return; }
        require(depth<=4 && l.kind()!=null && Set.of("stack","grid","split").contains(l.kind()),"Invalid layout kind/depth");
        var children=l.children();
        require(children!=null && !children.isEmpty() && (!"split".equals(l.kind()) || children.size()==2),"Invalid layout children");
        children.forEach(c->walk(c,depth+1,ids,used));
    }
}
