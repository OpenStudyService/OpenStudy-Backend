package com.openstudy.domainlens.catalog;
import com.openstudy.domainlens.api.semantic.*;
import jakarta.persistence.*;
import jakarta.persistence.metamodel.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/** Uses JPA's effective access model, then merges reviewed semantic field/getter annotations. */
public final class JpaModelCollector {
    public JpaModel collect(EntityManagerFactory factory) {
        var result=new ArrayList<JpaModel.Entity>();var diagnostics=new ArrayList<String>();
        var entities=factory.getMetamodel().getEntities().stream().sorted(Comparator.comparing(EntityType::getName)).toList();
        var targets=new HashMap<String,Class<?>>();
        for(var entity:entities) {
            Class<?> type=entity.getJavaType();
            targets.put(entity.getName(),type);
            targets.put(type.getSimpleName(),type);
            targets.put(type.getName(),type);
            var semantic=type.getAnnotation(SemanticEntity.class);
            if(semantic!=null && !semantic.id().isBlank()) {
                targets.put(semantic.id(),type);
                if(semantic.id().startsWith("openstudy."))targets.put(semantic.id().substring("openstudy.".length()),type);
                else targets.put("openstudy."+semantic.id(),type);
            }
            var table=type.getAnnotation(Table.class);
            if(table!=null && !table.name().isBlank())targets.put(table.name(),type);
        }
        for(var entity:entities) {
            Class<?> type=entity.getJavaType();var semantic=type.getAnnotation(SemanticEntity.class);var policy=type.getAnnotation(SemanticPolicy.class);var table=type.getAnnotation(Table.class);
            var fields=new ArrayList<JpaModel.Field>();var relations=new ArrayList<JpaModel.Relation>();
            fields(entity,type,"",false,fields,relations,diagnostics,targets);
            result.add(new JpaModel.Entity(type,entity.getName(),table==null || table.name().isBlank()?entity.getName():table.name(),
                semantic==null?"":semantic.id(),semantic!=null && semantic.exposed(),policy==null?"":policy.readPolicy(),policy==null?"":policy.rowPolicy(),policy==null?"":policy.fieldPolicy(),fields,relations,SemanticMetadata.from(semantic)));
        }
        return new JpaModel(result,diagnostics);
    }
    private void fields(ManagedType<?> managed,Class<?> owner,String prefix,boolean key,List<JpaModel.Field> fields,List<JpaModel.Relation> relations,List<String> diagnostics,Map<String,Class<?>> targets) {
        for(var attr:managed.getAttributes().stream().sorted(Comparator.comparing(Attribute::getName)).toList()) {
            String path=prefix+attr.getName();var member=(AnnotatedElement)attr.getJavaMember();
            boolean id=key || (attr instanceof SingularAttribute<?,?> singular && singular.isId());
            boolean optional=attr instanceof SingularAttribute<?,?> singular && singular.isOptional();
            if(attr.getPersistentAttributeType()==Attribute.PersistentAttributeType.EMBEDDED) {
                var embedded=(ManagedType<?>)((SingularAttribute<?,?>)attr).getType();
                fields(embedded,embedded.getJavaType(),path+".",id,fields,relations,diagnostics,targets);continue;
            }
            if(attr.isAssociation()) {
                Class<?> target=attr instanceof PluralAttribute<?,?,?> plural?plural.getElementType().getJavaType():attr.getJavaType();
                var semantic=annotation(member,owner,attr.getName(),SemanticRelation.class);
                var one=member.getAnnotation(OneToMany.class);var many=member.getAnnotation(ManyToMany.class);var single=member.getAnnotation(OneToOne.class);
                String mapped=one!=null?one.mappedBy():many!=null?many.mappedBy():single!=null?single.mappedBy():"";
                var columns=member.getAnnotationsByType(JoinColumn.class);var joinTable=member.getAnnotation(JoinTable.class);
                relations.add(new JpaModel.Relation(path,target,attr.getPersistentAttributeType().name(),mapped.isBlank(),mapped,optional,
                    Arrays.stream(columns).map(JoinColumn::name).toList(),Arrays.stream(columns).map(JoinColumn::referencedColumnName).toList(),joinTable==null?"":joinTable.name(),
                    semantic==null?"":semantic.id(),semantic==null?"":semantic.direction(),semantic==null?"":semantic.joinUse(),semantic==null?"":semantic.meaning()));
                continue;
            }
            if(attr.isCollection()) {
                var exposedCollection=annotation(member,owner,attr.getName(),SemanticField.class);
                if(exposedCollection!=null && exposedCollection.exposed())diagnostics.add("UNSUPPORTED_COLLECTION:"+owner.getName()+"."+path);
                continue;
            }
            var semanticRel=annotation(member,owner,attr.getName(),SemanticRelation.class);
            var column=member.getAnnotation(Column.class);
            String columnName=column==null || column.name().isBlank()?attr.getName():column.name();
            if(semanticRel!=null && !semanticRel.targetEntity().isBlank()) {
                Class<?> target=targets.get(semanticRel.targetEntity());
                String refCol=semanticRel.targetField()==null || semanticRel.targetField().isBlank()?"id":semanticRel.targetField();
                relations.add(new JpaModel.Relation(path,target,"MANY_TO_ONE",true,"",optional,
                    List.of(columnName),List.of(refCol),"",
                    semanticRel.id(),semanticRel.direction(),semanticRel.joinUse(),semanticRel.meaning()));
            }
            var semantic=annotation(member,owner,attr.getName(),SemanticField.class);var policy=annotation(member,owner,attr.getName(),SemanticPolicy.class);
            boolean sensitive=attr.getName().toLowerCase(Locale.ROOT).matches(".*(password|credential|secret|picture|email|phone).*" ) || attr.getJavaType()==byte[].class;
            if(sensitive && semantic==null)diagnostics.add("UNANNOTATED_SENSITIVE:"+owner.getName()+"."+path);
            if(sensitive && semantic!=null && semantic.exposed())diagnostics.add("EXPOSED_SENSITIVE:"+owner.getName()+"."+path);
            fields.add(new JpaModel.Field(path,columnName,attr.getJavaType(),id,optional,
                semantic==null?"":semantic.id(),semantic!=null && semantic.exposed(),policy==null?"":policy.fieldPolicy(),semantic==null?Set.of():new HashSet<>(Arrays.stream(semantic.allowedOps()).map(s->s.toUpperCase(Locale.ROOT)).toList()),SemanticMetadata.from(semantic)));
        }
    }
    private <A extends Annotation> A annotation(AnnotatedElement member,Class<?> owner,String name,Class<A> annotation) {
        A direct=member.getAnnotation(annotation);if(direct!=null)return direct;
        for(Class<?> type=owner;type!=null;type=type.getSuperclass()) {
            try {var field=type.getDeclaredField(name);var a=field.getAnnotation(annotation);if(a!=null)return a;}catch(NoSuchFieldException ignored){}
            String cap=Character.toUpperCase(name.charAt(0))+name.substring(1);
            for(String getter:List.of("get"+cap,"is"+cap))try {var a=type.getDeclaredMethod(getter).getAnnotation(annotation);if(a!=null)return a;}catch(NoSuchMethodException ignored){}
        }
        return null;
    }
}
