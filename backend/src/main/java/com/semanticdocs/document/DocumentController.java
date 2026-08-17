package com.semanticdocs.document;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** 202 Accepted, not 201 Created: the work has been queued, not finished. */
    @PostMapping
    public ResponseEntity<DocumentDtos.DocumentResponse> upload(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(documentService.upload(file));
    }

    @GetMapping
    public List<DocumentDtos.DocumentResponse> list() {
        return documentService.list();
    }

    /** The endpoint the UI polls while a document is processing. */
    @GetMapping("/{id}")
    public DocumentDtos.DocumentResponse get(@PathVariable Long id) {
        return documentService.get(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
