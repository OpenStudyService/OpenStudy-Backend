package com.openstudy.domainlens.api.ir;

import com.openstudy.domainlens.api.error.*;
import java.util.*;

/** Consumer-registered definitions; this contract is also consumed by UI registries. */
public record ComponentCatalog(String catalogVersion, List<Definition> components, Set<String> compatibleVersions) {
    public ComponentCatalog(String version,List<Definition> components){this(version,components,Set.of());}
    public ComponentCatalog {
        ContractRules.require(ContractRules.text(catalogVersion),"Missing catalog version");
        components=List.copyOf(components);compatibleVersions=compatibleVersions==null?Set.of():Set.copyOf(compatibleVersions);
        if(components.stream().map(Definition::id).distinct().count()!=components.size())
            throw new IllegalArgumentException("Duplicate component definition");
    }
    public record Slot(Set<ValueType> types, boolean required, boolean multiple, int minRows, int maxRows) {
        public Slot { types=Set.copyOf(types); ContractRules.require(!types.isEmpty() && minRows>=0 && maxRows>=minRows,"Invalid slot limits/types"); }
    }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Option(String type, @com.fasterxml.jackson.annotation.JsonProperty("enum")
            @org.jspecify.annotations.Nullable List<String> allowedValues,
            @org.jspecify.annotations.Nullable Integer maxLength) {
        public Option(String type,List<String> allowedValues){this(type,allowedValues,null);}
        public Option {
            ContractRules.require(maxLength==null || (type.equals("string") && maxLength>=0),"Invalid option length");
            ContractRules.require(Set.of("string","boolean","integer","number").contains(type),"Invalid option type");
            if(allowedValues!=null) {
                allowedValues=List.copyOf(allowedValues);
                ContractRules.require(type.equals("string") && !allowedValues.isEmpty() && new HashSet<>(allowedValues).size()==allowedValues.size(),"Invalid option enum");
            }
        }
    }
    public record OptionsSchema(String type, Map<String,Option> properties, List<String> required, boolean additionalProperties) {
        public OptionsSchema {
            properties=Map.copyOf(properties);required=List.copyOf(required);
            ContractRules.require("object".equals(type) && !additionalProperties && properties.keySet().containsAll(required)
                    && new HashSet<>(required).size()==required.size(),"Invalid options schema");
            properties.keySet().forEach(ContractRules::semanticOption);
        }
        public static OptionsSchema standard(String... names) {
            var properties=new LinkedHashMap<String,Option>();
            for(String name:names)properties.put(name,switch(name) {
                case "title","subtitle","unit","currency","xAxis","yAxis" -> new Option("string",null,120);
                case "density" -> new Option("string",List.of("comfortable","compact"));
                default -> throw new IllegalArgumentException("Unknown standard option");
            });
            return new OptionsSchema("object",properties,List.of(),false);
        }
        @SuppressWarnings("unchecked")
        public Map<String,Object> jsonSchema(){return ContractJson.read(ContractJson.write(this),Map.class);}
        public void validate(Map<String,Object> options){ContractSchema.validate(options,jsonSchema());}
    }
    public record Composition(boolean leaf, Set<String> parents) {
        public Composition {
            parents=Set.copyOf(parents);
            ContractRules.require(leaf && !parents.isEmpty() && Set.of("stack","grid","split").containsAll(parents),"Invalid component composition");
        }
    }
    public record Limits(int maxRows) {
        public Limits { ContractRules.require(maxRows>=1 && maxRows<=1000,"Invalid component row limit"); }
    }
    public record Definition(String id, String version, String description, Map<String,Slot> dataSlots,
            OptionsSchema optionsSchema, Set<ViewSpec.Event> events, Composition composition,
            Limits limits, String accessibility, Set<String> analysisIds) {
        public Definition(String id,String version,String description,Map<String,Slot> dataSlots,OptionsSchema optionsSchema,Set<ViewSpec.Event> events,Composition composition,Limits limits,String accessibility){this(id,version,description,dataSlots,optionsSchema,events,composition,limits,accessibility,Set.of());}
        public Definition {
            dataSlots=Map.copyOf(dataSlots);events=Set.copyOf(events);analysisIds=analysisIds==null?Set.of():Set.copyOf(analysisIds);
            ContractRules.require(ContractRules.text(id) && ContractRules.text(version) && ContractRules.text(description)
                    && ContractRules.text(accessibility) && optionsSchema!=null && composition!=null && limits!=null,"Invalid component definition");
            dataSlots.forEach((name,slot)->ContractRules.require(ContractRules.text(name) && slot.maxRows()<=limits.maxRows(),"Invalid slot definition"));
            events.forEach(ContractRules::event);
        }
    }
    public void validate(ViewSpec view, Map<String,List<TypedDataset.Column>> schemas) {
        if(!catalogVersion.equals(view.catalogVersion())&&!compatibleVersions.contains(view.catalogVersion())) fail("Catalog version mismatch");
        ContractRules.view(view,schemas.keySet());
        for(var c:view.components()) {
            var def=components.stream().filter(d->d.id().equals(c.componentRef().id()) && d.version().equals(c.componentRef().version()))
                    .findFirst().orElseThrow(()->new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Unsupported component/version"));
            def.optionsSchema().validate(c.options());
            ContractRules.require(def.events().containsAll(c.events()),"Invalid events");
            ContractRules.require(def.dataSlots().keySet().containsAll(c.bindings().keySet()),"Unknown binding slot");
            var columns=schemas.get(c.datasetId());
            ResultRules.columns(columns);
            for(var entry:def.dataSlots().entrySet()) {
                Object binding=c.bindings().get(entry.getKey());var slot=entry.getValue();
                ContractRules.require(binding!=null || !slot.required(),"Missing slot");if(binding==null)continue;
                var aliases=slot.multiple() && binding instanceof List<?> list ? list : List.of(binding);
                ContractRules.require(!aliases.isEmpty() && new HashSet<>(aliases).size()==aliases.size() && (slot.multiple() ? binding instanceof List<?> : binding instanceof String),"Invalid slot cardinality");
                for(var alias:aliases) ContractRules.require(columns.stream().anyMatch(col->col.alias().equals(alias) && slot.types().contains(col.type())),"Unknown alias/type");
            }
        }
        parents(view.layout(),view);
    }
    private void parents(Layout layout,ViewSpec view) {
        ContractRules.require(layout.componentId()==null,"Root must be a container");
        for(var child:layout.children()) {
            if(child.componentId()==null) { parents(child,view); continue; }
            var c=view.components().stream().filter(x->x.id().equals(child.componentId())).findFirst().orElseThrow();
            var d=components.stream().filter(x->x.id().equals(c.componentRef().id())).findFirst().orElseThrow();
            ContractRules.require(d.composition().parents().contains(layout.kind()),"Unsupported parent layout");
        }
    }
    public static final Set<String> OPENSTUDY_COMPONENT_TYPES = Set.of(
            "KPI", "BAR_CHART", "LINE_CHART", "RANKED_BAR_CHART",
            "DATA_TABLE", "TABLE", "HEATMAP", "SEAT_MAP",
            "WEEK_CALENDAR", "TASK_LIST", "CLARIFICATION");

    public static boolean isSupportedOpenStudyType(String type) {
        return type != null && OPENSTUDY_COMPONENT_TYPES.contains(type);
    }

    public static ComponentCatalog openStudyDefault() {
        var allTypes = Set.of(ValueType.values());
        var numeric = new Slot(Set.of(ValueType.INTEGER, ValueType.DECIMAL), false, false, 0, 366);
        var category = new Slot(Set.of(ValueType.STRING, ValueType.DATE, ValueType.TIMESTAMP, ValueType.INTEGER), false, false, 0, 366);
        var columns = new Slot(allTypes, false, true, 0, 1000);
        var values = new Slot(allTypes, false, true, 0, 100);
        var commonSlots = Map.of("columns", columns, "values", values, "category", category, "series", category, "value", numeric);
        var composition = new Composition(true, Set.of("stack", "grid", "split"));
        var events = Set.of(
                new ViewSpec.Event("pageChange", "page"),
                new ViewSpec.Event("sortChange", "sort"),
                new ViewSpec.Event("select", "drillDetail"),
                new ViewSpec.Event("filter", "refineFilter"),
                new ViewSpec.Event("export", "requestCsv"));
        var options = OptionsSchema.standard("title", "subtitle", "density", "unit", "currency", "xAxis", "yAxis");
        var definitions = new ArrayList<Definition>();
        // Registered analyses build their own ViewSpec with these ids; their shared slots stay as they were.
        for (String id : List.of(
                "KPI", "BAR_CHART", "LINE_CHART", "RANKED_BAR_CHART",
                "DATA_TABLE", "TABLE", "HEATMAP", "SEAT_MAP",
                "WEEK_CALENDAR", "TASK_LIST", "CLARIFICATION")) {
            definitions.add(new Definition(id, "1.0.0", id, commonSlots, options, events, composition, new Limits(1000), "표 대안과 접근성 레이블을 제공한다."));
        }
        // #232: the generation path's compiler binds each slot to the single column whose type fits, so these ids need
        // per-component slots. With the shared slots a table of (STRING, INTEGER) matched "category" twice and failed.
        var table = Map.of("columns", new Slot(allTypes, true, true, 0, 1000));
        var label = new Slot(Set.of(ValueType.STRING), true, false, 0, 366);
        var time = new Slot(Set.of(ValueType.DATE, ValueType.TIMESTAMP), true, false, 0, 366);
        var amount = new Slot(Set.of(ValueType.INTEGER, ValueType.DECIMAL), true, false, 0, 366);
        var generated = new java.util.LinkedHashMap<String, Map<String, Slot>>();
        generated.put("DataTable", table);
        generated.put("PaginatedDataTable", table);
        generated.put("DetailCard", table);
        generated.put("KpiGroup", Map.of("values", new Slot(Set.of(ValueType.INTEGER, ValueType.DECIMAL), true, true, 0, 1)));
        generated.put("BarChart", Map.of("category", label, "value", amount));
        generated.put("RankList", Map.of("category", label, "value", amount));
        generated.put("DonutChart", Map.of("category", label, "value", amount));
        generated.put("LineChart", Map.of("category", time, "value", amount));
        generated.put("AreaChart", Map.of("category", time, "value", amount));
        generated.forEach((id, slots) -> definitions.add(new Definition(id, "1.0.0", id, slots, options, events, composition, new Limits(1000), "표 대안과 접근성 레이블을 제공한다.")));
        return new ComponentCatalog("openstudy-ui-1.0.0", definitions, Set.of("ui-1.0.0", "ui-1.2.0", "ui-1.3.0", "ui-1.4.0", "1.0.0"));
    }

    private static void fail(String message) { throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,message); }
}
