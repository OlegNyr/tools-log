---
name: tools-log
description: How to add and use the tools-log logging library (io.github.olegnyr:tools-log-api / -core / -logback / -logbook / -servlet / -spring-boot / -spring-kafka) in a Java 21 / Spring Boot 4 service — structured JSON logs for Logback, Logbook request/response journal for incoming HTTP and Kafka, masking of card numbers, tokens and personal data in every log line of a request, MDC propagation across threads and Kafka headers. Covers choosing modules, logback-spring.xml, toolslog.* properties, SecretMaskingRegistry with Masks, custom SecretMatcher beans, PiiLoggerGuard, Kafka listener/template builders, setup without Spring, and testing. Use whenever a task mentions tools-log, toolslog.*, JSON-логи, структурное логирование, Logbook, журнал запросов, маскирование в логах, PAN / номер карты / ПДн / токен в логе, SecretMatcher, SecretMaskingRegistry, Masks, PiiLoggerGuard, MaskedMessageEncoder, logback-spring.xml, traceId в логах Kafka, KafkaListenerContainerFactoryBuilder, KafkaTemplateBuilder — or asks to hide sensitive data in logs or to log HTTP/Kafka traffic, even if the library is not named.
---

# tools-log: логирование сервиса

Библиотека даёт сервису одной связкой: JSON-лог (одна строка на событие, формат близок к ECS),
журнал обменов Logbook для входящего HTTP и Kafka, маскирование секретов во всех строках лога
запроса и проброс MDC через потоки и Kafka. Java 21, Spring Boot 4, Jackson 3, группа
`io.github.olegnyr`, версия — последняя в Maven Central (на момент написания `0.1.0`).

Справка по полям лога, логгерам, свойствам и классам — `reference/reference.md`. Читать, когда нужен
точный ключ, поле JSON или сигнатура.

## 1. Какие модули подключить

| Задача сервиса | Артефакты |
|---|---|
| Spring Boot, servlet-приложение (MVC) | `tools-log-spring-boot` + `tools-log-servlet` + `tools-log-logback` |
| …плюс Kafka | + `tools-log-spring-kafka` |
| Только свой класс секрета или регистрация секретов (например, модуль предметной области) | `tools-log-api` — без зависимостей, только JDK |
| Без Spring | `tools-log-core` + `tools-log-logback` + `tools-log-servlet` |

Что где лежит:

| Модуль | Пакет | Spring |
|---|---|---|
| `tools-log-api` | `io.github.olegnyr.toolslog.mask` — `SecretMatcher`, `SecretMaskingRegistry`, `Masks`, `SecretMask`, `SecretMaskingScanner` | нет |
| `tools-log-core` | `io.github.olegnyr.toolslog.mask.impl` — `DefaultSecretMaskingScanner`, `PanSecretMatcher`; `io.github.olegnyr.toolslog.context` — проброс MDC и реестра | нет |
| `tools-log-logback` | `io.github.olegnyr.toolslog.logback` — `JacksonStructuredLogEncoder`, `MaskedMessageEncoder`, `PiiLoggerGuard` | нет |
| `tools-log-logbook` | `io.github.olegnyr.toolslog.logbook` — `LogbackSink` | нет |
| `tools-log-servlet` | `io.github.olegnyr.toolslog.servlet` — `SecretMaskingFilter` (+ `logbook-servlet`) | нет |
| `tools-log-spring-boot` | `io.github.olegnyr.toolslog.boot` — автоконфигурации, `SpringStructuredLogEncoder` | да |
| `tools-log-spring-kafka` | `io.github.olegnyr.toolslog.kafka` — перехватчики и билдеры | да (Spring Kafka) |

Версию держите одним свойством (`<tools-log.version>`), модули выпускаются вместе.

## 2. Spring Boot: подключить

```xml
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

Автоконфигурация сама поднимает: сканер секретов со всеми бинами `SecretMatcher` и матчером
номеров карт, общий бин `Logbook` (маскирует `Authorization`, `Cookie`, `Set-Cookie`, не логирует
`/actuator/**`), фильтр маскирования тела (`HIGHEST_PRECEDENCE`) и фильтр Logbook сразу за ним —
оба до Spring Security. Свой бин `Logbook`, `SecretMaskingScanner` или `PanSecretMatcher` в
сервисе заменяет библиотечный.

`src/main/resources/logback-spring.xml`:

```xml
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="io.github.olegnyr.toolslog.logback.MaskedMessageEncoder">
            <encoder class="io.github.olegnyr.toolslog.boot.SpringStructuredLogEncoder"/>
        </encoder>
    </appender>

    <turboFilter class="io.github.olegnyr.toolslog.logback.PiiLoggerGuard">
        <logger>com.example.orders.persistence</logger>
    </turboFilter>

    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

Почему так:

- **`MaskedMessageEncoder` снаружи обязателен.** Замену секретов делает только он. Без обёртки
  сканер и реестр отработают, но в лог уйдут исходные значения — это самая частая ошибка.
- **`SpringStructuredLogEncoder`** берёт поля сервиса из `logging.structured.ecs.service.*`
  (`name`, `version`, `environment`, `node-name`, `commit`, `system`, `service`, `reporter`), имя и
  версию — запасным путём из `spring.application.name` / `spring.application.version`; свойство,
  заданное у encoder'а в XML, главнее. Стектрейс — принтером Spring Boot.
- **`PiiLoggerGuard`** отбрасывает `DEBUG`/`TRACE` перечисленных логгеров (имя или префикс пакета)
  до проверки уровня — даже если уровень поменяли через `/actuator/loggers`. Ставьте на пакеты, чьи
  отладочные логи печатают значения: репозитории и SQL-мапперы с ПДн.
- Журнал обменов пишется в `toolslog.request.incoming` / `toolslog.request.outgoing` на `INFO`.
  Уровень этих логгеров выше `INFO` выключает журнал целиком — Logbook даже не буферизует тела.

Настройки (`application.yml`), все необязательны:

```yaml
toolslog:
  masking:
    pan:
      enabled: true                                      # матчер номеров карт
      include-paths: /api/payments/**, /api/messages/**  # где тело сканируется; пусто — все пути
      exclude-paths: /api/payments/health
  logbook:
    exclude-paths: /actuator/**, /static/**              # не логировать (бинарную статику — обязательно)
    masked-headers: cookie, set-cookie, x-user-id        # → XXX; Authorization — всегда
  servlet:
    secret-masking-filter.enabled: true
    logbook-filter.enabled: true                         # false — поставить LogbookFilter самому
```

`toolslog.logbook.exclude-paths` по умолчанию `/actuator/**`; заданный список его **заменяет**,
поэтому `/actuator/**` повторяйте явно. Пути бинарной статики исключайте всегда: `LogbackSink`
пишет тело как текст независимо от content-type.

Нужна идентичность пользователя в журнале запросов — выключите глобальный фильтр
(`toolslog.servlet.logbook-filter.enabled=false`) и добавьте `new LogbookFilter(logbook)` в
security-цепочку после аутентификации.

## 3. Маскирование секретов и ПДн

Как работает: секрет попадает в реестр текущего запроса (`SecretMaskingRegistry`, ThreadLocal)
вместе с маской; `MaskedMessageEncoder` при записи **каждой** строки делает `String.replace` по
реестру. Данные запроса не меняются — меняется только лог. Regex по строкам лога не гоняется: regex
матчеров выполняются один раз на тело запроса или Kafka-записи.

Выбор способа:

1. **Значение известно коду** (выданный токен, телефон и ФИО из загруженного профиля, номер карты
   из ответа платёжного шлюза) — регистрируйте его там, где оно появилось. Это точнее и дешевле
   regex:

   ```java
   SecretMaskingRegistry.register(token, Masks.hidden());               // ***
   SecretMaskingRegistry.register(profile.phone(), Masks.keepLast(2));  // **********67
   SecretMaskingRegistry.register(profile.email(), Masks.email());      // i***@example.com
   SecretMaskingRegistry.register(card, Masks.keepFirstAndLast(6, 4));  // 411111******1111
   SecretMaskingRegistry.register(passport, "**** ******");             // готовая строка
   ```

   Маски: `hidden()` (`***`, скрывает и длину — для паролей и токенов), `all()`, `keepFirst(n)`,
   `keepLast(n)`, `keepFirstAndLast(first, last)`, `email()`, своя — лямбда `SecretMask`.
   Значение не длиннее видимой части скрывается целиком. `null` и пустая строка пропускаются.

2. **Секрет приходит свободным текстом** (клиент вписал номер карты в сообщение) — нужен
   `SecretMatcher`. Матчер номеров карт уже есть (Luhn, видны последние 4 цифры); свой класс
   секрета — бин:

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
               return Pattern.compile("\\bak_[A-Za-z0-9]{32}\\b");
           }

           @Override
           public String mask(String match) {
               return Masks.keepFirst(3).apply(match);   // null — кандидат не секрет
           }

           @Override
           public List<String> includeUrlPatterns() {
               return List.of("/api/keys/**");            // пусто — все пути
           }
       };
   }
   ```

   Делайте шаблон узким (границы `\b`, фиксированные префиксы) и проверяйте кандидата в `mask()`
   (контрольная сумма, длина), возвращая `null` для ложных срабатываний: всё, что матчер
   зарегистрирует, заменится во **всех** строках запроса. Ограничивайте пути
   (`includeUrlPatterns`, Ant: `?`, `*`, `**`) — на других путях тело не читается вовсе.

Где и когда секрет действует:

| Где | Сканирование тела | Очистка реестра |
|---|---|---|
| Входящий HTTP | JSON-тело ≤ 64 КБ с известной длиной, если путь подходит хотя бы одному матчеру | `SecretMaskingFilter` после ответа |
| Kafka consume | тело записи ≤ 64 КБ, все матчеры (пути нет) | сразу после request-события Logbook — **до** кода listener'а |
| Свой поток, `@Scheduled`, `@Async` без context-propagation | — | сам: `SecretMaskingRegistry.clear()` в `finally` |

Что не маскируется: поля MDC и KV-пары события (заголовки маскирует Logbook по
`masked-headers`), тело длиннее 64 КБ матчерами (зарегистрированные кодом значения — да). Не
кладите секреты в MDC. Короткое значение (`"123"`) заменится везде, где встречается подстрока, —
регистрируйте только значения, которые не путаются с обычным текстом.

Регистрация вне HTTP-запроса — всегда с очисткой:

```java
try {
    SecretMaskingRegistry.register(apiSecret, Masks.hidden());
    syncPartner();
} finally {
    SecretMaskingRegistry.clear();
}
```

Через async-границу реестр и MDC переносит Micrometer context-propagation (`ThreadLocalAccessor`'ы
регистрируются SPI-файлом `tools-log-core`): для Reactor — `spring.reactor.context-propagation=auto`,
для своих пулов — `ContextSnapshotFactory.builder().build().captureAll().wrap(task)`.
Ответ, который уходит на другой поток без context-propagation: `SecretMaskingRegistry.snapshotAndClear()`
и положить результат в атрибут запроса `SecretMaskingFilter.SECRETS_ATTRIBUTE` — фильтр
восстановит реестр на ASYNC-диспетче.

## 4. Kafka (Spring Kafka)

Зависимость `tools-log-spring-kafka`. Автоконфигурации нет — бины собираются билдерами, которым
передают готовые свойства (`KafkaProperties.buildConsumerProperties()` / `buildProducerProperties()`):

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
            .auditMdcKeys(List.of("user.id"))
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
            .auditMdcKeys(List.of("user.id"))
            .build();
}
```

- Обязательные у фабрики: `valueType`, `consumerProperties`, `jsonMapper`, `meterRegistry`,
  `errorHandler`, `logbook`, `tracer`, `propagator`; у шаблона — `producerProperties`, `jsonMapper`,
  `logbook`, `meterRegistry`. Без обязательного — `NullPointerException` на `build()` с именем поля.
- Ключ записи — `String`, значение — JSON через Jackson 3 без заголовка `__TypeId__`: полиморфизм —
  `@JsonTypeInfo` в самом классе. Битый JSON уходит в `errorHandler` (DLQ), а не роняет контейнер.
- Обычный `generic`-тип значения — `jsonMapper.getTypeFactory().constructParametricType(...)`.
- `Tracer` и `Propagator` даёт мост трассировки сервиса. В Spring Boot 4 для Brave нужны
  `io.micrometer:micrometer-tracing-bridge-brave` **и** `org.springframework.boot:spring-boot-micrometer-tracing-brave`
  (без второго бина `Tracer` нет). Трассировки нет — передайте `Tracer.NOOP` и `Propagator.NOOP`:
  журнал пишется, просто без `traceId`.
- `auditMdcKeys` — поля MDC, которые едут от продьюсера к консьюмеру заголовками записи (пара:
  у шаблона пишет, у фабрики читает и снимает после записи). По умолчанию — ничего. Свои
  `interceptor.classes` продьюсера сохраняются. Поля не должны входить в
  `management.tracing.baggage.correlation.fields`.
- Опции фабрики: `ackMode`, `assignmentCommitOption` (`NEVER` — для broadcast-групп с group.id на
  под), `observationEnabled(false)` — для высокочастотных служебных потоков, `rebalanceListener`.
- DLQ-шаблон (`byte[]`-значения) билдером не собирают: там нет Jackson и журнал не нужен.

URL записи в журнале: consume — `kafka://<topic>/<topic>?<topic>-<partition>@<offset>/<group>`,
produce — `kafka://broker/<topic>?partition=…&timestamp=…`.

## 5. Без Spring

```java
Logbook logbook = Logbook.builder()
        .headerFilter(HeaderFilter.merge(HeaderFilters.authorization(),
                HeaderFilters.replaceHeaders(
                        name -> "cookie".equalsIgnoreCase(name) || "set-cookie".equalsIgnoreCase(name), "XXX")))
        .sink(new LogbackSink(LoggerFactory.getLogger("toolslog.request")))
        .build();
SecretMaskingScanner scanner = new DefaultSecretMaskingScanner(List.of(
        new PanSecretMatcher(List.of("/api/messages/**"), List.of())));

// цепочка фильтров контейнера: сначала маскирование, затем Logbook
servletContext.addFilter("secretMasking", new SecretMaskingFilter(scanner)).addMappingForUrlPatterns(
        EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC), false, "/*");
servletContext.addFilter("logbook", new LogbookFilter(logbook)).addMappingForUrlPatterns(
        EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC), true, "/*");
```

`logback.xml` — `JacksonStructuredLogEncoder` внутри `MaskedMessageEncoder`, поля сервиса —
свойствами encoder'а (`<name>`, `<version>`, `<environment>`, `<nodeName>`, `<commit>`, `<system>`,
`<service>`, `<reporter>`); `pid` — текущего процесса, стектрейс — конвертером Logback.

## 6. Проверить в сервисе

- **Матчер** — юнит-тестом без Spring:

  ```java
  SecretMaskingScanner scanner = new DefaultSecretMaskingScanner(List.of(new ApiKeyMatcher()));
  scanner.scanAndRegister("key=ak_" + "a".repeat(32));
  assertThat(SecretMaskingRegistry.maskAll("ak_" + "a".repeat(32))).isEqualTo("ak_*****…");
  SecretMaskingRegistry.clear();   // в @AfterEach — реестр живёт в потоке теста
  ```

- **Регистрация в коде** — `SecretMaskingRegistry.maskAll("…значение…")` сразу после вызова кода,
  который регистрирует; очистка в `@AfterEach`.
- **Весь путь до лога** — `ListAppender` на логгер и `MaskedMessageEncoder` над
  `JacksonStructuredLogEncoder` с контекстом этого логгера: закодированная строка не должна
  содержать исходное значение.
- **Автоконфигурация** — `ApplicationContextRunner` с
  `AutoConfigurations.of(MaskingAutoConfiguration.class, LogbookAutoConfiguration.class)`.

## 7. Чего не делать

- Не подключать `org.zalando:logbook-spring`: он собран под Spring 6 и падает на Spring 7
  (`IncompatibleClassChangeError` из-за `HttpHeaders`). Исходящий HTTP — адаптер Logbook для своего
  клиента (`logbook-httpclient5` и т. п.) с общим бином `Logbook`.
- Не санитайзить значения перед `log.info(...)`: JSON-encoder сам экранирует CR/LF и кавычки, а
  ручная правка теряет исходное значение.
- Не логировать секрет до его регистрации: строка, записанная раньше `register(...)`, уже ушла.
