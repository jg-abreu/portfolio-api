package dev.joaoabreu.portfolioapi.recommendations;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/books")
public class GoogleBooksController {
    private final GoogleBooksService books;
    public GoogleBooksController(GoogleBooksService books) { this.books = books; }
    @GetMapping
    public ResponseEntity<List<GoogleBooksService.Book>> search(@RequestParam String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(books.search(q));
    }
    @PostMapping("/{volumeId}/selection")
    public ResponseEntity<GoogleBooksService.Book> select(@PathVariable String volumeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(books.verify(volumeId));
    }
}
