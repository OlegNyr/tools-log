---
apply: always
---

# Правила разработки

**Стек:** Java 21 · Spring Boot 4 (BOM) · Jackson 3 · Logback · Logbook · Lombok · Micrometer
**Форма:** библиотека из модулей без Spring (`api`, `core`, `logback`, `logbook`, `servlet`) и Spring-адаптеров
(`spring-boot`, `spring-kafka`)

Библиотеку подключают чужие сервисы, поэтому всё, что видно снаружи, — пакеты, имена свойств
`toolslog.*`, имена логгеров `toolslog.request.*`, поля JSON-лога, — меняется только осознанно,
с записью в `CHANGELOG.md`.

## 1. Модули и границы

```
tools-log-api           ← SecretMatcher, SecretMaskingRegistry, SecretMaskingScanner; только JDK
   ↑
tools-log-core          ← DefaultSecretMaskingScanner, PanSecretMatcher (пакет mask.impl), ThreadLocalAccessor'ы
tools-log-logback       ← JSON-encoder, MaskedMessageEncoder, PiiLoggerGuard; от api, не от core
tools-log-servlet       ← SecretMaskingFilter (+ logbook-servlet); от api, не от core
tools-log-logbook       ← LogbackSink; от api/core не зависит
   ↑
tools-log-spring-boot   ← автоконфигурация и настройки над модулями выше; servlet и logback — optional
tools-log-spring-kafka  ← Spring Kafka: перехватчики, адаптеры записей к Logbook, билдеры; от core (SPI MDC)
```

- `tools-log-api` — точка расширения для сервисов: в нём нет зависимостей, кроме JDK, и только то,
  что видит код сервиса. Реализации — в `core`, в другом пакете (`mask.impl`): один пакет в двух jar
  ломает модули Java.
- Зависимости однонаправленные. В модулях без Spring его нет в compile/runtime/provided — это
  держит правило `no-spring` (`maven-enforcer`, свойство `toolslog.spring-allowed=false`).
  В тестах Spring допустим.
- Поведение — в модулях без Spring; `tools-log-spring-boot` только собирает их классы в бины и
  связывает настройки. Новая функция Spring Boot — сначала класс без Spring, потом бин.
- Автоконфигурация регистрируется в `META-INF/spring/...AutoConfiguration.imports`; условия на
  optional-модули — `@ConditionalOnClass(name = ...)` строками.
- Настройки — `@ConfigurationProperties` с префиксом `toolslog.*`, final-класс со связыванием
  через конструктор, Javadoc на каждом поле (из него собираются метаданные для IDE). Переключатели
  без класса настроек описываются в `additional-spring-configuration-metadata.json`.
- Конкретные пути, заголовки, имена полей MDC сервиса в код не зашиваются — только настройки или
  параметры билдеров. Ссылки на внутренние трекеры и чужие проекты в коде не пишутся.

## 2. Маскирование

- Секрет заменяется **при записи лога** (`MaskedMessageEncoder` по `SecretMaskingRegistry`), тело
  запроса не меняется. Заголовки маскирует Logbook `HeaderFilter`.
- Кто открыл реестр на потоке, обязан закрыть его в `finally`. Новый код, регистрирующий секреты,
  соблюдает это правило; переход потока — через `MaskingThreadLocalAccessor` или
  `SecretMaskingRegistry.snapshotAndClear()`.
- Внешние значения (message, MDC, KV) в JSON пишутся только через `writeString`/`writeName`
  Jackson — так событие остаётся одной строкой. Проверяется `FormatterElasticTest`, раздел «Log forging».

## 3. Lombok

Разрешено: `@Getter`, `@Setter` на отдельных полях (свойства компонентов Logback), `@Slf4j`,
`@Builder` для неизменяемых моделей, `@Delegate`. Запрещено: `@Data`, `@AllArgsConstructor` на
публичных классах, `@Setter` на уровне класса. Lombok — `optional`, в рантайм сервиса не попадает.

## 4. Тесты

- JUnit 5 + AssertJ; Mockito подключается как `-javaagent` (запуск — через фазы `test`/`verify`).
- Автоконфигурация — `ApplicationContextRunner` / `WebApplicationContextRunner`.
- Именование `methodName_shouldDoSomething_whenCondition`, `@DisplayName` — по-русски, о поведении;
  структура Arrange / Act / Assert.

## 5. Definition of Done

1. Поведение покрыто тестами.
2. `./mvnw -P quality,release -Dgpg.skip verify` зелёный (то же, что делает CI).
3. Javadoc публичного API обновлён; изменение внешнего контракта — в `CHANGELOG.md` и `README.md`.
