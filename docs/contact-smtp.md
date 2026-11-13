# Contact SMTP transport

The SMTP adapter uses Spring Boot JavaMailSender and sends a plain-text notification to all configured recipients. The From address belongs to the configured sending account; Reply-To is the visitor email, validated before sending. Visitor text never controls the subject or SMTP recipient list.

The transport is disabled unless `PORTFOLIO_CONTACT_MAIL_ENABLED=true`. When enabled, sender and recipient configuration is required. ContactDeliveryWorker invokes this adapter only when delivery is explicitly enabled; contact submission has a separate disabled-by-default flag. A successful SMTP submission alone is not a guarantee of inbox delivery.

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

Keep delivery disabled until private SMTP authentication and a real delivery test are complete. Unit tests simulate JavaMailSender; no real email is sent. The worker marks a row sent only after transport success. Retention is 90 days, independently enabled; retries and duplicate-delivery limits are documented in contact-delivery.md.
