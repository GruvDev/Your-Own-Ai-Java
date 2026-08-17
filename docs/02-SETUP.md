# Setup

## Part 0: Installing the tools (skip if you already have them)

Do these once. Everything after assumes they are on your PATH.

### JDK 21

- **Windows / macOS**: download Eclipse Temurin 21 from adoptium.net and run the installer.
  On Windows, tick "Set JAVA_HOME variable" during install.
- **Ubuntu / WSL**: `sudo apt update && sudo apt install openjdk-21-jdk`

Verify: `java -version` and `javac -version` must BOTH print 21.x. If `java` works but
`javac` does not, you installed a JRE instead of a JDK - install the JDK.

### Maven

- **Windows**: download the binary zip from maven.apache.org, extract to `C:\maven`, add
  `C:\maven\bin` to your PATH.
- **macOS**: `brew install maven`
- **Ubuntu / WSL**: `sudo apt install maven`

Verify: `mvn -version`. Check the last line says Java version 21 - if it says 17, your
JAVA_HOME points at an older JDK and the build will fail.

### Node.js 20 LTS

Download the LTS installer from nodejs.org, or `brew install node`, or
`sudo apt install nodejs npm`.

Verify: `node --version` (must be 18+) and `npm --version`.

### Docker Desktop

From docker.com. On Windows it will ask to enable WSL 2 - allow it. Start Docker Desktop and
wait for the whale icon to stop animating before running anything.

Verify: `docker --version` and `docker compose version`.

### Ollama

From ollama.com. On Windows and macOS it runs as a background app after install. On Linux:
`curl -fsSL https://ollama.com/install.sh | sh`

Verify: `ollama --version`, then open <http://localhost:11434> in a browser - it should say
"Ollama is running".

---

## What you need

| Tool | Version | Check with |
|---|---|---|
| JDK | 21+ | `java -version` |
| Node | 18+ | `node --version` |
| Docker | any recent | `docker --version` |
| Ollama | latest | `ollama --version` |

RAM matters more than CPU here. 8 GB works with a small model; 16 GB is comfortable.

## 1. Start Postgres and Redis

```bash
docker compose up -d
docker compose ps          # both should be "healthy"
```

Flyway creates the schema on first boot of the backend, so there is nothing to import.

## 2. Pull the models

```bash
ollama pull nomic-embed-text     # embeddings, 768 dimensions, ~270 MB
ollama pull llama3.2:3b          # generation, ~2 GB
ollama list                      # confirm both are there
```

Model choice by machine:

| RAM | Generation model | Set in application.yml |
|---|---|---|
| 8 GB | `llama3.2:3b` or `phi3:mini` | `semanticdocs.llm.model` |
| 16 GB | `qwen2.5:7b` or `llama3.1:8b` | same |
| Low disk | `all-minilm` for embeddings (384 dims) | also set `embedding.dimension: 384` |

**If you change the embedding model, change the dimension too**, and delete
`data/index/hnsw.bin`. Vectors from two different models are not comparable - mixing them
produces a ranking that looks fine and means nothing. The application refuses mismatched
dimensions rather than silently corrupting results.

## 3. Run the backend

```bash
cd backend
mvn spring-boot:run
```

If you would rather not install Maven globally, generate the wrapper once with
`mvn wrapper:wrapper` and use `./mvnw` from then on.

Watch for these lines - they tell you the moving parts came up:

```
Flyway ... Successfully applied 1 migration
Loaded index from ./data/index/hnsw.bin with N vectors    (or "Rebuilt index with N vectors")
Tomcat started on port 8080
```

Swagger UI: <http://localhost:8080/swagger-ui.html>

## 4. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>, create an account, upload a PDF, and watch the status pill go
PENDING -> PROCESSING -> READY.

Vite proxies `/api` to port 8080, so the browser only ever sees one origin and CORS stays out
of the way during development.

## Switching to a hosted model

Local generation is the default because it is free and works offline. If you are demoing on
a borrowed machine and do not want to pull 2 GB the night before, keep the API path warm:

```yaml
semanticdocs:
  llm:
    provider: openai
    model: gpt-4o-mini
```

with `OPENAI_API_KEY` in your environment. Embeddings should stay local regardless - you will
re-embed your corpus many times while tuning chunk size, and paying per token for that is
both slow and expensive.

## If you cannot use Docker

Install PostgreSQL 16 natively, then create the database and user:

```sql
CREATE USER semanticdocs WITH PASSWORD 'semanticdocs';
CREATE DATABASE semanticdocs OWNER semanticdocs;
```

Redis is harder to install natively on Windows, and it is only a cache here. Turn it off by
setting this in `application.yml`:

```yaml
spring:
  cache:
    type: simple      # in-memory cache instead of Redis
```

The application starts and behaves identically; the query-embedding cache just lives in the
JVM's heap and disappears on restart. Nothing else depends on Redis.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot reach the embedding model` | Ollama not running | `ollama serve`, then retry the upload |
| Document stuck at PROCESSING | backend restarted mid-ingest | it requeues on next start; check the log |
| Document FAILED with "no readable text" | scanned PDF, images only | run OCR first (`ocrmypdf in.pdf out.pdf`) |
| `returned 384 dimensions but ... is 768` | embedding model changed | fix `embedding.dimension`, delete the index file, restart |
| Search returns nothing | nothing is READY yet | check the Library tab |
| `Failed to determine a suitable driver class` | Postgres not up | `docker compose up -d` |
| Answers ignore the documents | generation model too small | try a 7B model, or lower `temperature` |
| Port 5432 already in use | local Postgres running | stop it, or change the port mapping |

## Useful commands

```bash
# Watch ingestion happen
docker compose logs -f postgres
psql -h localhost -U semanticdocs -d semanticdocs -c "SELECT id, filename, status, chunk_count FROM documents;"

# How many vectors are stored
psql -h localhost -U semanticdocs -d semanticdocs -c "SELECT count(*), model FROM embeddings GROUP BY model;"

# Force a full index rebuild from the database
rm data/index/hnsw.bin && mvn spring-boot:run

# Run the tests
cd backend && mvn test
```
