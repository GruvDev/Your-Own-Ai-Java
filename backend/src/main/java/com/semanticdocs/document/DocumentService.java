package com.semanticdocs.document;

import com.semanticdocs.auth.CurrentUser;
import com.semanticdocs.auth.User;
import com.semanticdocs.common.ApiExceptions;
import com.semanticdocs.config.AppProperties;
import com.semanticdocs.vectorindex.VectorIndexService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Upload, list, and delete. The heavy lifting is handed to IngestionService. */
@Service
public class DocumentService {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "txt", "md", "docx", "doc", "html", "htm", "pptx", "epub", "rtf");

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final IngestionService ingestionService;
    private final VectorIndexService indexService;
    private final CurrentUser currentUser;
    private final Path uploadDir;

    public DocumentService(DocumentRepository documentRepository,
                           ChunkRepository chunkRepository,
                           IngestionService ingestionService,
                           VectorIndexService indexService,
                           CurrentUser currentUser,
                           AppProperties properties) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.ingestionService = ingestionService;
        this.indexService = indexService;
        this.currentUser = currentUser;
        this.uploadDir = Path.of(properties.getStorage().getUploadDir());
    }

    @Transactional
    public DocumentDtos.DocumentResponse upload(MultipartFile file) {
        User user = currentUser.require();
        String originalName = sanitiseFilename(file.getOriginalFilename());
        validate(file, originalName);

        Path stored = storeOnDisk(file, user.getId());

        Document document = new Document(
                user, originalName, file.getContentType(), file.getSize(), stored.toString());
        documentRepository.save(document);

        // Returns immediately. The pipeline runs on the ingestion pool and the UI polls
        // this document's status until it turns READY.
        ingestionService.ingest(document.getId());

        return DocumentDtos.DocumentResponse.from(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentDtos.DocumentResponse> list() {
        User user = currentUser.require();
        return documentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(DocumentDtos.DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentDtos.DocumentResponse get(Long id) {
        return DocumentDtos.DocumentResponse.from(requireOwned(id));
    }

    @Transactional
    public void delete(Long id) {
        Document document = requireOwned(id);

        // Take the vectors out of the graph before the rows disappear, otherwise a search
        // could return a chunk id that no longer resolves to anything.
        chunkRepository.findByDocumentIdOrderByChunkIndex(id)
                .forEach(chunk -> indexService.remove(chunk.getId()));

        documentRepository.delete(document); // cascades to chunks and embeddings
        try {
            Files.deleteIfExists(Path.of(document.getStoragePath()));
        } catch (IOException ignored) {
            // The database is the source of truth; an orphan file is harmless.
        }
    }

    private Document requireOwned(Long id) {
        User user = currentUser.require();
        // Filtering by user id in the query, not after loading, is what stops user A from
        // reading user B's document by guessing an id.
        return documentRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such document"));
    }

    private void validate(MultipartFile file, String filename) {
        if (file.isEmpty()) {
            throw new ApiExceptions.BadRequestException("The file is empty");
        }
        String extension = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ApiExceptions.BadRequestException(
                    "Unsupported file type ." + extension + ". Try PDF, DOCX, TXT or MD.");
        }
    }

    private Path storeOnDisk(MultipartFile file, Long userId) {
        try {
            Path userDir = uploadDir.resolve(String.valueOf(userId));
            Files.createDirectories(userDir);
            // A random name on disk: two users uploading "notes.pdf" must not collide, and a
            // crafted filename must never be able to steer the write somewhere else.
            Path target = userDir.resolve(UUID.randomUUID() + "." + extensionOf(
                    sanitiseFilename(file.getOriginalFilename())));
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException ex) {
            throw new ApiExceptions.UpstreamException("Could not save the upload", ex);
        }
    }

    /** Strips any path components so "../../etc/passwd" cannot escape the upload folder. */
    private String sanitiseFilename(String raw) {
        if (raw == null || raw.isBlank()) return "upload";
        String name = Path.of(raw).getFileName().toString();
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    }
}
