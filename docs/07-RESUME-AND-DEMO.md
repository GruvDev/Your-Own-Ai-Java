# Resume bullets and demo script

## Resume entry

Keep it to four lines. Lead with the thing nobody else has.

> **SemanticDocs — Semantic document search & RAG platform**
> *Java 21, Spring Boot, PostgreSQL, Redis, React, Ollama*
> - Implemented an HNSW approximate-nearest-neighbour index from scratch in Java (layered
>   navigable small-world graph, neighbour-selection heuristic, tombstone deletes, binary
>   persistence); benchmarked at **recall@10 = 1.00 with 7.9× lower latency than exact search**
>   over 20K vectors.
> - Built an asynchronous ingestion pipeline (Tika extraction → overlapping chunking →
>   batched embedding → dual-store persistence) with bounded thread pools, transactional
>   rollback and startup crash recovery.
> - Delivered RAG question-answering with per-passage citations, context budgeting and
>   SSE token streaming, over stateless JWT auth and a Redis-cached query path.

### Line-by-line reasoning

- **"from scratch"** is the load-bearing phrase. It's what separates this from every
  LangChain project in the pile.
- **The number is specific and includes the baseline.** "7.9× faster than exact search over
  20K vectors" is checkable; "high performance" is not.
- **Name the mechanisms, not the tools.** "Bounded thread pools, transactional rollback,
  crash recovery" tells a backend engineer more than "used Spring Boot".
- **Never claim what you can't defend.** Every phrase above maps to code you can open.

Shorter version if space is tight:

> Built a semantic document search platform (Spring Boot, React, PostgreSQL) with a
> hand-written HNSW vector index in Java — recall@10 = 1.00 at 7.9× the speed of exact
> search; async ingestion pipeline and RAG answers with clickable source citations.

---

## The 5-minute demo

Rehearse this. A demo that stalls loses the room regardless of the code.

**Before you start:** backend running, Ollama up, two or three documents already indexed, and
one small file held back to upload live.

**0:00 — The problem (30s).**
"Ctrl+F searches for letters. If the document says 'return items for a reimbursement' and you
search 'refund policy', you get nothing. That's what this fixes."

**0:30 — Upload (60s).**
Drop the held-back file. Point at the status pill moving PENDING → PROCESSING → READY.
"The response came back in milliseconds — the actual work runs on a background pool, and the
browser polls the status. That's why the status is a database column and not just memory: if
the server dies mid-ingest, it requeues on startup."
Point at the index strip in the header as the vector count jumps.

**1:30 — Semantic search (90s).**
Search a phrase using *none* of the words in the target passage. Let the result land.
"No shared keywords. It matched because the question and that passage sit near each other in
768-dimensional embedding space."
Point at the call number line: file, part, chunk id, and the tick meter.
"That score is cosine similarity. Every result is traceable to an exact passage."

**3:00 — Ask (60s).**
Ask a question needing two documents. Sources appear before the first token.
"Citations stream first, so you can read the sources while the model is still writing."
Click an inline `[1]` chip.
"Every answer stores which passages it was shown. Months later you can still ask why it said
that."

**4:00 — The index (60s).** This is the part that matters.
Open `HnswIndex.java`, scroll to `selectNeighbours`.
"This is the search engine — I didn't use FAISS or pgvector. HNSW is a layered graph: the top
layer is sparse so you cross the dataset in a few hops, then you refine downward. This method
is the neighbour-selection heuristic — it skips a candidate that's closer to an already-chosen
neighbour than to the node being linked, which keeps neighbours spread in different directions
so the graph doesn't fragment. It's the detail that most affects recall."
Then show the benchmark output.
"I kept brute force in the codebase as ground truth. Recall 1.0 at ef=64, about 8× faster.
And on uniform random vectors recall drops to 0.71 — same code — because in high dimensions
random points are all roughly equidistant. Recall is a property of your data as much as your
implementation."

**5:00 — Close (15s).**
"Where it breaks: the index is in one JVM's heap. Past a million vectors I'd shard it by
tenant or move it behind its own service."

---

## Questions to have ready

They will ask something you didn't prepare. These come up most:

- "Why not use a vector database?" → *That's the point of the project; I wanted to implement
  the algorithm rather than call one. In production with a deadline I'd use pgvector.*
- "How long did it take?" → Be honest. Say what you'd cut if you had half the time.
- "What's the weakest part?" → `docs/04-HOW-IT-WORKS.md` Part 12. Pick one and mean it.
- "Did you use AI to build it?" → *Yes, for scaffolding and review — the same way I'd use
  Stack Overflow.* Then open `HnswIndex.java` and explain the neighbour heuristic line by
  line. That's the answer that actually settles it, so make sure it's true: read the code
  until you can walk through `searchLayer` from memory.

---

## Before you list it

- [ ] It runs from a clean clone following `docs/02-SETUP.md`
- [ ] Tests pass (`mvn test`)
- [ ] Benchmark runs and the README numbers are *yours*, from your machine
- [ ] README opens with what it does and the measured result
- [ ] You can explain `searchLayer` and `selectNeighbours` without reading them
- [ ] You can name three limitations unprompted
- [ ] 90-second demo video linked in the README (recruiters won't clone your repo)
- [ ] Commit history looks like development, not one "initial commit" of 5,000 lines
