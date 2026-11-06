package com.openstudy.domainlens.ai;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Uses the JVM architecture, including an Intel JVM on Apple Silicon, not the host CPU. */
record OnnxRuntimePlatform(String os, String architecture) {
    static OnnxRuntimePlatform current() {
        return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static OnnxRuntimePlatform detect(String operatingSystem, String cpu) {
        String name=operatingSystem.toLowerCase(Locale.ROOT);
        String os=name.startsWith("mac") || name.equals("darwin")?"osx":name.startsWith("linux")?"linux":name.startsWith("windows")?"win":null;
        String arch=switch(cpu.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
        if(os==null || arch==null)throw new IllegalStateException("Unsupported local embedding JVM platform: "+operatingSystem+"/"+cpu);
        return new OnnxRuntimePlatform(os,arch);
    }

    String classifier() { return os+"-"+architecture; }

    Path manifestPath(Path directory) {
        Path specific=directory.resolve("manifest-"+architecture+".json");
        return Files.isRegularFile(specific)?specific:directory.resolve("manifest.json");
    }

    void requireCompatibleModel(String filename) {
        if(architecture.equals("x86_64") && filename.contains("arm64"))
            throw new IllegalStateException("ARM Q8 model cannot be used by an Intel JVM; prepare manifest-x86_64.json with scripts/download-embedding-model.py --architecture x86_64");
        if(architecture.equals("aarch64") && filename.contains("x86_64"))
            throw new IllegalStateException("Intel Q8 model cannot be used by an ARM JVM; prepare the ARM manifest with scripts/download-embedding-model.py --architecture aarch64");
    }

    void requireBundledLibraries() {
        String extension=os.equals("win")?".dll":os.equals("osx")?".dylib":".so";
        String prefix=os.equals("win")?"":"lib";
        String tokenizer="/native/lib/"+classifier()+"/"+prefix+"tokenizers"+extension;
        String ort="/ai/onnxruntime/native/"+os+"-"+(architecture.equals("x86_64")?"x64":architecture)+"/"+prefix+"onnxruntime4j_jni"+extension;
        if(HuggingFaceTokenizer.class.getResource(tokenizer)==null || OrtEnvironment.class.getResource(ort)==null)
            throw new IllegalStateException("Missing bundled tokenizer/ONNX native libraries for "+classifier()+"; use the pinned runtime dependencies with a supported 64-bit JVM");
    }

    static String tokenizerVersion() throws IOException {
        var properties=new Properties();
        try(var input=HuggingFaceTokenizer.class.getResourceAsStream("/native/lib/tokenizers.properties")) {
            if(input==null)throw new IllegalStateException("Tokenizer version metadata missing");
            properties.load(input);
        }
        String version=properties.getProperty("version");
        if(version==null || version.isBlank())throw new IllegalStateException("Tokenizer version metadata empty");
        return version;
    }
}
