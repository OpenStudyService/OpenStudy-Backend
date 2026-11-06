package com.openstudy.domainlens.ai;
import com.openstudy.domainlens.api.ir.*;
import com.openstudy.domainlens.api.spi.ModelGateway;
import java.util.List;
public final class PlaybackGateway implements ModelGateway {
    private final FixtureStore store;
    public PlaybackGateway(FixtureStore store){this.store=store;}
    public IrDocument generate(String ask,List<String> refs){return store.find(ask).orElseGet(()->new IrDocument(IrDocument.VERSION,IrStatus.UNSUPPORTED,null,List.of(),"PLAYBACK_NO_MATCH",List.of(),null,List.of()));}
}
