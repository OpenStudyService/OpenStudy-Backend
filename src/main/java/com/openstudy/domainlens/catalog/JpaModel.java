package com.openstudy.domainlens.catalog;
import java.util.*;
public record JpaModel(List<Entity> entities,List<String> diagnostics) {
    public JpaModel { entities=List.copyOf(entities);diagnostics=List.copyOf(diagnostics); }
    public record Entity(Class<?> javaType,String jpaName,String table,String id,boolean exposed,
            String readPolicy,String rowPolicy,String fieldPolicy,List<Field> fields,List<Relation> relations,
            SemanticMetadata metadata) {
        public Entity { fields=List.copyOf(fields);relations=List.copyOf(relations);Objects.requireNonNull(metadata); }
        public Entity(Class<?> javaType,String jpaName,String table,String id,boolean exposed,
                String readPolicy,String rowPolicy,String fieldPolicy,List<Field> fields,List<Relation> relations) {
            this(javaType,jpaName,table,id,exposed,readPolicy,rowPolicy,fieldPolicy,fields,relations,SemanticMetadata.EMPTY);
        }
    }
    public record Field(String path,String column,Class<?> javaType,boolean key,boolean optional,
            String id,boolean exposed,String fieldPolicy,Set<String> operations,SemanticMetadata metadata) {
        public Field { operations=Set.copyOf(operations);Objects.requireNonNull(metadata); }
        public Field(String path,String column,Class<?> javaType,boolean key,boolean optional,
                String id,boolean exposed,String fieldPolicy,Set<String> operations) {
            this(path,column,javaType,key,optional,id,exposed,fieldPolicy,operations,SemanticMetadata.EMPTY);
        }
    }
    public record Relation(String path,Class<?> target,String cardinality,boolean owning,String mappedBy,
            boolean optional,List<String> joinColumns,List<String> referencedColumns,String joinTable,
            String id,String direction,String joinUse,String meaning) {
        public Relation { joinColumns=List.copyOf(joinColumns);referencedColumns=List.copyOf(referencedColumns); }
        public Relation(String path,Class<?> target,String cardinality,boolean owning,String mappedBy,
                boolean optional,List<String> joinColumns,List<String> referencedColumns,String joinTable,
                String id,String direction,String joinUse) {
            this(path,target,cardinality,owning,mappedBy,optional,joinColumns,referencedColumns,joinTable,id,direction,joinUse,"");
        }
        public boolean amplifies() { return cardinality.endsWith("TO_MANY"); }
    }
}
