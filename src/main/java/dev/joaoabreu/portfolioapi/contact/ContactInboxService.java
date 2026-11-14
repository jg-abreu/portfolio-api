package dev.joaoabreu.portfolioapi.contact;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class ContactInboxService {
    private final ContactMessageRepository messages;

    public ContactInboxService(ContactMessageRepository messages) { this.messages = messages; }

    public InboxPage list(boolean archived, int page, int size) {
        var pagination = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var result = archived ? messages.findByArchivedAtIsNotNull(pagination)
                : messages.findByArchivedAtIsNull(pagination);
        return new InboxPage(result.map(ContactMessage::summary).getContent(), page, size,
                result.getTotalElements(), result.hasNext());
    }

    public ContactMessage.Detail get(long id) { return find(id).detail(); }

    @Transactional
    public ContactMessage.Detail change(long id, long version, boolean value, boolean archive) {
        var message = find(id);
        if (message.getVersion() != version) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (archive) message.setArchived(value);
        else message.setRead(value);
        messages.flush();
        return message.detail();
    }

    private ContactMessage find(long id) {
        return messages.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record InboxPage(List<ContactMessage.Summary> messages, int page, int size,
            long totalElements, boolean hasNext) {}
}
