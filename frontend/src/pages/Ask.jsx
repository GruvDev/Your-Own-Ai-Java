import { useEffect, useRef, useState } from 'react';
import { api, askStreaming } from '../api.js';

/** Turns the model's inline [1] markers into clickable chips. */
function AnswerText({ text, onCite }) {
  const parts = text.split(/(\[\d+\])/g);
  return (
    <p className="answer">
      {parts.map((part, i) => {
        const match = part.match(/^\[(\d+)\]$/);
        if (!match) return <span key={i}>{part}</span>;
        const number = Number(match[1]);
        return (
          <button key={i} className="citemark" onClick={() => onCite(number)}>
            {number}
          </button>
        );
      })}
    </p>
  );
}

export default function Ask() {
  const [question, setQuestion] = useState('');
  const [turns, setTurns] = useState([]);
  const [conversationId, setConversationId] = useState(null);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState(null);
  const [highlighted, setHighlighted] = useState(null);
  const bottom = useRef(null);

  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [turns]);

  const send = async () => {
    const text = question.trim();
    if (!text || streaming) return;

    setQuestion('');
    setError(null);
    setStreaming(true);
    setTurns((current) => [
      ...current,
      { role: 'user', content: text },
      { role: 'assistant', content: '', citations: [] },
    ]);

    const updateLast = (change) =>
      setTurns((current) => {
        const copy = [...current];
        copy[copy.length - 1] = { ...copy[copy.length - 1], ...change(copy[copy.length - 1]) };
        return copy;
      });

    try {
      await askStreaming(
        { question: text, conversationId },
        {
          // Sources arrive before the first token, so they can be read while the model writes.
          onCitations: (citations) => updateLast(() => ({ citations })),
          onToken: (token) => updateLast((last) => ({ content: last.content + token })),
          onDone: (answer) => {
            if (answer?.conversationId) setConversationId(answer.conversationId);
            // Re-enable input here, on the server's explicit "done" signal, rather than
            // waiting for the connection to close. A proxy can hold a stream open well
            // after the last token, and the user should not be locked out in the meantime.
            setStreaming(false);
          },
          onError: (err) => {
            setError(err.message);
            setStreaming(false);
          },
        }
      );
    } catch (err) {
      setError(err.message);
    } finally {
      setStreaming(false);
    }
  };

  const jumpToSource = (number) => {
    setHighlighted(number);
    setTimeout(() => setHighlighted(null), 1600);
  };

  return (
    <>
      <div className="page-head">
        <h1>Ask</h1>
        <p>
          Answers come only from your own documents, with the passages behind them listed
          underneath. If your library does not cover the question, it says so instead of
          guessing.
        </p>
      </div>

      {error && <div className="banner error">{error}</div>}

      {turns.length === 0 && (
        <div className="empty">
          <strong>Ask your library a question</strong>
          Every answer names the passages it used, so you can check it.
        </div>
      )}

      <div className="thread">
        {turns.map((turn, index) => (
          <div key={index} className={`bubble ${turn.role}`}>
            {turn.role === 'user' ? (
              turn.content
            ) : (
              <>
                <AnswerText text={turn.content} onCite={jumpToSource} />
                {streaming && index === turns.length - 1 && <span className="caret" />}

                {turn.citations?.length > 0 && (
                  <div className="sources">
                    <h4>Sources</h4>
                    {turn.citations.map((citation) => (
                      <div
                        key={citation.chunkId}
                        className={`source ${highlighted === citation.number ? 'highlight' : ''}`}
                      >
                        <span className="source-num">[{citation.number}]</span>
                        <span className="source-text">
                          {citation.snippet}
                          <span className="source-file">
                            {citation.filename} · part {citation.chunkIndex + 1} ·
                            {' '}similarity {citation.score.toFixed(3)}
                          </span>
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </>
            )}
          </div>
        ))}
        <div ref={bottom} />
      </div>

      <div className="composer">
        <textarea
          value={question}
          placeholder="Ask about your documents..."
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
          aria-label="Your question"
        />
        <button className="btn" onClick={send} disabled={streaming || !question.trim()}>
          {streaming ? 'Answering' : 'Ask'}
        </button>
      </div>
    </>
  );
}
