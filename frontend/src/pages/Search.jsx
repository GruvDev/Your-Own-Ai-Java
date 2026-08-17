import { useEffect, useState } from 'react';
import { api } from '../api.js';
import Ticks from '../components/Ticks.jsx';
import Highlight from '../components/Highlight.jsx';

export default function Search() {
  const [query, setQuery] = useState('');
  const [documentId, setDocumentId] = useState('');
  const [documents, setDocuments] = useState([]);
  const [response, setResponse] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.documents()
      .then((docs) => setDocuments(docs.filter((doc) => doc.status === 'READY')))
      .catch(() => setDocuments([]));
  }, []);

  const run = async () => {
    if (!query.trim()) return;
    setBusy(true);
    setError(null);
    try {
      setResponse(await api.search({
        query: query.trim(),
        topK: 10,
        documentId: documentId ? Number(documentId) : null,
      }));
    } catch (err) {
      setError(err.message);
      setResponse(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="page-head">
        <h1>Search</h1>
        <p>
          Ranked by meaning, not by keyword. A passage about "quarterly revenue fell" can be
          the top hit for "did sales drop" without sharing a single word.
        </p>
      </div>

      <div className="searchbar">
        <input
          type="text"
          value={query}
          placeholder="Ask in your own words..."
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && run()}
          aria-label="Search query"
        />
        <select
          value={documentId}
          onChange={(e) => setDocumentId(e.target.value)}
          style={{ width: 'auto', minWidth: 170 }}
          aria-label="Limit to one document"
        >
          <option value="">All documents</option>
          {documents.map((doc) => (
            <option key={doc.id} value={doc.id}>{doc.filename}</option>
          ))}
        </select>
        <button className="btn" onClick={run} disabled={busy || !query.trim()}>
          {busy ? 'Searching' : 'Search'}
        </button>
      </div>

      {error && <div className="banner error">{error}</div>}

      {response && (
        <>
          <div className="result-meta">
            <span>{response.resultCount} passages</span>
            <span>{response.tookMillis} ms end to end</span>
            <span>{response.cached ? 'served from cache' : 'freshly embedded + searched'}</span>
          </div>

          {response.results.length === 0 ? (
            <div className="empty">
              <strong>No matches</strong>
              Nothing in your library is close enough to this query.
            </div>
          ) : (
            <div className="stack">
              {response.results.map((result) => (
                <article className="result" key={result.chunkId}>
                  <div className="callno">
                    <span className="file">{result.filename}</span>
                    <span className="sep">/</span>
                    <span>part {String(result.chunkIndex + 1).padStart(2, '0')}</span>
                    <span className="sep">/</span>
                    <span>chunk {result.chunkId}</span>
                    <Ticks score={result.score} />
                  </div>
                  <p className="snippet">
                    <Highlight text={result.snippet} query={response.query} />
                  </p>
                </article>
              ))}
            </div>
          )}
        </>
      )}

      {!response && !error && (
        <div className="empty">
          <strong>Try a question</strong>
          Type what you are looking for the way you would say it out loud.
        </div>
      )}
    </>
  );
}
