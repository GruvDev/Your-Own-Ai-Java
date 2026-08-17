import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api.js';

const IN_FLIGHT = ['PENDING', 'PROCESSING'];

export default function Library({ onChange }) {
  const [documents, setDocuments] = useState([]);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState(null);
  const [uploading, setUploading] = useState(false);
  const fileInput = useRef(null);

  const load = useCallback(async () => {
    try {
      setDocuments(await api.documents());
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  // Ingestion runs on a background thread, so the browser polls until nothing is in flight.
  // Polling stops on its own once every document is READY or FAILED - an interval that never
  // clears is a memory leak and a pointless load on the server.
  useEffect(() => {
    const pending = documents.some((doc) => IN_FLIGHT.includes(doc.status));
    if (!pending) {
      onChange?.();
      return undefined;
    }
    const timer = setInterval(load, 2000);
    return () => clearInterval(timer);
  }, [documents, load, onChange]);

  const upload = async (files) => {
    setError(null);
    setUploading(true);
    try {
      for (const file of files) {
        await api.upload(file);
      }
      await load();
    } catch (err) {
      setError(err.message);
    } finally {
      setUploading(false);
    }
  };

  const remove = async (id) => {
    try {
      await api.deleteDocument(id);
      setDocuments((current) => current.filter((doc) => doc.id !== id));
      onChange?.();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <>
      <div className="page-head">
        <h1>Library</h1>
        <p>
          Upload a PDF, Word file or plain text. Each one is split into passages, turned into
          vectors and added to the index. Large files take a minute.
        </p>
      </div>

      {error && <div className="banner error">{error}</div>}

      <div
        className={`dropzone ${dragging ? 'dragging' : ''}`}
        onClick={() => fileInput.current?.click()}
        onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && fileInput.current?.click()}
        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          upload(Array.from(e.dataTransfer.files));
        }}
        role="button"
        tabIndex={0}
      >
        <strong>{uploading ? 'Uploading...' : 'Drop files here or click to choose'}</strong>
        <div className="mono" style={{ marginTop: 6 }}>PDF · DOCX · TXT · MD · HTML · up to 25 MB</div>
        <input
          ref={fileInput}
          type="file"
          multiple
          hidden
          onChange={(e) => upload(Array.from(e.target.files))}
        />
      </div>

      <div className="stack" style={{ marginTop: 20 }}>
        {documents.length === 0 && (
          <div className="empty">
            <strong>Nothing indexed yet</strong>
            Upload a document to start searching it.
          </div>
        )}

        {documents.map((doc) => (
          <div className="doc-row" key={doc.id}>
            <div className="doc-grow">
              <div className="doc-name">{doc.filename}</div>
              <div className="doc-meta">
                SD-{String(doc.id).padStart(5, '0')}
                {' · '}{(doc.sizeBytes / 1024).toFixed(0)} KB
                {doc.chunkCount > 0 && ` · ${doc.chunkCount} passages`}
              </div>
              {doc.status === 'FAILED' && doc.errorMessage && (
                <div className="mono" style={{ color: 'var(--danger)', marginTop: 4 }}>
                  {doc.errorMessage}
                </div>
              )}
            </div>
            <span className={`pill ${doc.status.toLowerCase()}`}>{doc.status}</span>
            <button className="btn danger" onClick={() => remove(doc.id)} aria-label={`Delete ${doc.filename}`}>
              Delete
            </button>
          </div>
        ))}
      </div>
    </>
  );
}
