package com.openstudy.domainlens.api.ir;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record ViewSpec(String catalogVersion, List<Component> components, Layout layout) {
    public ViewSpec { components=List.copyOf(components); }
    public record Ref(String id, String version) {}
    public record Event(String event, String actionId) {}
    public record Component(String id, Ref componentRef, @Nullable String datasetId,
            Map<String,Object> bindings, Map<String,Object> options, List<Event> events) {
        public Component { bindings=Map.copyOf(bindings); options=Map.copyOf(options); events=List.copyOf(events); }
    }
}
