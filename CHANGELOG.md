## [0.1.0]

Первая публикация в Maven Central (группа `io.github.olegnyr`).

- **Модули:** `tools-log-api` (без зависимостей: `SecretMatcher`, `SecretMaskingRegistry`, контракт
  сканера — для сервисов, которые расширяют маскирование), `tools-log-core`, `tools-log-logback`,
  `tools-log-logbook`, `tools-log-servlet` — без Spring (граница держится правилом `maven-enforcer`); `tools-log-spring-boot` — автоконфигурация
  Spring Boot 4; `tools-log-spring-kafka` — Logbook для Spring Kafka.
- **Маскирование секретов:** реестр секретов запроса, `SecretMatcher` для своих классов секретов,
  матчер номеров карт с проверкой Luhn; пути матчера — `toolslog.masking.pan.*` (по умолчанию все пути).
- **Маски:** `Masks` (`hidden`, `all`, `keepFirst`, `keepLast`, `keepFirstAndLast`, `email`) и
  `SecretMaskingRegistry.register(secret, SecretMask)`.
- **Лимит сканирования:** тело Kafka-записи длиннее 64 КБ не сканируется на секреты — как и тело HTTP.
- **JSON-лог:** `JacksonStructuredLogEncoder` берёт поля сервиса из своих свойств в `logback.xml`;
  `SpringStructuredLogEncoder` — из `logging.structured.ecs.service.*` и `spring.application.*`.
- **Logbook:** общий бин с исключёнными путями (`toolslog.logbook.exclude-paths`) и маскируемыми
  заголовками (`toolslog.logbook.masked-headers`); логгеры `toolslog.request.incoming|outgoing`.
- **Servlet:** фильтры маскирования и Logbook, выключаются `toolslog.servlet.*.enabled`.
- **Kafka:** аудит-поля MDC через заголовки записей задаются `auditMdcKeys(...)` у билдеров;
  `KafkaTemplateBuilder` добавляет свой перехватчик к `interceptor.classes` сервиса, не затирая их.
  Библиотека зависит только от API `micrometer-tracing` — мост трассировки выбирает сервис.
