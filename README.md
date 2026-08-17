<div align="center">

# SemanticDocs

**Search your documents by meaning, not by keyword — and get answers with citations you can check.**

The vector search engine is written from scratch in Java. No FAISS, no Pinecone, no pgvector doing the hard part.

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-61DAFB)](https://react.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791)](https://www.postgresql.org/)
[![Recall](https://img.shields.io/badge/recall%4010-1.000-success)](#benchmarks)
[![Speedup](https://img.shields.io/badge/vs%20exact%20search-7.9x-blue)](#benchmarks)

<img src="screenshots/ask.png" alt="Asking a question and getting a cited answer" width="900">

</div>

---

## The problem

Press Ctrl+F and search for `refund policy`. Now suppose the document actually says:

> *"Customers may return items within thirty days for a full reimbursement."*

Ctrl+F finds **nothing**. The document answers your question perfectly and shares not a single word with what you typed.

SemanticDocs fixes that. It converts every passage into a 768-number vector that captures its *meaning*, then finds the passages closest to your question in that space. No shared keywords required.

---

## What makes this different

Most RAG projects are ~200 lines of Python gluing three libraries together. Ask *"how does the vector search actually work?"* and the answer is *"FAISS does it."*

Here, the search engine is a **hand-written HNSW graph in Java** — layered navigable small-world, with the neighbour-selection heuristic from the paper, tombstone deletes, binary persistence and read-write locking. An exact brute-force index sits alongside it as ground truth, so the recall numbers below are measured rather than claimed.

---

## Screenshots

### Upload and index

Drop files in and watch them move `PENDING → PROCESSING → READY`. The header strip shows the live vector count, edge count and graph depth, so the data structure is never hidden from the user.

<img src="screenshots/library-dark.png" alt="Document library with indexing status" width="900">

### Search by meaning

Every result carries a call number — file, part, chunk id — and a relevance meter drawn as discrete ticks rather than a progress bar, because a similarity score is a measurement.

<img src="screenshots/search.png" alt="Semantic search results with relevance scores" width="900">

### Ask, with citations

Sources stream to the browser **before** the first token of the answer, so you can start reading the evidence while the model is still writing. Every inline `[1]` is clickable.

<img src="screenshots/ask.png" alt="RAG answer with numbered source citations" width="900">

### Light and dark

The whole theme is one block of CSS custom properties — no component rule is duplicated. It follows your OS preference until you choose explicitly.

<table>
<tr>
<td width="50%"><img src="screenshots/library-light.png" alt="Light theme"></td>
<td width="50%"><img src="screenshots/library-dark.png" alt="Dark theme"></td>
</tr>
<tr>
<td align="center"><em>Light</em></td>
<td align="center"><em>Dark</em></td>
</tr>
</table>

### Sign in

<img src="screenshots/login.png" alt="Sign in screen" width="440">

---

## Architecture

<img src="docs/diagrams/architecture.png" alt="System architecture" width="900">

Everything above the purple box is ordinary Spring Boot. The purple box is the part worth talking about.

<details>
<summary><b>How HNSW works</b> (click to expand)</summary>

<br>

<img src="docs/diagrams/hnsw-layers.png" alt="HNSW layered graph" width="900">

A graph where each vector links to a few near neighbours, so you search by *walking toward* the query instead of checking everything. The graph is stacked in layers: the top is sparse and lets you cross the whole dataset in a few big hops, and each layer down is denser. Motorway, then main road, then your street.

That turns an O(n) scan into roughly **O(log n)** hops.

</details>

<details>
<summary><b>The ingestion pipeline</b> (click to expand)</summary>

<br>

<img src="docs/diagrams/ingestion.png" alt="Ingestion pipeline" width="900">

The upload returns `202 Accepted` in milliseconds; the slow work runs on a bounded background pool and the browser polls for status. The index is updated **last**, after every database write has succeeded — because a database write can roll back and an in-memory graph cannot.

</details>

<details>
<summary><b>Retrieval-augmented generation</b> (click to expand)</summary>

<br>

<img src="docs/diagrams/rag.png" alt="RAG prompt construction" width="900">

Retrieved passages are sanitised, fenced, numbered, and the task is restated *after* them — small models weight recent tokens most heavily, so instructions placed first lose to instructions hidden in the data.

</details>

---

## Benchmarks

<img src="docs/diagrams/benchmark.png" alt="Recall and latency benchmark" width="900">

20,000 vectors, 128 dimensions, 200 queries, `M=16`, `efConstruction=200`, cosine:

| Dataset | Index | recall@10 | p50 | Speedup |
|---|---|---|---|---|
| clustered | brute force *(exact)* | 1.000 | 2.45 ms | 1.0x |
| clustered | HNSW `ef=16` | 0.940 | 0.16 ms | **15.1x** |
| clustered | HNSW `ef=32` | 0.993 | 0.21 ms | 11.5x |
| clustered | HNSW `ef=64` | **1.000** | 0.31 ms | **7.9x** |
| uniform random | HNSW `ef=128` | 0.710 | 1.02 ms | 2.4x |

Graph: 20,000 nodes, ~520,000 edges, 4 layers, ~11.8 MB in memory.

**The two datasets are the interesting part.** Same code, same parameters, recall 1.000 versus 0.710. Uniform random vectors in 128 dimensions are nearly equidistant from each other — the curse of dimensionality — so there is barely a meaningful "nearest neighbour" to find. Real text embeddings are heavily clustered, which is why the clustered rows describe this application.

> A recall figure quoted without naming the dataset it was measured on is meaningless.

Reproduce it with no build tool, no database and no network — the `vectorindex` package depends only on the JDK:

```bash
javac -d /tmp/bench \
  backend/src/main/java/com/semanticdocs/vectorindex/*.java \
  backend/src/test/java/com/semanticdocs/vectorindex/RecallBenchmark.java

java -cp /tmp/bench com.semanticdocs.vectorindex.RecallBenchmark 20000 128 200
```

---

## Tech stack

| Layer | Choice | Why this one |
|---|---|---|
| Language | Java 21 | Records and text blocks; the language backend roles hire for |
| Framework | Spring Boot 3.3 | Auto-configuration; the industry default |
| Security | Spring Security + JWT | Stateless, so any instance serves any request |
| Database | PostgreSQL 16 | The data is genuinely relational; cascades guarantee cleanup |
| Migrations | Flyway | Reproducible schema; Hibernate validates rather than alters |
| Cache | Redis | Caches the *query embedding*, not results — results are per-user |
| Extraction | Apache Tika | One API for PDF, DOCX, PPTX, HTML, EPUB; sniffs real file type |
| AI | Ollama (local) | Free re-indexing while tuning, offline demo, nothing leaves the machine |
| Frontend | React 18 + Vite | Fast dev loop; the dev proxy removes CORS friction |
| **Search** | **Hand-written HNSW** | **The point of the project** |

---

## Quick start

**Prerequisites:** JDK 21, Maven, Node 18+, Docker, [Ollama](https://ollama.com)

```bash
# 1. databases
docker compose up -d

# 2. models (~2.3 GB, one time)
ollama pull nomic-embed-text
ollama pull llama3.2:3b

# 3. backend  -> http://localhost:8080
cd backend && mvn spring-boot:run

# 4. frontend -> http://localhost:5173
cd frontend && npm install && npm run dev
```

Flyway creates the schema on first boot — there is nothing to import. Swagger UI is at `/swagger-ui.html`.

Full instructions, including how to run without Docker and what to do on a low-RAM machine, are in [`docs/02-SETUP.md`](docs/02-SETUP.md).

---

## Project structure

```
backend/src/main/java/com/semanticdocs/
├── vectorindex/    HNSW, brute force, metrics, persistence   <- the centrepiece
├── document/       entities, extraction, chunking, ingestion pipeline
├── embedding/      EmbeddingProvider interface + Ollama implementation
├── search/         query path, relevance floor, snippets
├── rag/            prompts, sanitiser, LLM provider, SSE streaming
├── auth/           JWT, BCrypt, security filter
├── config/         thread pools, security rules, typed properties
└── common/         exception types, one error-response shape

frontend/src/
├── pages/          Login, Library, Search, Ask
├── components/     ThemeToggle, Ticks, Highlight
└── api.js          the only file that calls fetch
```

Dependencies point one way: `rag` -> `search` -> `vectorindex`, and **`vectorindex` depends on nothing but the JDK**. That is why the benchmark runs with a bare `javac`.

---

## Documentation

| Document | Read it for |
|---|---|
| [`01-ARCHITECTURE.md`](docs/01-ARCHITECTURE.md) | Component map, request flows, schema, design decisions |
| [`02-SETUP.md`](docs/02-SETUP.md) | Getting it running, troubleshooting |
| [`03-API.md`](docs/03-API.md) | Every endpoint with request and response examples |
| [`04-HOW-IT-WORKS.md`](docs/04-HOW-IT-WORKS.md) | The whole system explained from zero, in plain language |
| [`05-INTERVIEW-GUIDE.md`](docs/05-INTERVIEW-GUIDE.md) | 50 questions and answers on this codebase |
| [`06-BENCHMARKS.md`](docs/06-BENCHMARKS.md) | How to reproduce the numbers above |
| [`07-RESUME-AND-DEMO.md`](docs/07-RESUME-AND-DEMO.md) | Demo script |

---

## Things that went wrong, and what fixed them

Documented because the debugging is the interesting part.

| Bug | Cause | Fix |
|---|---|---|
| Every similarity scored 0.47–0.58 | `nomic-embed-text` is asymmetric and needs `search_document:` / `search_query:` prefixes; I omitted both | Split `embedDocument` / `embedQuery` so call sites cannot get it wrong |
| Irrelevant passages in every answer | No relevance floor — an index returns its k nearest neighbours unconditionally, and *nearest is not relevant* | `search.min-score`, applied before results are returned |
| A poisoned PDF hijacked the model | The defence was a prompt instruction. A 3B model obeyed the document instead — resisting an instruction and following one are the same capability | Four layers: relevance floor, sanitiser, instructions-last, output guard |
| Streamed answers lost every space | Spring writes `data:` with no separator; the SSE spec strips one leading space, eating the token's own | JSON-encode every event so whitespace and newlines survive |
| Chat UI hung after answering | Streaming shared the ingestion thread pool and queued behind uploads | A dedicated `streamingExecutor` — the bulkhead pattern |

---

## Honest limitations

Worth knowing before you read the code:

- **The index lives in one JVM's heap.** Scaling out means sharding by tenant or moving it behind its own service. Comfortable to about a million vectors.
- **Deletes are tombstones.** Removing a node's edges could strand other vectors, so deleted nodes stay walkable and are filtered from results. Many deletes degrade the graph until a rebuild.
- **`List<Integer>` neighbour lists box every id.** `int[]` would cut memory roughly threefold and reduce the GC pressure visible in p95 latency. Readability was chosen while learning the algorithm.
- **Build throughput is well below hnswlib's**, which uses SIMD distance kernels.
- **Grounding is a prompt instruction, not a guarantee.** Pattern matching loses to paraphrase; the durable mitigation is architectural — the model has no tools, no network and no database access.
- **Brute force is honestly fine at 20,000 vectors.** HNSW earns its keep in the hundreds of thousands.

---

## License

MIT
