package com.semanticdocs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for everything under the "semanticdocs" prefix in application.yml.
 *
 * <p>Beats sprinkling @Value("${...}") everywhere: you get one object, IDE autocomplete,
 * and a startup failure instead of a null when a key is missing.
 */
@ConfigurationProperties(prefix = "semanticdocs")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Search search = new Search();
    private Storage storage = new Storage();
    private Chunking chunking = new Chunking();
    private Index index = new Index();
    private Embedding embedding = new Embedding();
    private Llm llm = new Llm();
    private Ollama ollama = new Ollama();
    private OpenAi openai = new OpenAi();

    public static class Jwt {
        private String secret;
        private long expiryMinutes = 120;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpiryMinutes() { return expiryMinutes; }
        public void setExpiryMinutes(long expiryMinutes) { this.expiryMinutes = expiryMinutes; }
    }

    public static class Storage {
        private String uploadDir = "./data/uploads";
        private String indexFile = "./data/index/hnsw.bin";

        public String getUploadDir() { return uploadDir; }
        public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }
        public String getIndexFile() { return indexFile; }
        public void setIndexFile(String indexFile) { this.indexFile = indexFile; }
    }

    public static class Chunking {
        private int size = 1800;
        private int overlap = 250;

        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public int getOverlap() { return overlap; }
        public void setOverlap(int overlap) { this.overlap = overlap; }
    }

    public static class Index {
        private int m = 16;
        private int efConstruction = 200;
        private int efSearch = 64;
        private boolean rebuildOnStart = false;

        public int getM() { return m; }
        public void setM(int m) { this.m = m; }
        public int getEfConstruction() { return efConstruction; }
        public void setEfConstruction(int efConstruction) { this.efConstruction = efConstruction; }
        public int getEfSearch() { return efSearch; }
        public void setEfSearch(int efSearch) { this.efSearch = efSearch; }
        public boolean isRebuildOnStart() { return rebuildOnStart; }
        public void setRebuildOnStart(boolean rebuildOnStart) { this.rebuildOnStart = rebuildOnStart; }
    }

    public static class Embedding {
        private String provider = "ollama";
        private String model = "nomic-embed-text";
        private int dimension = 768;
        private int batchSize = 16;

        // nomic-embed-text was trained with these literal prefixes. Blank them out for a
        // model that does not use them (all-minilm, OpenAI's models).
        private String documentPrefix = "search_document: ";
        private String queryPrefix = "search_query: ";

        public String getDocumentPrefix() { return documentPrefix; }
        public void setDocumentPrefix(String documentPrefix) { this.documentPrefix = documentPrefix; }
        public String getQueryPrefix() { return queryPrefix; }
        public void setQueryPrefix(String queryPrefix) { this.queryPrefix = queryPrefix; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public static class Search {
        /**
         * Passages scoring below this are discarded before they reach the answer or the model.
         *
         * <p>Without a floor, every query returns a full page of results no matter how weakly
         * related, because the index always hands back its k nearest neighbours - "nearest"
         * does not mean "relevant". That is how an unrelated document ends up in the prompt.
         */
        private float minScore = 0.55f;

        public float getMinScore() { return minScore; }
        public void setMinScore(float minScore) { this.minScore = minScore; }
    }

    public static class Llm {
        private String provider = "ollama";
        private String model = "llama3.2:3b";
        private double temperature = 0.2;
        private int maxContextChunks = 6;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxContextChunks() { return maxContextChunks; }
        public void setMaxContextChunks(int maxContextChunks) { this.maxContextChunks = maxContextChunks; }
    }

    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private int timeoutSeconds = 120;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class OpenAi {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }
    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }
    public Chunking getChunking() { return chunking; }
    public void setChunking(Chunking chunking) { this.chunking = chunking; }
    public Index getIndex() { return index; }
    public void setIndex(Index index) { this.index = index; }
    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding embedding) { this.embedding = embedding; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Ollama getOllama() { return ollama; }
    public void setOllama(Ollama ollama) { this.ollama = ollama; }
    public OpenAi getOpenai() { return openai; }
    public void setOpenai(OpenAi openai) { this.openai = openai; }
}
