# Benchmarks

## Why this file exists

Anyone can say "I implemented HNSW". The difference between that and an engineer is being
able to answer "how do you know it works, and what did it cost you?" - with numbers you
produced yourself.

Recall@k is the measurement. It is the fraction of the *true* k nearest neighbours that the
approximate index actually returned. Brute force gives the true answer by definition, which
is exactly why it stayed in the codebase after HNSW started working.

```
recall@10 = |HNSW's top 10  ∩  brute force's top 10| / 10
```

## Running it

The vector index has no dependencies beyond the JDK, so this needs no Maven, no database and
no network:

```bash
# with a JDK
javac -d /tmp/bench \
  backend/src/main/java/com/semanticdocs/vectorindex/*.java \
  backend/src/test/java/com/semanticdocs/vectorindex/RecallBenchmark.java
java -cp /tmp/bench com.semanticdocs.vectorindex.RecallBenchmark 20000 128 200

# arguments: [vectorCount] [dimensions] [queryCount]
```

If you only have a JRE, `javac` is still reachable through its module:

```bash
java -m jdk.compiler/com.sun.tools.javac.Main -d /tmp/bench <same files>
```

## Results

20,000 vectors, 128 dimensions, 200 queries, k=10, M=16, efConstruction=200, cosine.
Raw output is in `benchmark-output.txt`.

### Clustered vectors (realistic)

| index | build | recall@10 | p50 | p95 | speedup |
|---|---|---|---|---|---|
| brute force (exact) | 160 ms | 1.000 | 2.45 ms | 6.75 ms | 1.0x |
| HNSW ef=16 | 16.2 s | 0.940 | 0.16 ms | 0.93 ms | 15.1x |
| HNSW ef=32 | | 0.993 | 0.21 ms | 2.27 ms | 11.5x |
| HNSW ef=64 | | 1.000 | 0.31 ms | 4.41 ms | 7.9x |
| HNSW ef=128 | | 1.000 | 0.30 ms | 3.93 ms | 8.3x |

### Uniform random vectors (worst case)

| index | recall@10 | p50 | speedup |
|---|---|---|---|
| HNSW ef=16 | 0.194 | 0.20 ms | 12.2x |
| HNSW ef=32 | 0.327 | 0.35 ms | 7.0x |
| HNSW ef=64 | 0.509 | 0.58 ms | 4.2x |
| HNSW ef=128 | 0.710 | 1.02 ms | 2.4x |

Graph in both cases: 20,000 nodes, ~520,000 edges, 4 layers, ~11.8 MB heap, 12.06 MB on disk.

## Reading the numbers

**The dataset matters more than the code.** Identical implementation, identical parameters:
recall 1.000 on clustered data, 0.710 on uniform random. In 128 uniform random dimensions
almost every point sits at nearly the same distance from every other, so there is barely a
"nearest" to find and the graph's local structure has nothing to exploit. Real text
embeddings are heavily clustered - documents about the same topic land near each other - so
the clustered row is the one that describes this application. Any recall figure quoted
without naming the dataset is noise.

**ef buys recall with latency, and then stops.** Going from ef=16 to ef=64 takes clustered
recall from 0.940 to 1.000 and doubles the query time. Going on to ef=128 buys nothing,
because recall is already perfect. That knee is the tuning target, and it is the reason `ef`
is a runtime parameter rather than a constant.

**Build is slow, queries are fast.** Building 20,000 vectors takes 16 seconds; a query takes
0.3 milliseconds. That asymmetry is the whole bargain of an index - you pay once at write
time to make every subsequent read cheap. If you want it faster: `efConstruction` is the
dial, and hnswlib beats this comfortably because it uses SIMD distance kernels.

**p95 is much worse than p50.** 0.31 ms against 4.41 ms. On the JVM that gap is mostly
garbage collection and JIT warm-up, not the algorithm - the `List<Integer>` neighbour lists
allocate heavily. Switching them to `int[]` would reduce both memory and GC pressure. It is a
real, known, deliberately unfixed trade in favour of readable code.

## Fair caveats

- Ran on a normal cloud container with no CPU pinning, so absolute times are indicative.
- Latency measured single-threaded, in-process, with no network or database in the path.
- Synthetic vectors, not real embeddings. The clustered generator mimics their structure but
  is not a substitute for measuring on your own corpus.
- Brute force is ~2.4 ms at 20k vectors. It stays perfectly usable at this size - HNSW earns
  its keep at hundreds of thousands of vectors, and saying so is more credible than pretending
  the speedup matters at every scale.

## Worth trying next

- Sweep M (8, 16, 32, 48) and plot recall against memory.
- Rerun at 100k and 1M vectors to see the curves separate.
- Measure with real embeddings from your own documents.
- Compare against pgvector's HNSW on the same vectors - a comparison against a serious
  implementation is a much stronger interview answer than a comparison against brute force
  alone.
