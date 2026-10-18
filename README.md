# tools-log

[![build](https://github.com/OlegNyr/tools-log/actions/workflows/build.yaml/badge.svg)](https://github.com/OlegNyr/tools-log/actions/workflows/build.yaml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.olegnyr/tools-log-core)](https://central.sonatype.com/namespace/io.github.olegnyr)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Логирование сервиса одной связкой: структурный JSON-лог Logback, журнал HTTP и Kafka через
[Logbook](https://github.com/zalando/logbook), маскирование секретов и персональных данных в любом
логе запроса, проброс MDC через потоки и Kafka.

- **JSON-лог.** Каждое событие — одна строка JSON (формат близок к ECS). CR/LF и кавычки во внешних
  значениях экранируются: подделать запись через значение нельзя.
- **Журнал обменов.** Входящий HTTP и Kafka (consume и produce) пишутся Logbook'ом как пары
  запрос/ответ с общим `correlation`; в consumer-логе есть `traceId`.
- **Маскирование.** Секрет — найденный в теле запроса или известный коду — заменяется маской во
  **всех** строках лога этого запроса: и в журнале Logbook, и в прикладных логах. Regex по каждой
  строке не гоняется.
- **Заголовки.** `Authorization`, `Cookie`, `Set-Cookie` и настроенные заменяются на `XXX`.
- **Защита ПДн от DEBUG.** `DEBUG`/`TRACE` выбранных логгеров не пишется никогда — даже если уровень
  включили через `/actuator/loggers`.

Java 21, Spring Boot 4, Jackson 3. Модули `api`, `core`, `logback`, `logbook`, `servlet` работают и
без Spring.

Так выглядит запись входящего запроса — номер карты из тела замаскирован, `Authorization` скрыт:

```json
{
  "@timestamp": "2026-10-18T11:46:42.857165009Z",
  "level": "INFO",
  "logger": "toolslog.request.incoming",
  "process": {
    "pid": 923454,
    "thread": {
      "name": "http-nio-8080-exec-1"
    }
  },
  "servicesc": {
    "version": "1.4.0",
    "environment": "prod",
    "name": "orders"
  },
  "message": "{\"text\":\"оплата картой ************1111\"}",
  "sequenceNumber": 0,
  "traceId": "6a1f0c2d9b8e4f71",
  "user.id": "u-42",
  "correlation": "d7ff0b927fd94469",
  "header": {
    "Authorization": "XXX",
    "Content-Type": "application/json"
  },
  "method": "POST",
  "operation": "request",
  "origin": "incoming",
  "url": "http://localhost/api/messages",
  "ecs": {
    "version": "8.11"
  }
}
```

## Модули

Группа `io.github.olegnyr`.

| Артефакт | Spring | Что внутри |
|---|---|---|
| `tools-log-api` | нет | `SecretMatcher`, `SecretMaskingRegistry`, маски `Masks`, контракт сканера. Зависимостей нет, только JDK |
| `tools-log-core` | нет | `DefaultSecretMaskingScanner`, матчер номеров карт, проброс MDC и реестра секретов через потоки |
| `tools-log-logback` | нет | `JacksonStructuredLogEncoder` (JSON), `MaskedMessageEncoder`, `PiiLoggerGuard` |
| `tools-log-logbook` | нет | `LogbackSink` — обмены Logbook как структурные события SLF4J |
| `tools-log-servlet` | нет | `SecretMaskingFilter` и всё для `LogbookFilter` |
| `tools-log-spring-boot` | да | Автоконфигурация: сканер, общий `Logbook`, servlet-фильтры, настройки `toolslog.*`; `SpringStructuredLogEncoder` |
| `tools-log-spring-kafka` | да | Logbook для Spring Kafka, `traceId` и аудит-поля MDC через заголовки, билдеры фабрики listener'ов и `KafkaTemplate` |

Что подключить:

| Задача | Артефакты |
|---|---|
| Spring Boot, servlet-приложение | `tools-log-spring-boot`, `tools-log-servlet`, `tools-log-logback` |
| …и Kafka | + `tools-log-spring-kafka` |
| Свой класс секрета в библиотеке предметной области | `tools-log-api` |
| Без Spring | `tools-log-core`, `tools-log-logback`, `tools-log-servlet` |

## Быстрый старт (Spring Boot)

```xml
<properties>
    <tools-log.version>0.1.0</tools-log.version>
</properties>

<dependency>
    <groupId>io.github.olegnyr</groupId>
    <artifactId>tools-log-spring-boot</artifactId>
    <version>${tools-log.version}</version>
</dependency>
<dependency>
    <groupId>io.github.olegnyr</groupId>
    <artifactId>tools-log-servlet</artifactId>
    <version>${tools-log.version}</version>
</dependency>
<dependency>
    <groupId>io.github.olegnyr</groupId>
    <artifactId>tools-log-logback</artifactId>
    <version>${tools-log.version}</version>
</dependency>
```

`src/main/resources/logback-spring.xml`:

```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <!-- MaskedMessageEncoder заменяет зарегистрированные секреты; без него маскирования нет -->
        <encoder class="io.github.olegnyr.toolslog.logback.MaskedMessageEncoder">
            <encoder class="io.github.olegnyr.toolslog.boot.SpringStructuredLogEncoder"/>
        </encoder>
    </appender>

    <!-- DEBUG/TRACE этих логгеров не пишется никогда: на debug они печатают значения параметров -->
    <turboFilter class="io.github.olegnyr.toolslog.logback.PiiLoggerGuard">
        <logger>com.example.orders.persistence</logger>
    </turboFilter>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

Больше ничего не нужно: фильтры маскирования и Logbook регистрируются сами, обмены пишутся в
логгеры `toolslog.request.incoming` / `toolslog.request.outgoing` на уровне `INFO`.

`SpringStructuredLogEncoder` берёт поля сервиса из `logging.structured.ecs.service.*` (`name`,
`version`, `environment`, `node-name`, `commit`, а также `system`, `service`, `reporter`), имя и
версию — запасным путём из `spring.application.name` / `spring.application.version`. Свойство,
заданное у encoder'а в `logback-spring.xml`, главнее.

### Настройки

| Ключ | По умолчанию | Что |
|---|---|---|
| `toolslog.masking.pan.enabled` | `true` | Матчер номеров карт |
| `toolslog.masking.pan.include-paths` | все пути | Ant-паттерны путей, где тело сканируется на номера карт |
| `toolslog.masking.pan.exclude-paths` | — | Пути, где тело не сканируется (главнее include) |
| `toolslog.logbook.exclude-paths` | `/actuator/**` | Пути, обмен по которым не логируется |
| `toolslog.logbook.masked-headers` | `cookie, set-cookie` | Заголовки, значения которых заменяются на `XXX`; `Authorization` — всегда |
| `toolslog.servlet.secret-masking-filter.enabled` | `true` | Фильтр маскирования тела |
| `toolslog.servlet.logbook-filter.enabled` | `true` | Глобальный фильтр Logbook; `false` — поставить `LogbookFilter` самому, например после аутентификации |

```yaml
toolslog:
  masking:
    pan:
      include-paths: /api/payments/**, /api/messages/**
  logbook:
    exclude-paths: /actuator/**, /static/**
    masked-headers: cookie, set-cookie, x-user-id
```

Свой бин `Logbook` или `SecretMaskingScanner` в сервисе заменяет библиотечный.

## Маскирование

Секрет попадает в реестр текущего запроса (`SecretMaskingRegistry`) вместе со своей маской; при
записи каждой строки лога `MaskedMessageEncoder` заменяет в тексте все известные секреты их масками.
Данные запроса не меняются — меняется только то, что уходит в лог.

```
тело запроса ──► SecretMatcher'ы (regex, один раз) ──┐
код сервиса ──► SecretMaskingRegistry.register() ────┴──► реестр запроса ──► каждая строка лога:
                                                                              String.replace по реестру
```

### Значение известно коду

Выданный токен, телефон и ФИО из загруженного профиля — regex не нужен, значение регистрируется
там, где появилось:

```java
SecretMaskingRegistry.register(token, Masks.hidden());                 // ***
SecretMaskingRegistry.register(profile.phone(), Masks.keepLast(2));    // **********67
SecretMaskingRegistry.register(profile.email(), Masks.email());        // i***@example.com
SecretMaskingRegistry.register(card, Masks.keepFirstAndLast(6, 4));    // 411111******1111
```

| Маска | Результат |
|---|---|
| `Masks.hidden()` | `***` — скрывает и длину |
| `Masks.all()` | звёздочка на каждый символ |
| `Masks.keepLast(n)` / `Masks.keepFirst(n)` | видны последние / первые `n` символов |
| `Masks.keepFirstAndLast(first, last)` | видны края |
| `Masks.email()` | первая буква и домен |
| `secret -> ...` | своя маска (`SecretMask`) |

Значение не длиннее видимой части скрывается целиком.

### Свой класс секрета

Секрет, который надо найти в тексте, описывает `SecretMatcher`. Для него достаточно `tools-log-api` —
модуль без зависимостей, его можно подключить и в библиотеку предметной области.

```java
@Bean
SecretMatcher apiKeyMatcher() {
    return new SecretMatcher() {
        @Override
        public String alias() {
            return "api-key";
        }

        @Override
        public Pattern pattern() {
            return Pattern.compile("ak_[A-Za-z0-9]{32}");
        }

        @Override
        public String mask(String match) {
            return Masks.keepFirst(3).apply(match);    // null — не секрет, не маскировать
        }

        @Override
        public List<String> includeUrlPatterns() {
            return List.of("/api/keys/**");             // пусто — все пути
        }
    };
}
```

В Spring Boot матчер — просто бин: сканер соберёт его вместе с матчером карт.

### Где и когда работает

| Где | Сканирование | Очистка реестра |
|---|---|---|
| Входящий HTTP | JSON-тело до 64 КБ, если путь подходит хотя бы одному матчеру | после ответа — `SecretMaskingFilter` |
| Kafka consume | тело записи до 64 КБ | сразу после записи request-события, до кода listener'а |
| Async-граница | — | реестр переносится `MaskingThreadLocalAccessor` (context-propagation) |
| Свой поток, фоновая задача | — | сам, `SecretMaskingRegistry.clear()` в `finally` |

Ограничения:

- маскируется текст сообщения; поля MDC и KV-пары — нет (заголовки маскирует сам Logbook);
- замена буквальная: короткий секрет вроде `123` заменится везде, где встречается подстрока;
- в Kafka listener'е секреты из тела записи уже сняты — чтобы маскировать их в логах listener'а,
  зарегистрируйте нужные значения в нём самом.

## Kafka (Spring Kafka)

```xml
<dependency>
    <groupId>io.github.olegnyr</groupId>
    <artifactId>tools-log-spring-kafka</artifactId>
    <version>${tools-log.version}</version>
</dependency>
```

Автоконфигурации нет — бины собираются билдерами:

```java
@Bean
ConcurrentKafkaListenerContainerFactory<String, OrderEvent> orderEventsListenerFactory(
        KafkaProperties props, JsonMapper jsonMapper, MeterRegistry meter, DefaultErrorHandler errorHandler,
        Logbook logbook, Tracer tracer, Propagator propagator, SecretMaskingScanner scanner) {
    return new KafkaListenerContainerFactoryBuilder<OrderEvent>()
            .valueType(jsonMapper.constructType(OrderEvent.class))
            .consumerProperties(props.buildConsumerProperties())
            .jsonMapper(jsonMapper)
            .meterRegistry(meter)
            .errorHandler(errorHandler)
            .logbook(logbook)
            .tracer(tracer)
            .propagator(propagator)
            .secretMaskingScanner(scanner)
            .auditMdcKeys(List.of("user.id"))   // поля MDC из заголовков записи
            .build();
}

@Bean
KafkaTemplate<String, OrderEvent> orderEventsTemplate(
        KafkaProperties props, JsonMapper jsonMapper, Logbook logbook, MeterRegistry meter) {
    return new KafkaTemplateBuilder<OrderEvent>()
            .producerProperties(props.buildProducerProperties())
            .jsonMapper(jsonMapper)
            .logbook(logbook)
            .meterRegistry(meter)
            .auditMdcKeys(List.of("user.id"))   // поля MDC в заголовки записи
            .build();
}
```

- `auditMdcKeys` — поля MDC, которые едут от продьюсера к консьюмеру заголовками записи; по
  умолчанию — ничего. Свои `interceptor.classes` продьюсера сохраняются.
- Трассировку (`Tracer`, `Propagator`) даёт мост, который выбрал сервис (Brave или OpenTelemetry):
  библиотека зависит только от API `micrometer-tracing`.
- Метрики `kafka_consumer_*` / `kafka_producer_*` регистрируются в переданном `MeterRegistry`.

## Без Spring

```java
Logbook logbook = Logbook.builder()
        .headerFilter(HeaderFilters.authorization())
        .sink(new LogbackSink(LoggerFactory.getLogger("toolslog.request")))
        .build();
SecretMaskingScanner scanner = new DefaultSecretMaskingScanner(List.of(new PanSecretMatcher()));

// в цепочке фильтров servlet-контейнера: сначала маскирование, затем Logbook
Filter masking = new SecretMaskingFilter(scanner);
Filter logbookFilter = new LogbookFilter(logbook);
```

`logback.xml` — поля сервиса задаются свойствами encoder'а:

```xml
<encoder class="io.github.olegnyr.toolslog.logback.MaskedMessageEncoder">
    <encoder class="io.github.olegnyr.toolslog.logback.JacksonStructuredLogEncoder">
        <name>orders</name>
        <version>1.4.0</version>
        <environment>prod</environment>
    </encoder>
</encoder>
```

Свойства: `name`, `version`, `environment`, `nodeName`, `commit`, `system`, `service`, `reporter`;
`pid` — текущего процесса.

## Сборка

```bash
./mvnw verify
./mvnw -P quality verify                     # Checkstyle, PMD, SpotBugs + FindSecBugs
./mvnw -P quality,release -Dgpg.skip verify  # то же, что CI: плюс sources и javadoc для Central
```

Модули без Spring держат границу правилом `maven-enforcer`: Spring в их зависимостях (кроме
тестовых) валит сборку.

Выкладка — [RELEASING.md](RELEASING.md), изменения — [CHANGELOG.md](CHANGELOG.md).

## Лицензия

[Apache License 2.0](LICENSE).
