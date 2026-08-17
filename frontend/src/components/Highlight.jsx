const STOP_WORDS = new Set([
  'the', 'a', 'an', 'of', 'in', 'on', 'for', 'to', 'is', 'are', 'was', 'were',
  'and', 'or', 'what', 'how', 'why', 'when', 'does', 'do', 'did', 'this', 'that',
]);

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Bolds the query words inside a snippet.
 *
 * Worth saying out loud in a demo: the ranking above is semantic, so a passage can rank
 * first without containing any of these words. The highlight is a reading aid, not the
 * reason the result was chosen.
 */
export default function Highlight({ text, query }) {
  const terms = (query || '')
    .toLowerCase()
    .split(/\W+/)
    .filter((word) => word.length > 2 && !STOP_WORDS.has(word));

  if (terms.length === 0) return <>{text}</>;

  const pattern = new RegExp(`(${terms.map(escapeRegex).join('|')})`, 'gi');
  const parts = text.split(pattern);

  return (
    <>
      {parts.map((part, i) =>
        pattern.test(part) && terms.includes(part.toLowerCase())
          ? <mark key={i}>{part}</mark>
          : <span key={i}>{part}</span>
      )}
    </>
  );
}
