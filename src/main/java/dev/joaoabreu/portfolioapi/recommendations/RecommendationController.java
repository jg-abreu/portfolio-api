package dev.joaoabreu.portfolioapi.recommendations;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static dev.joaoabreu.portfolioapi.recommendations.RecommendationModels.*;

@RestController
public class RecommendationController {
    private final RecommendationService recommendations;
    private final RecommendationImages images;
    public RecommendationController(RecommendationService recommendations, RecommendationImages images) {
        this.recommendations = recommendations;
        this.images = images;
    }
    @GetMapping("/api/admin/recommendations")
    public ResponseEntity<List<Draft>> list() { return privateResponse(recommendations.list()); }
    @GetMapping("/api/admin/recommendations/{id}")
    public ResponseEntity<Draft> get(@PathVariable long id) { return privateResponse(recommendations.get(id)); }
    @PostMapping("/api/admin/recommendations")
    public ResponseEntity<Draft> create(@Valid @RequestBody Save request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(recommendations.create(request));
    }
    @PutMapping("/api/admin/recommendations/{id}")
    public ResponseEntity<Draft> update(@PathVariable long id, @Valid @RequestBody Save request) { return privateResponse(recommendations.update(id, request)); }
    @PatchMapping("/api/admin/recommendations/{id}/status")
    public ResponseEntity<Draft> status(@PathVariable long id, @Valid @RequestBody ChangeStatus request) { return privateResponse(recommendations.status(id, request)); }
    @PutMapping("/api/admin/recommendations/{id}/publication")
    public ResponseEntity<Summary> select(@PathVariable long id, @Valid @RequestBody Version request) { return privateResponse(recommendations.select(id, request.version())); }
    @DeleteMapping("/api/admin/recommendations/{id}/publication")
    public ResponseEntity<Void> withdraw(@PathVariable long id, @Valid @RequestBody Version request) {
        recommendations.withdraw(id, request.version());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/api/admin/recommendation-publications")
    public ResponseEntity<List<Summary>> summaries() { return privateResponse(recommendations.summaries()); }
    @GetMapping("/api/recommendations")
    public ResponseEntity<Snapshot> snapshot() { return privateResponse(recommendations.snapshot()); }
    @PostMapping(value = "/api/admin/recommendation-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RecommendationImages.StoredImage> upload(@RequestPart("image") MultipartFile image) throws IOException {
        try (var input = image.getInputStream()) { return privateResponse(images.store(input)); }
    }
    @GetMapping("/api/admin/recommendation-images/{key}")
    public ResponseEntity<byte[]> preview(@PathVariable String key) { return image(images.read(key)); }
    @GetMapping("/api/recommendation-images/{key}")
    public ResponseEntity<byte[]> publishedImage(@PathVariable String key) { return image(recommendations.publicImage(key)); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Recommendation slug is already in use"));
    }
    private <T> ResponseEntity<T> privateResponse(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
