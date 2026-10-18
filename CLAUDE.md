# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Библиотека логирования `tools-log` для Java 21 / Spring Boot 4 (BOM `spring-boot-dependencies`),
Jackson 3: JSON-лог Logback, Logbook для HTTP и Kafka, маскирование секретов, проброс MDC.
Группа `io.github.olegnyr`, пакеты `io.github.olegnyr.toolslog.*`, настройки `toolslog.*`, логгеры
обменов `toolslog.request.incoming|outgoing`. Готовится к выкладке в Maven Central (`RELEASING.md`),
схема выкладки та же, что у соседнего проекта `business-knobs`.

Библиотека общего назначения: в коде, тестах и документах нет привязки к конкретной компании или
сервису. Пути, заголовки и поля MDC сервиса задаются настройками или параметрами билдеров; в
тестах — нейтральные `/api/messages/**`, `com.example.orders`, `user.id`.

## Сборка

```bash
./mvnw verify
./mvnw -P quality verify                        # Checkstyle, PMD, SpotBugs + FindSecBugs
./mvnw -P quality,release -Dgpg.skip verify     # то же, что CI: плюс sources/javadoc
./mvnw test -pl tools-log-core -Dtest=PanSecretMatcherTest            # один тест-класс
./mvnw test -pl tools-log-core -Dtest='PanSecretMatcherTest#mask*'    # методы
```

Тесты — только через фазы (`test`, `verify`), не голым `surefire:test`: Mockito подключается как
`-javaagent`, путь к нему кладёт `maven-dependency-plugin:properties` в фазе `initialize`.
Для `-pl` модуля, зависящего от других модулей реактора, добавлять `-am` или сначала `install`.

## Модули

`api` (только JDK: `SecretMatcher`, `SecretMaskingRegistry`, `SecretMaskingScanner` — точка расширения
маскирования для сервисов), `core` (реализации в `mask.impl`, `ThreadLocalAccessor`'ы), `logback`,
`logbook`, `servlet` — без Spring; `logback` и `servlet` зависят от `api`, не от `core`; правило `no-spring` (`maven-enforcer`,
`toolslog.spring-allowed=false` в их pom) валит сборку, если Spring попадёт в их зависимости.
`spring-boot` — автоконфигурации (`MaskingAutoConfiguration`, `LogbookAutoConfiguration`,
`ServletLogAutoConfiguration`) и `SpringStructuredLogEncoder`; servlet и logback у него optional.
`spring-kafka` — Spring Kafka, своей автоконфигурации нет: бины сервис собирает билдерами.
Границы и правила — `.claude/rules/contribution.md`, стиль кода — `.claude/rules/codestyle.md`.

## Сквозные механизмы

- **Маскирование** (api/core → servlet / spring-kafka → logback): `SecretMaskingScanner` прогоняет тело
  через `SecretMatcher`'ы и кладёт пары секрет→маска в `SecretMaskingRegistry` (ThreadLocal);
  замену делает `MaskedMessageEncoder` при записи сообщения. Регистрируют `SecretMaskingFilter`
  (тело входящего HTTP) и `LogbookKafkaRecordInterceptor` (тело записи); оба чистят реестр в
  `finally`. Через async-границу реестр и MDC переносят `ThreadLocalAccessor`'ы из SPI-файла core.
- **Logbook:** один бин на все транспорты; `LogbackSink` пишет обмен событием SLF4J с KV-парами
  (`header.*`, `correlation`, …), тело — текстом сообщения. Kafka-записи адаптируются к
  Logbook-интерфейсам (`Kafka{Producer,Consumer}Http{Request,Response}`); consumer хранит
  незавершённые обмены в Caffeine с TTL, ключ — UUID в служебном заголовке, который не логируется.
- **JSON-лог:** `FormatterElastic` (горячий путь — кэш имён полей, пул буферов, KV с точками
  разворачиваются во вложенные объекты); поля сервиса — `ServiceInfo`. Базовый encoder берёт их
  из своих свойств в `logback.xml`, Spring-наследник — из `Environment`, который кладёт в контекст
  Logback система логирования Spring Boot.
- `logbook-spring` не использовать: собран под Spring 6 и падает на Spring 7
  (`IncompatibleClassChangeError` из-за `HttpHeaders`).
