package com.openstudy.domainlens.ai;
import com.openstudy.domainlens.api.retrieval.EmbeddingGateway;
import com.openstudy.domainlens.api.ir.ContractJson;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
/** Small HTTP adapter; no global API client, request/body logging or implicit paid fallback. */
public final class OpenAiEmbeddingGateway implements EmbeddingGateway {
    private final URI endpoint; private final String key,model;private final int dimensions;private final HttpClient client;
    public OpenAiEmbeddingGateway(String key){this(URI.create("https://api.openai.com/v1/embeddings"),key,"text-embedding-3-small",1536);}
    public OpenAiEmbeddingGateway(URI endpoint,String key,String model,int dimensions){this.endpoint=endpoint;this.key=key;this.model=model;this.dimensions=dimensions;this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();}
    public String model(){return model;}public int dimensions(){return dimensions;}
    @SuppressWarnings("unchecked") public List<List<Double>> embed(List<String> texts) {
        if(key==null || key.isBlank() || texts.isEmpty() || texts.stream().anyMatch(String::isBlank))throw new IllegalArgumentException("Embedding input/key required");
        try {
            var body=ContractJson.write(Map.of("model",model,"input",texts,"dimensions",dimensions,"encoding_format","float"));
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("Embedding provider rejected request");
            var root=ContractJson.read(response.body(),Map.class);
            if(!model.equals(root.get("model")))throw new IllegalStateException("Embedding model mismatch");
            var data=(List<Map<String,Object>>)root.get("data");
            if(data.size()!=texts.size())throw new IllegalStateException("Embedding count mismatch");
            var ordered=new TreeMap<Integer,List<Double>>();
            for(var item:data){int index=((Number)item.get("index")).intValue();var vector=((List<Number>)item.get("embedding")).stream().map(Number::doubleValue).toList();if(index<0 || index>=texts.size() || vector.size()!=dimensions || vector.stream().anyMatch(v->!Double.isFinite(v)) || vector.stream().allMatch(v->v==0) || ordered.put(index,vector)!=null)throw new IllegalStateException("Embedding shape mismatch");}
            return List.copyOf(ordered.values());
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Embedding interrupted");}
        catch(Exception ex){throw new IllegalStateException("Embedding provider unavailable or invalid response");}
    }
}
