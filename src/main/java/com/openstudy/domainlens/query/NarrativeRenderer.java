package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.ir.*;
import java.util.*;
import static com.openstudy.domainlens.api.ir.ContractRules.require;

/** Optional server-only templates. Applications must opt in; no model-generated prose or arithmetic. */
public final class NarrativeRenderer {
    public record Template(String id,String text,Map<String,String> slots) {public Template{slots=Map.copyOf(slots);}}
    private final Map<String,Template> templates;
    public NarrativeRenderer(List<Template> approved){var map=new HashMap<String,Template>();for(var t:approved){require(map.put(t.id(),t)==null,"Duplicate narrative template");var matcher=java.util.regex.Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}").matcher(t.text());var slots=new HashSet<String>();while(matcher.find())slots.add(matcher.group(1));require(slots.equals(t.slots().keySet()),"Narrative slot mismatch");}templates=Map.copyOf(map);}
    public String render(String id,List<TypedDataset> datasets){var template=templates.get(id);require(template!=null,"Unregistered narrative template");String text=template.text();for(var entry:template.slots().entrySet()){var split=entry.getValue().split("\\.");require(split.length==2,"Narrative binding must reference a result column");var dataset=datasets.stream().filter(d->d.id().equals(split[0])).findFirst().orElseThrow();require(dataset.rows().size()==1,"Narrative binding requires a scalar result");require(dataset.columns().stream().anyMatch(c->c.alias().equals(split[1])),"Narrative column absent");text=text.replace("{"+entry.getKey()+"}",Objects.toString(dataset.rows().getFirst().get(split[1]),"값 없음"));}return text;}
}
