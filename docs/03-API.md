# API reference

Base URL `http://localhost:8080`. Everything except `/api/auth/**` needs
`Authorization: Bearer <token>`.

## Auth

### POST /api/auth/register -> 201

```json
{ "email": "you@example.com", "password": "at-least-8-chars", "displayName": "You" }
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresInSeconds": 7200,
  "user": { "id": 1, "email": "you@example.com", "displayName": "You", "role": "USER" }
}
```

### POST /api/auth/login -> 200
Same body minus `displayName`, same response.

Wrong password and unknown email return the identical message on purpose - a different one
would let an attacker enumerate which emails have accounts.

### GET /api/auth/me -> 200
Returns the user summary. The frontend calls this on load to check a stored token is still
valid.

## Documents

### POST /api/documents -> 202 Accepted
`multipart/form-data` with a `file` part.

```json
{ "id": 12, "filename": "report.pdf", "status": "PENDING", "chunkCount": 0, "createdAt": "..." }
```

202, not 201: the file is stored and queued, nothing is searchable yet. Poll the next
endpoint.

### GET /api/documents -> 200
Array of the caller's documents, newest first.

### GET /api/documents/{id} -> 200
One document. This is the polling endpoint.

```json
{ "id": 12, "filename": "report.pdf", "status": "READY", "chunkCount": 47,
  "indexedAt": "2026-02-11T09:31:08Z", "errorMessage": null }
```

Statuses: `PENDING` -> `PROCESSING` -> `READY`, or `FAILED` with `errorMessage` set.

### DELETE /api/documents/{id} -> 204
Removes the vectors from the index, then the rows (cascading to chunks, embeddings and
citations), then the file.

## Search

### POST /api/search -> 200

```json
{ "query": "how did revenue change?", "topK": 10, "documentId": null, "ef": null }
```

`documentId` restricts the search to one document. `ef` overrides search breadth - higher
means better recall and slower queries; leave it null for the configured default.

```json
{
  "query": "how did revenue change?",
  "resultCount": 10,
  "tookMillis": 41,
  "cached": false,
  "results": [
    { "chunkId": 883, "documentId": 12, "filename": "report.pdf", "chunkIndex": 6,
      "score": 0.8412, "snippet": "...revenue for the quarter fell 12%...",
      "content": "full passage text" }
  ]
}
```

`score` is 0..1, higher is closer. POST rather than GET because queries can be long, are not
usefully cacheable by the browser, and should not end up in server logs or browser history.

### GET /api/index/stats -> 200

```json
{ "nodeCount": 4820, "deletedCount": 12, "maxLevel": 3, "m": 16,
  "efConstruction": 200, "edgeCount": 126400, "approximateBytes": 15319040 }
```

## Chat

### POST /api/chat/ask -> 200
Blocking. Returns when the model has finished.

```json
{ "question": "what were the main risks?", "conversationId": null, "documentId": null }
```

```json
{
  "conversationId": 3, "messageId": 18,
  "answer": "The report lists three main risks [1][2]...",
  "model": "llama3.2:3b", "tookMillis": 8420,
  "citations": [
    { "number": 1, "chunkId": 883, "documentId": 12, "filename": "report.pdf",
      "chunkIndex": 6, "score": 0.84, "snippet": "..." }
  ]
}
```

### POST /api/chat/stream -> text/event-stream
Same request body. Three event types:

```
event: citations
data: [{"number":1,"chunkId":883,...}]

event: token
data: The

event: token
data:  report

event: done
data: {"conversationId":3,"messageId":18,"answer":"...","tookMillis":8420,...}
```

Citations are sent before the first token, so the browser can show sources while the model is
still writing.

### GET /api/chat/conversations -> 200
### GET /api/chat/conversations/{id}/messages -> 200
Full thread with citations resolved, loaded in a single query.

## Errors

Every error has the same shape:

```json
{
  "timestamp": "2026-02-11T09:31:08Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Check the highlighted fields",
  "path": "/api/auth/register",
  "fieldErrors": { "password": "Use at least 8 characters" }
}
```

| Status | When |
|---|---|
| 400 | validation failed, bad login |
| 401 / 403 | missing, expired or invalid token |
| 404 | not found, or owned by someone else - deliberately indistinguishable |
| 409 | email already registered |
| 413 | file over 25 MB |
| 502 | Ollama unreachable |
