package com.openstudy.domainlens.api.ir;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;
/** Inline container or component reference; null fields are omitted on the wire. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Layout(@Nullable String kind, @Nullable List<Layout> children, @Nullable String componentId) {
    public Layout { if(children!=null) children=List.copyOf(children); }
    public static Layout leaf(String id) { return new Layout(null,null,id); }
    public static Layout stack(List<Layout> children) { return new Layout("stack",children,null); }
}
