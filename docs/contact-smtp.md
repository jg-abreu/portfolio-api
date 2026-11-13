# Contact SMTP transport

The SMTP adapter uses Spring Boot JavaMailSender and sends a plain-text notification to all configured recipients. The From address belongs to the configured sending account; Reply-To is the visitor email, validated before sending. Visitor text never controls the subject or SMTP recipient list.

The transport is disabled unless `PORTFOLIO_CONTACT_MAIL_ENABLED=true`. When enabled, sender and recipient configuration is required. No scheduler or public submission endpoint invokes this adapter yet. A successful SMTP submission alone is not a guarantee of inbox delivery.

Configuration for the approved Gmail sender (set privately, never commit credentials):

```text
PORTFOLIO_CONTACT_MAIL_ENABLED=false
PORTFOLIO_CONTACT_MAIL_FROM=YOUR_SENDING_ADDRESS
PORTFOLIO_CONTACT_MAIL_RECIPIENTS=YOUR_PERSONAL_ADDRESS,YOUR_DEV_ADDRESS
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=YOUR_SENDING_ADDRESS
SPRING_MAIL_PASSWORD=YOUR_APP_PASSWORD
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_CONNECTIONTIMEOUT=5000
SPRING_MAIL_PROPERTIES_MAIL_SMTP_TIMEOUT=5000
SPRING_MAIL_PROPERTIES_MAIL_SMTP_WRITETIMEOUT=5000
```

Keep delivery disabled until the outbox worker, retry policy and submission protection are complete. Unit tests simulate JavaMailSender; no real email is sent. The worker must mark a row sent only after the transport succeeds, and must retain failed messages for retries according to the agreed policy. Retention and duplicate-delivery handling remain to be finalized before enabling delivery.
