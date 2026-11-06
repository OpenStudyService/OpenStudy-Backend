package com.openstudy.domainlens.ai;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.openstudy.domainlens.api.ir.ContractJson;
import com.openstudy.domainlens.api.retrieval.EmbeddingGateway;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Local transformer encoder: pinned artifacts, manifest-defined prefixes, masked mean pooling and L2. */
public final class OnnxEmbeddingGateway implements EmbeddingGateway, AutoCloseable {
    public record Manifest(String modelId, String revision, int dimensions, int maxTokens, String queryPrefix, String documentPrefix, Map<String,String> sha256) {
        public Manifest { sha256 = Map.copyOf(sha256); }
    }
    private static final String PIPELINE = "onnx-prefix-mean-l2-v2";
    private final Manifest manifest;
    private final String identity;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private boolean closed;

    public OnnxEmbeddingGateway(Path directory) {
        OrtSession opened = null;HuggingFaceTokenizer tokens = null;
        try {
            var platform=OnnxRuntimePlatform.current();
            platform.requireBundledLibraries();
            manifest = ContractJson.read(Files.readString(platform.manifestPath(directory)), Manifest.class);
            if (manifest.modelId() == null || manifest.modelId().isBlank() || manifest.revision() == null
                    || manifest.revision().isBlank() || manifest.dimensions() < 1 || manifest.maxTokens() < 4
                    || manifest.queryPrefix() == null || manifest.documentPrefix() == null)
                throw new IllegalArgumentException();
            String modelFile = manifest.sha256().keySet().stream().filter(n -> n.endsWith(".onnx")).findFirst().orElseThrow();
            if (manifest.sha256().size() != 2 || !manifest.sha256().containsKey("tokenizer.json")) throw new IllegalArgumentException();
            for (var file : manifest.sha256().entrySet()) {
                if (!file.getKey().matches("[a-zA-Z0-9_.-]+") || !file.getValue().matches("[a-f0-9]{64}")
                        || !sha256(directory.resolve(file.getKey())).equals(file.getValue())) throw new IllegalArgumentException();
            }
            platform.requireCompatibleModel(modelFile);
            identity = manifest.modelId() + "@" + manifest.revision() + ":" + hash(PIPELINE + platform.classifier() + OnnxRuntimePlatform.tokenizerVersion() + ContractJson.canonical(manifest));
            environment = OrtEnvironment.getEnvironment();
            try (var options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(2);
                opened = environment.createSession(directory.resolve(modelFile).toString(), options);
            }
            if (!opened.getInputNames().containsAll(Set.of("input_ids", "attention_mask"))
                    || !Set.of("input_ids", "attention_mask", "token_type_ids").containsAll(opened.getInputNames())
                    || !opened.getOutputNames().contains("last_hidden_state")) throw new IllegalArgumentException();
            tokens = HuggingFaceTokenizer.builder().optTokenizerPath(directory.resolve("tokenizer.json"))
                    .optAddSpecialTokens(true).optTruncation(true).optMaxLength(manifest.maxTokens())
                    .optPadding(false).build();
            session = opened;tokenizer = tokens;
            // Fail startup, rather than the first user query, if native tokenization/inference fails.
            encode(manifest.queryPrefix(), "embedding startup 확인");
            System.getLogger(OnnxEmbeddingGateway.class.getName()).log(System.Logger.Level.INFO,
                    "Local embedding ready: JVM="+platform.classifier()+", model="+modelFile+", tokenizer="+OnnxRuntimePlatform.tokenizerVersion()+", dimensions="+manifest.dimensions());
        } catch (Exception | LinkageError failure) {
            if (tokens != null) tokens.close();
            if (opened != null) try { opened.close(); } catch (OrtException ignored) { }
            throw new IllegalStateException("Local ONNX artifacts or runtime unavailable for JVM "+System.getProperty("os.name")+"/"+System.getProperty("os.arch"),failure);
        }
    }
    public String model() { return identity; }
    public int dimensions() { return manifest.dimensions(); }
    public Manifest manifest() { return manifest; }
    public List<List<Double>> embed(List<String> texts) { return embedDocuments(texts); }
    public synchronized List<List<Double>> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) throw new IllegalArgumentException("Embedding input required");
        return texts.stream().map(text -> encode(manifest.documentPrefix(), text)).toList();
    }
    public synchronized List<Double> embedQuery(String text) { return encode(manifest.queryPrefix(), text); }
    private List<Double> encode(String prefix, String text) {
        if (closed) throw new IllegalStateException("Encoder closed");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding input required");
        var tensors = new HashMap<String,OnnxTensor>();
        try {
            var encoded = tokenizer.encode(prefix + text);
            long[] ids = encoded.getIds(), mask = encoded.getAttentionMask();
            if (ids.length > manifest.maxTokens() || ids.length == 0) throw new IllegalStateException();
            tensors.put("input_ids", OnnxTensor.createTensor(environment, new long[][] {ids}));
            tensors.put("attention_mask", OnnxTensor.createTensor(environment, new long[][] {mask}));
            if (session.getInputNames().contains("token_type_ids"))
                tensors.put("token_type_ids", OnnxTensor.createTensor(environment, new long[][] {encoded.getTypeIds()}));
            try (var result = session.run(tensors)) {
                var hidden = (float[][][]) result.get("last_hidden_state").orElseThrow().getValue();
                if (hidden.length != 1) throw new IllegalStateException();
                return pool(hidden[0], mask, dimensions());
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Local embedding inference failed",failure);
        } finally { tensors.values().forEach(OnnxTensor::close); }
    }
    static List<Double> pool(float[][] hidden, long[] mask, int dimensions) {
        if (hidden.length != mask.length) throw new IllegalArgumentException("Embedding shape mismatch");
        double[] mean = new double[dimensions];int count = 0;
        for (int t = 0; t < hidden.length; t++) {
            if (hidden[t].length != dimensions) throw new IllegalArgumentException("Embedding shape mismatch");
            if (mask[t] == 0) continue;
            if (mask[t] != 1) throw new IllegalArgumentException("Invalid attention mask");
            count++;
            for (int d = 0; d < dimensions; d++) {
                if (!Float.isFinite(hidden[t][d])) throw new IllegalArgumentException("Invalid embedding");
                mean[d] += hidden[t][d];
            }
        }
        if (count == 0) throw new IllegalArgumentException("Empty embedding");
        double norm = 0;
        for (int d = 0; d < dimensions; d++) { mean[d] /= count;norm += mean[d] * mean[d]; }
        if (!Double.isFinite(norm) || norm == 0) throw new IllegalArgumentException("Invalid embedding norm");
        var vector = new ArrayList<Double>();
        for (double v : mean) vector.add(v / Math.sqrt(norm));
        return List.copyOf(vector);
    }
    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) { byte[] buffer = new byte[65536];int count;
            while ((count = stream.read(buffer)) >= 0) digest.update(buffer, 0, count); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    public synchronized void close() {
        if (closed) return;closed = true;tokenizer.close();
        try { session.close(); } catch (OrtException e) { throw new IllegalStateException("Encoder close failed"); }
    }
}
