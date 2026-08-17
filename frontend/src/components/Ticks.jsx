/**
 * Relevance as ten discrete ticks instead of a smooth progress bar.
 *
 * A similarity score is a measurement, and a ruler reads like one. It also stops the score
 * looking like a loading bar, which is what a filled bar tends to suggest.
 */
export default function Ticks({ score }) {
  const filled = Math.max(0, Math.min(10, Math.round(score * 10)));
  return (
    <span className="ticks" title={`similarity ${score.toFixed(4)}`}>
      {Array.from({ length: 10 }, (_, i) => (
        <i key={i} className={i < filled ? 'on' : ''} />
      ))}
      <span className="value">{score.toFixed(3)}</span>
    </span>
  );
}
