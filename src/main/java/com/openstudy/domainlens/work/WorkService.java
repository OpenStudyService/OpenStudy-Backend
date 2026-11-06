package com.openstudy.domainlens.work;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.query.*;
import com.openstudy.domainlens.security.*;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

public final class WorkService {
    public record Restored(UUID id,String status,WorkStore.@Nullable Execution execution,@Nullable String reason) {}
    private final WorkStore store;private final IrValidator validator;private final SemanticRegistry registry;
    public WorkService(WorkStore store,IrValidator validator,SemanticRegistry registry){this.store=store;this.validator=validator;this.registry=registry;}
    public WorkStore.Conversation create(UserContext user){return store.create(Identity.require(user).subjectId(),Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));}
    public UUID complete(UUID conversation,String question,QueryPipeline.Outcome outcome,UserContext user) {
        return complete(conversation,question,outcome,user,false);
    }
    public UUID complete(UUID conversation,String question,QueryPipeline.Outcome outcome,UserContext user,boolean messagesAccepted) {
        String owner=Identity.require(user).subjectId();validator.validate(outcome.ir(),registry.publicNodeIds(),user);
        var result=outcome.result();var datasets=new ArrayList<WorkStore.Dataset>();
        for(var ds:result.datasets()){var plan=outcome.plans().stream().filter(p->p.query().id().equals(ds.id())).findFirst().orElseThrow(()->new EngineException(EngineErrorCode.IR_INVALID,"Missing result plan"));datasets.add(new WorkStore.Dataset(ds.id(),plan,ds.columns(),ds.totalElements()));}
        Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);UUID id=UUID.fromString(result.executionId());
        var execution=new WorkStore.Execution(StorageSchema.VERSION,id,conversation,owner,outcome.ir(),datasets,result.view(),result.warnings(),result.status(),result.asOf(),result.snapshotVersion(),result.policyVersion(),at,outcome.usage());
        store.save(execution,messagesAccepted?List.of():List.of(new WorkStore.Message(UUID.randomUUID(),conversation,"USER",com.openstudy.domainlens.catalog.SearchText.sanitize(question),at),new WorkStore.Message(UUID.randomUUID(),conversation,"ASSISTANT",result.status(),at.plusNanos(1000))),owner);return id;
    }
    public Restored restore(UUID id,UserContext user) {
        String owner=Identity.require(user).subjectId();var saved=store.execution(id,owner).orElseThrow(()->new EngineException(EngineErrorCode.NOT_FOUND,"Execution not found"));
        if(saved.formatVersion()!=StorageSchema.VERSION || !saved.snapshotVersion().equals(registry.version()))return new Restored(id,"INCOMPATIBLE",null,"IR_UNSUPPORTED");
        try {validator.validate(saved.ir(),registry.publicNodeIds(),user);for(var dataset:saved.datasets())validator.validateReference(dataset.plan(),user);return new Restored(id,saved.finalStatus(),saved,null);}
        catch(EngineException ex){if(ex.code()==EngineErrorCode.IR_UNSUPPORTED)return new Restored(id,"INCOMPATIBLE",null,ex.code().name());if(ex.code()==EngineErrorCode.SCOPE_DENIED || ex.code()==EngineErrorCode.PII_DENIED)return new Restored(id,"ACCESS_REVOKED",null,ex.code().name());throw ex;}
    }
}
