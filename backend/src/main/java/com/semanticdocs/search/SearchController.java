package com.semanticdocs.search;

import com.semanticdocs.vectorindex.HnswIndex;
import com.semanticdocs.vectorindex.VectorIndexService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService searchService;
    private final VectorIndexService indexService;

    public SearchController(SearchService searchService, VectorIndexService indexService) {
        this.searchService = searchService;
        this.indexService = indexService;
    }

    /**
     * POST rather than GET, even though this reads data. Queries can be long, they are not
     * meaningfully cacheable by the browser, and putting user text in a URL means it lands in
     * server logs and browser history.
     */
    @PostMapping("/search")
    public SearchDtos.SearchResponse search(@Valid @RequestBody SearchDtos.SearchRequest request) {
        return searchService.search(request);
    }

    /** Feeds the stats strip in the UI and makes the index visible during a demo. */
    @GetMapping("/index/stats")
    public HnswIndex.IndexStats stats() {
        return indexService.stats();
    }
}
