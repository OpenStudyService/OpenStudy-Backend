package com.openstudy.domainlens.work;

import com.openstudy.domainlens.api.error.*;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.prompt.PreviousReferenceResolver;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.work.*;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.*;
import com.openstudy.domainlens.security.Identity;
import java.util.*;

/** Reads only the selected persisted execution. Conversation history and result rows never enter prompts. */
public final class StoredReferenceResolver implements PreviousReferenceResolver {
    private final WorkStore store;
    private final IrValidator validator;
    private final SemanticRegistry registry;
    public StoredReferenceResolver(WorkStore store,IrValidator validator,SemanticRegistry registry) {
        this.store=store;this.validator=validator;this.registry=registry;
    }
    @Override public Conditions resolve(Request request,UserContext user) {
        var principal=Identity.require(user);String owner=principal.subjectId();
        var conversation=store.conversation(request.conversationId(),owner).orElseThrow(StoredReferenceResolver::missing);
        if(!conversation.id().equals(request.conversationId()) || !conversation.owner().equals(owner))throw missing();
        var saved=store.execution(request.executionId(),owner).orElseThrow(StoredReferenceResolver::missing);
        if(!saved.id().equals(request.executionId()) || !saved.conversationId().equals(request.conversationId()) || !saved.owner().equals(owner))throw missing();
        if(!"COMPLETED".equals(saved.finalStatus()) || saved.ir().status()!=IrStatus.READY)
            throw new EngineException(EngineErrorCode.IR_INVALID,"Reference execution is not completed");
        if(saved.formatVersion()!=StorageSchema.VERSION || !saved.snapshotVersion().equals(registry.version()))
            throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Reference version is incompatible");
        var choices=saved.datasets().stream().filter(d->request.datasetId()==null || d.id().equals(request.datasetId())).toList();
        if(choices.size()!=1)throw new EngineException(EngineErrorCode.IR_INVALID,"Select one reference dataset");
        var dataset=choices.getFirst();var plan=dataset.plan();Identity.owner(user,plan.owner());
        if(plan.formatVersion()!=ExecutionPlan.VERSION || !plan.registryVersion().equals(registry.version()))
            throw new EngineException(EngineErrorCode.IR_UNSUPPORTED,"Reference plan version is incompatible");
        if(!ContractJson.canonical(plan.effectiveScope()).equals(ContractJson.canonical(principal.scope())))
            throw new EngineException(EngineErrorCode.SCOPE_DENIED,"Reference scope has changed");
        var query=plan.query();
        if(!query.id().equals(dataset.id()))throw new EngineException(EngineErrorCode.IR_INVALID,"Reference dataset mismatch");
        // Revalidate with current policy bindings. The stored policy version grants no authority.
        ContractRules.validate(saved.ir());
        validator.validateReference(plan,user);
        var refs=validator.referenceContext(query);
        if(!registry.publicNodeIds().containsAll(refs))throw new EngineException(EngineErrorCode.IR_INVALID,"Unknown reference context");
        boolean aggregate=query.mode()==QuerySpec.Mode.AGGREGATE;
        var dimensions=aggregate?query.dimensions().stream().map(QuerySpec.Selection::field).collect(java.util.stream.Collectors.toSet()):Set.<String>of();
        var metrics=aggregate?query.metrics().stream().map(QuerySpec.Selection::field).collect(java.util.stream.Collectors.toSet()):Set.<String>of();
        // A population total is inherited only from a matching, currently authorized saved plan.
        boolean total=false;
        if(aggregate && !dimensions.isEmpty())for(var other:saved.datasets()) {
            var candidate=other.plan();var scalar=candidate.query();
            if(scalar.mode()==QuerySpec.Mode.AGGREGATE && scalar.dimensions().isEmpty()
                    && scalar.source().equals(query.source()) && scalar.grain().equals(query.grain())
                    && scalar.metrics().stream().map(QuerySpec.Selection::field).collect(java.util.stream.Collectors.toSet()).equals(metrics)
                    && ContractJson.canonical(scalar.filter()).equals(ContractJson.canonical(query.filter()))) {
                Identity.owner(user,candidate.owner());validator.validateReference(candidate,user);
                if(candidate.formatVersion()!=ExecutionPlan.VERSION || !candidate.registryVersion().equals(registry.version())
                        || !scalar.id().equals(other.id()) || !ContractJson.canonical(candidate.effectiveScope()).equals(ContractJson.canonical(principal.scope())))
                    throw new EngineException(EngineErrorCode.IR_INVALID,"Reference total plan mismatch");
                total=true;
            }
        }
        return new Conditions(saved.id(),dataset.id(),query.source(),query.grain(),query.filter(),saved.asOf(),refs,dimensions,metrics,total);
    }
    private static EngineException missing(){return new EngineException(EngineErrorCode.NOT_FOUND,"Reference execution not found");}
}
