// Every call to the backend goes through here.
//
// One module means one place that attaches the token, one place that turns an error body
// into a readable message, and one place to change if the API moves. Components never
// touch fetch directly.

const TOKEN_KEY = 'semanticdocs.token';

export const auth = {
  get token() {
    return localStorage.getItem(TOKEN_KEY);
  },
  save(token) {
    localStorage.setItem(TOKEN_KEY, token);
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY);
  },
};

async function request(path, { method = 'GET', body, isForm = false } = {}) {
  const headers = {};
  if (auth.token) headers.Authorization = `Bearer ${auth.token}`;
  if (!isForm && body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(`/api${path}`, {
    method,
    headers,
    body: isForm ? body : body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (response.status === 204) return null;

  const text = await response.text();
  const data = text ? JSON.parse(text) : null;

  if (!response.ok) {
    if (response.status === 401 || response.status === 403) auth.clear();
    throw new Error(data?.message || `Request failed (${response.status})`);
  }
  return data;
}

export const api = {
  register: (payload) => request('/auth/register', { method: 'POST', body: payload }),
  login: (payload) => request('/auth/login', { method: 'POST', body: payload }),
  me: () => request('/auth/me'),

  documents: () => request('/documents'),
  document: (id) => request(`/documents/${id}`),
  deleteDocument: (id) => request(`/documents/${id}`, { method: 'DELETE' }),
  upload: (file) => {
    const form = new FormData();
    form.append('file', file);
    return request('/documents', { method: 'POST', body: form, isForm: true });
  },

  search: (payload) => request('/search', { method: 'POST', body: payload }),
  indexStats: () => request('/index/stats'),

  ask: (payload) => request('/chat/ask', { method: 'POST', body: payload }),
  conversations: () => request('/chat/conversations'),
  messages: (id) => request(`/chat/conversations/${id}/messages`),
};

// Server-Sent Events cannot carry an Authorization header through the EventSource API,
// so the streaming endpoint is consumed as a plain fetch with a readable stream instead.
//
// Every event the server sends is JSON, tokens included. That is deliberate. Spring writes a
// frame as "data:" immediately followed by the payload, and the SSE specification tells a
// client to strip one leading space after the colon - so a raw token beginning with a space
// would arrive with that space eaten, and the answer would render as one unbroken run of
// characters. A raw token containing a newline would be worse: a blank line terminates an
// event, so the frame would split and the rest would be parsed as garbage. JSON escapes both,
// so a frame is always exactly one line and whitespace survives untouched.
export async function askStreaming(payload, { onCitations, onToken, onDone, onError }) {
  let response;
  try {
    response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${auth.token}`,
      },
      body: JSON.stringify(payload),
    });
  } catch (networkError) {
    onError?.(new Error('Could not reach the server. Is the backend running?'));
    return;
  }

  if (!response.ok || !response.body) {
    onError?.(new Error(`Streaming failed (${response.status})`));
    return;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;

  // Parses whatever complete frames are currently in the buffer.
  // Returns true once a terminal event (done or error) has been seen.
  const drain = () => {
    const frames = buffer.split('\n\n');
    buffer = frames.pop() ?? '';

    for (const frame of frames) {
      let event = 'message';
      const dataLines = [];

      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) {
          event = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          // Strip at most ONE leading space, per the SSE specification. Never trim, or
          // meaningful leading whitespace inside the payload is destroyed.
          const value = line.slice(5);
          dataLines.push(value.startsWith(' ') ? value.slice(1) : value);
        }
      }

      const raw = dataLines.join('\n');
      if (!raw) continue;

      let parsed;
      try {
        parsed = JSON.parse(raw);
      } catch {
        continue; // a partial or malformed frame; ignore rather than crash the stream
      }

      if (event === 'token') {
        if (parsed?.t) onToken?.(parsed.t);
      } else if (event === 'citations') {
        onCitations?.(parsed);
      } else if (event === 'done') {
        onDone?.(parsed);
        return true;
      } else if (event === 'error') {
        onError?.(new Error(parsed?.message || 'The server reported an error'));
        return true;
      }
    }
    return false;
  };

  try {
    while (!finished) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      finished = drain();
    }
    // Flush anything the decoder was holding, then parse any final frame that arrived
    // without a trailing blank line.
    if (!finished) {
      buffer += decoder.decode();
      if (buffer.trim()) {
        buffer += '\n\n';
        drain();
      }
    }
  } finally {
    // Releasing the reader is what actually closes the connection when we stop early
    // because a terminal event arrived. Without this the socket can stay open and the
    // UI never learns that the answer is complete.
    try {
      await reader.cancel();
    } catch {
      /* already closed */
    }
  }
}
