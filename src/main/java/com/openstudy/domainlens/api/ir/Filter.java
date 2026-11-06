package com.openstudy.domainlens.api.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({@JsonSubTypes.Type(value=Filter.Leaf.class,name="leaf"),
    @JsonSubTypes.Type(value=Filter.Group.class,name="group")})
public sealed interface Filter permits Filter.Leaf, Filter.Group {
    enum Operator { EQ, NE, IN, GT, GTE, LT, LTE, BETWEEN, IS_NULL }
    enum Junction { AND, OR }
    record Leaf(String field, Operator operator, List<Object> values) implements Filter {
        public Leaf { values = List.copyOf(values); }
    }
    record Group(Junction junction, List<Filter> children) implements Filter {
        public Group { children = List.copyOf(children); }
    }
}
