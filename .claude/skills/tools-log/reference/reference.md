# tools-log: справочник

Содержание: 1. Поля JSON-события · 2. Журнал Logbook (KV-пары) · 3. Логгеры · 4. Настройки
`toolslog.*` · 5. Свойства encoder'ов · 6. Бины автоконфигурации · 7. API маскирования ·
8. Kafka-классы

## 1. Поля JSON-события (`JacksonStructuredLogEncoder`)

Одна строка JSON на событие, в конце — перевод строки. Пустые поля не пишутся.

| Поле | Что |
|---|---|
| `@timestamp` | момент события, ISO-8601 UTC |
| `level` | уровень |
| `logger` | имя логгера |
| `process.pid` | pid (`spring.application.pid` у Spring-encoder'а, иначе текущего процесса) |
| `process.thread.name` | поток |
| `system`, `service`, `reporter` | поля сервиса верхнего уровня |
| `servicesc.version`, `.environment`, `.name`, `.commit`, `.node.name` | поля сервиса |
| `message` | текст сообщения — **после** замены секретов `MaskedMessageEncoder`'ом |
| `sequenceNumber` | порядковый номер события Logback |
| *ключи MDC* | плоско на верхнем уровне (`traceId`, `spanId`, `user.id`…) |
| *KV-пары* | ключи с точками разворачиваются во вложенные объекты: `header.Authorization` → `"header":{"Authorization":…}` |
| `error.type`, `error.message`, `error.stack_trace` | если есть исключение |
| `tags` | имена маркеров SLF4J (с вложенными), без повторов |
| `ecs.version` | `8.11` |

Внешние значения (message, MDC, KV) пишутся через экранирование Jackson: CR/LF и кавычки не
ломают строку и не добавляют полей.

## 2. Журнал Logbook — KV-пары `LogbackSink`

Тело обмена — текст сообщения (пустое тело — URL у запроса, reason у ответа).

| Ключ | Запрос | Ответ |
|---|---|---|
| `correlation` | ✓ | ✓ (тот же) |
| `operation` | `request` | `response` |
| `origin` | `incoming` / `outgoing` | `incoming` / `outgoing` |
| `url` | ✓ | — |
| `remote`, `method`, `protocolVersion` | если есть | `protocolVersion` |
| `duration` | — | мс |
| `reason` | — | reason phrase; у Kafka — класс и текст корневой причины ошибки |
| `header.<Имя>` | заголовки (значения через `, `) | заголовки |
| `attribute.<имя>` | атрибуты Logbook | атрибуты |

## 3. Логгеры

| Логгер | Что |
|---|---|
| `toolslog.request.incoming` | входящие запросы и ответы на исходящие (Origin REMOTE) |
| `toolslog.request.outgoing` | ответы на входящие и исходящие запросы (Origin LOCAL) |
| `io.github.olegnyr.toolslog.mask.impl.DefaultSecretMaskingScanner` | `DEBUG`: «secret masked by matcher '<alias>'» — без значения |

Журнал пишется на `INFO`; если оба логгера выше `INFO`, Logbook не активен и тела не буферизует.

## 4. Настройки `toolslog.*` (`tools-log-spring-boot`)

| Ключ | Тип | По умолчанию | Что |
|---|---|---|---|
| `toolslog.masking.pan.enabled` | boolean | `true` | регистрировать `PanSecretMatcher` |
| `toolslog.masking.pan.include-paths` | list | пусто = все пути | Ant-паттерны путей для сканирования тела |
| `toolslog.masking.pan.exclude-paths` | list | пусто | пути без сканирования, главнее include |
| `toolslog.logbook.exclude-paths` | list | `/actuator/**` | пути без журнала; заданный список заменяет умолчание |
| `toolslog.logbook.masked-headers` | list | `cookie, set-cookie` | заголовки → `XXX`, без учёта регистра; `Authorization` маскируется всегда |
| `toolslog.servlet.secret-masking-filter.enabled` | boolean | `true` | фильтр маскирования тела |
| `toolslog.servlet.logbook-filter.enabled` | boolean | `true` | глобальный `LogbookFilter` |
| `logging.structured.ecs.service.*` | — | — | поля сервиса для `SpringStructuredLogEncoder`: `name`, `version`, `environment`, `node-name`, `commit`, `system`, `service`, `reporter` |

## 5. Свойства encoder'ов (`logback.xml`)

`JacksonStructuredLogEncoder` и `SpringStructuredLogEncoder`: `name`, `version`, `environment`,
`nodeName`, `commit`, `system`, `service`, `reporter`. У Spring-encoder'а свойство главнее
`logging.structured.ecs.service.*`, а то — главнее `spring.application.*`.

`MaskedMessageEncoder`: вложенный `<encoder class="…"/>` — любой `Encoder<ILoggingEvent>`.

`PiiLoggerGuard` (`<turboFilter>`): повторяемый `<logger>` — имя логгера или префикс пакета.

## 6. Бины автоконфигурации

| Автоконфигурация | Бины | Условие |
|---|---|---|
| `MaskingAutoConfiguration` | `PanSecretMatcher`, `SecretMaskingScanner` (`DefaultSecretMaskingScanner` над всеми `SecretMatcher`) | `@ConditionalOnMissingBean`; матчер — ещё `toolslog.masking.pan.enabled` |
| `LogbookAutoConfiguration` | `Logbook` с `LogbackSink` | `@ConditionalOnMissingBean` |
| `ServletLogAutoConfiguration` | `FilterRegistrationBean`: `secretMaskingFilter` (`HIGHEST_PRECEDENCE`), `logbookFilter` (`HIGHEST_PRECEDENCE + 1`); диспетчи REQUEST и ASYNC | servlet-приложение, на classpath `tools-log-servlet`; каждый — своё `enabled` |

## 7. API маскирования (`tools-log-api`, пакет `io.github.olegnyr.toolslog.mask`)

`SecretMaskingRegistry` (статические методы, состояние — ThreadLocal):

| Метод | Что |
|---|---|
| `register(String secret, String mask)` | точная замена; `null`/пустой секрет пропускается |
| `register(String secret, SecretMask mask)` | маска считается от значения |
| `maskAll(String text)` | заменить все известные секреты; `null` → `null`, пустой реестр — текст как есть |
| `clear()` | снять реестр потока |
| `snapshotAndClear()` | копия реестра и очистка — перенести «контекст замены» данными на другой поток |
| `getContext()` / `setContext(Map)` | для своих accessor'ов переноса контекста |

`SecretMatcher`: `alias()`, `pattern()`, `mask(String match)` (`null` — не секрет),
`includeUrlPatterns()` / `excludeUrlPatterns()` (по умолчанию пусто), `appliesTo(String path)`.

`SecretMaskingScanner`: `applicable(String path)`, `scanAndRegister(String text)` — все матчеры,
`scanAndRegister(String text, List<SecretMatcher>)`; `SecretMaskingScanner.NOOP` — для тестов.

`Masks`: `hidden()`, `all()`, `keepFirst(n)`, `keepLast(n)`, `keepFirstAndLast(first, last)`,
`email()`. Символы считаются по code point; отрицательное `n` — `IllegalArgumentException`.

`tools-log-core`: `new DefaultSecretMaskingScanner(List<SecretMatcher>)`,
`new PanSecretMatcher()` (все пути) / `new PanSecretMatcher(include, exclude)`.

## 8. Kafka-классы (`tools-log-spring-kafka`, пакет `io.github.olegnyr.toolslog.kafka`)

| Класс | Что |
|---|---|
| `KafkaListenerContainerFactoryBuilder<V>` | фабрика listener'ов: `ErrorHandlingDeserializer` → снимок тела для журнала → Jackson; метрики; Logbook + span для `traceId`; observation |
| `KafkaTemplateBuilder<V>` | шаблон: Jackson без `__TypeId__`, Logbook на каждый `send`, MDC в callback, метрики, observation |
| `LogbookKafkaRecordInterceptor` | `RecordInterceptor`: request на `intercept`, response на `success`/`failure`; сканирует тело ≤ `MAX_SCAN_LENGTH` (64 КБ) |
| `LogbookContextKafkaProducerInterceptor` | `ProducerPostProcessor`: Logbook на `send` и перенос контекста в callback |
| `AuditHeaderProducerInterceptor` / `AuditMdcRecordInterceptor` | поля MDC ↔ заголовки записи; свойство продьюсера `toolslog.audit.mdc-keys` |
| `KafkaBodyMode` | `BODY` / `OFF` — писать ли значение записи телом журнала |
