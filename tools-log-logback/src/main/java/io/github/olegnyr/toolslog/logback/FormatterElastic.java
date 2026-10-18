package io.github.olegnyr.toolslog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import tools.jackson.core.JsonEncoding;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.io.SerializedString;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.util.BufferRecycler;
import tools.jackson.core.util.ByteArrayBuilder;
import tools.jackson.core.util.JsonRecyclerPools;
import tools.jackson.core.util.RecyclerPool;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class FormatterElastic {

    private static final byte[] ENDEOF = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
    // JsonFactory потокобезопасен и переиспользуем (документированная практика Jackson) — один общий
    // инстанс на все события. NB: symbol-table канонизации имён работает только на ПАРСИНГЕ; на записи
    // Jackson ничего не кэширует, поэтому имена полей кэшируем сами через SerializedString ниже.
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    // Пул BufferRecycler для ByteArrayBuilder: сегменты выходного буфера переиспользуются между
    // событиями (не аллоцируются заново на каждое), а сам builder — без synchronized на write.
    private static final RecyclerPool<BufferRecycler> RECYCLER_POOL = JsonRecyclerPools.defaultPool();
    private static final String[] EMPTY_PATH = new String[0];
    private static final Comparator<KeyValuePair> KEY_COMPARATOR = Comparator.comparing(pair -> pair.key);

    // Имена полей — константы: заквоченные UTF-8-байты имени кэшируются внутри SerializedString,
    // на событие остаётся один System.arraycopy вместо повторного экранирования + кодирования.
    private static final SerializedString N_TIMESTAMP = new SerializedString("@timestamp");
    private static final SerializedString N_LEVEL = new SerializedString("level");
    private static final SerializedString N_LOGGER = new SerializedString("logger");
    private static final SerializedString N_PROCESS = new SerializedString("process");
    private static final SerializedString N_PID = new SerializedString("pid");
    private static final SerializedString N_THREAD = new SerializedString("thread");
    private static final SerializedString N_NAME = new SerializedString("name");
    private static final SerializedString N_SYSTEM = new SerializedString("system");
    private static final SerializedString N_SERVICE = new SerializedString("service");
    private static final SerializedString N_REPORTER = new SerializedString("reporter");
    private static final SerializedString N_SERVICESC = new SerializedString("servicesc");
    private static final SerializedString N_MESSAGE = new SerializedString("message");
    private static final SerializedString N_SEQUENCE_NUMBER = new SerializedString("sequenceNumber");
    private static final SerializedString N_ERROR = new SerializedString("error");
    private static final SerializedString N_TYPE = new SerializedString("type");
    private static final SerializedString N_STACK_TRACE = new SerializedString("stack_trace");
    private static final SerializedString N_TAGS = new SerializedString("tags");
    private static final SerializedString N_ECS = new SerializedString("ecs");
    // "ecs":{"version":"8.11"} не меняется за всю жизнь процесса — пишем готовым фрагментом
    // через writeRawValue (unquoted UTF-8-байты копируются как есть).
    private static final SerializedString V_ECS = new SerializedString("{\"version\":\"8.11\"}");

    // Разбиение dotted-ключей KV-пар ({@code header.Authorization}) кэшируем: набор ключей стабилен
    // и мал (в основном header.* от Logbook), поэтому split достаточно посчитать один раз на ключ.
    // Потолок обязателен: часть ключей ({@code header.*}) содержит имена заголовков, контролируемые
    // клиентом — без лимита кэш растёт неограниченно (внешне управляемая утечка памяти).
    private static final int KEY_SEGMENTS_MAX = 1024;
    private static final Map<String, String[]> KEY_SEGMENTS = new ConcurrentHashMap<>();

    private final Function<ILoggingEvent, String> stackTraceRenderer;
    private final Long pid;
    // Константные за жизнь процесса поддеревья service-блока, отрендеренные один раз в конструкторе:
    // на событии остаётся writeName(cached) + writeString(cached)/writeRawValue(cached). null —
    // когда serviceInfo пуст (блок целиком не пишется).
    private final SerializedString systemValue;
    private final SerializedString serviceValue;
    private final SerializedString reporterValue;
    private final SerializedString servicescFragment;

    /**
     * Форматер с заданными полями сервиса.
     *
     * @param serviceInfo        поля сервиса; {@link ServiceInfo#NONE} — блока сервиса нет
     * @param pid                pid процесса; {@code null} — поле не пишется
     * @param stackTraceRenderer стектрейс события с исключением
     */
    public FormatterElastic(ServiceInfo serviceInfo, Long pid, Function<ILoggingEvent, String> stackTraceRenderer) {
        this.pid = pid;
        this.stackTraceRenderer = stackTraceRenderer;
        if (serviceInfo.isEmpty()) {
            this.systemValue = null;
            this.serviceValue = null;
            this.reporterValue = null;
            this.servicescFragment = null;
        } else {
            this.systemValue = serializedOrNull(serviceInfo.system());
            this.serviceValue = serializedOrNull(serviceInfo.service());
            this.reporterValue = serializedOrNull(serviceInfo.reporter());
            this.servicescFragment = renderServicesc(serviceInfo);
        }
    }

    // Значение поля как SerializedString (кэш заквоченных UTF-8-байт) либо null для blank —
    // повторяет skip-семантику writeNullable для константных service-полей.
    private static SerializedString serializedOrNull(String value) {
        if (value == null) {
            return null;
        }
        return StringUtils.isNotBlank(value) ? new SerializedString(value) : null;
    }

    // Рендерит объект servicesc один раз (реальным генератором — значения корректно экранируются),
    // результат пишется на событии через writeRawValue. Вызывается только когда serviceInfo не пуст.
    @SneakyThrows
    private static SerializedString renderServicesc(ServiceInfo info) {
        ByteArrayBuilder buffer = new ByteArrayBuilder();
        try (JsonGenerator generator =
                 JSON_FACTORY.createGenerator(ObjectWriteContext.empty(), buffer, JsonEncoding.UTF8)) {
            SimpleJsonGenerator json = new SimpleJsonGenerator(generator);
            json.writeStartObject()
                .writeNullable("version", info.version())
                .writeNullable("environment", info.environment())
                .writeNullable("name", info.name())
                .writeNullable("commit", info.commit());
            if (info.nodeName() != null) {
                json.writeObjectFieldStart("node")
                    .writeStringField("name", info.nodeName())
                    .writeEndObject();
            }
            json.writeEndObject();
        }
        return new SerializedString(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * <p>@SuppressWarnings: java:S2093 — внешний try с ресурсом для пула</p>
     *
     * <p>Внутри JsonGenerator закрывается try-with-resources, а ByteArrayBuilder и
     *  * recycler — вручную в finally для возврата в буферный пул</p>
     */
    @SuppressWarnings("java:S2093")
    @SneakyThrows
    public byte[] formatAsBytes(ILoggingEvent event) {
        String formattedMessage = event.getFormattedMessage();

        // ByteArrayBuilder (не synchronized, сегменты из BufferRecycler) вместо ByteArrayOutputStream.
        // Финальную копию в byte[] убрать нельзя — контракт Encoder.encode() требует массив.
        // Начальный размер не задаём: первый блок приходит из recycler'а и самонастраивается под
        // трафик — release() возвращает в пул последний (самый крупный) блок события.
        BufferRecycler recycler = RECYCLER_POOL.acquireAndLinkPooled();
        ByteArrayBuilder stream = new ByteArrayBuilder(recycler);
        try {
            try (JsonGenerator jsonGenerator =
                     JSON_FACTORY.createGenerator(ObjectWriteContext.empty(), stream, JsonEncoding.UTF8)) {
                SimpleJsonGenerator json = new SimpleJsonGenerator(jsonGenerator);
                //@formatter:off
                json.writeStartObject()
                    .writeNullable(N_TIMESTAMP, event.getInstant())
                    .writeNullable(N_LEVEL, event.getLevel())
                    .writeNullable(N_LOGGER, event.getLoggerName());
                json.writeObjectFieldStart(N_PROCESS);
                // pid опционален: null — поле пропускается, а не роняет логирование NPE при unbox'инге.
                if (pid != null) {
                    json.writeNumberField(N_PID, pid);
                }
                json.writeObjectFieldStart(N_THREAD)
                        .writeNullable(N_NAME, event.getThreadName())
                        .writeEndObject()
                    .writeEndObject();
                // Весь service-блок константен за жизнь процесса — пишем из готовых фрагментов
                // (servicescFragment != null ⇔ !serviceInfo.isEmpty()).
                if (servicescFragment != null) {
                    writeServiceFields(json);
                }
                // Анти-log-forging (инвариант всего проекта): message, MDC и KV — единственные
                // поля с внешними данными, и все три пишутся через Jackson writeString/writeName,
                // который экранирует CR/LF и кавычки. Событие = ровно одна строка JSON, поэтому
                // подставить в лог лишнюю запись или лишнее поле через \r\n в значении нельзя.
                // Санитайзить значения на местах вызова log.*() не нужно (и вредно — потеряется
                // исходное значение). Проверяется FormatterElasticTest, раздел «Log forging».
                json.writeStringField(N_MESSAGE, formattedMessage);
                json.writeNumberField(N_SEQUENCE_NUMBER, event.getSequenceNumber());

                if (!event.getMDCPropertyMap().isEmpty()) {
                    for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
                          json.writeStringField(entry.getKey(), entry.getValue());
                    }
                }
                if (!isNullOrEmpty(event.getKeyValuePairs())) {
                  writeKeyValuePairs(json, event.getKeyValuePairs());
                }
                if (event.getThrowableProxy() != null) {
                    json.writeObjectFieldStart(N_ERROR)
                        .writeStringField(N_TYPE, event.getThrowableProxy().getClassName())
                        .writeStringField(N_MESSAGE, event.getThrowableProxy().getMessage())
                        .writeStringField(N_STACK_TRACE, stackTrace(event))
                        .writeEndObject();
                }

                if (!isNullOrEmpty(event.getMarkerList())) {
                    json.writeArrayFieldStart(N_TAGS);
                    for (String marker : getMarkers(event.getMarkerList())) {
                        json.writeString(marker);
                    }
                    json.writeEndArray();
                }
                json.writeRawValueField(N_ECS, V_ECS);
                json.writeEndObject();
                //@formatter:on
            }
            stream.write(ENDEOF);
            return stream.toByteArray();
        } finally {
            // Возвращаем сегменты буфера в recycler, затем recycler — в пул для следующего события.
            stream.release();
            recycler.releaseToPool();
        }
    }

    /**
     * Пишет KV-пары прямо в JSON-генератор, без промежуточной {@code HashMap} (горячий путь —
     * каждое лог-событие, а Logbook-логи несут ~10-15 пар вроде {@code header.*}). Dotted-ключи
     * ({@code header.Authorization}) разворачиваются во вложенные объекты. Пары сортируются по
     * ключу: одинаковые префиксы становятся соседними, поэтому вложенные объекты пишутся потоково
     * (JSON требует непрерывности объекта) — открываем/закрываем по мере смены префикса.
     */
    static void writeKeyValuePairs(SimpleJsonGenerator json, List<KeyValuePair> keyValuePairs) {
        KeyValuePair[] sorted = keyValuePairs.toArray(new KeyValuePair[0]);
        Arrays.sort(sorted, KEY_COMPARATOR);
        String[] openPath = EMPTY_PATH;
        int openDepth = 0;
        for (KeyValuePair pair : sorted) {
            String[] segments = splitKey(pair.key);
            int depth = segments.length - 1; // число объектов-обёрток до листа
            int common = 0;
            while (common < openDepth && common < depth && openPath[common].equals(segments[common])) {
                common++;
            }
            for (int i = openDepth; i > common; i--) {
                json.writeEndObject();
            }
            for (int i = common; i < depth; i++) {
                json.writeObjectFieldStart(segments[i]);
            }
            json.writeStringField(segments[depth], DisplayStrings.of(pair.value));
            openPath = segments;
            openDepth = depth;
        }
        for (int i = 0; i < openDepth; i++) {
            json.writeEndObject();
        }
    }

    private void writeServiceFields(SimpleJsonGenerator json) {
        if (systemValue != null) {
            json.writeStringField(N_SYSTEM, systemValue);
        }
        if (serviceValue != null) {
            json.writeStringField(N_SERVICE, serviceValue);
        }
        if (reporterValue != null) {
            json.writeStringField(N_REPORTER, reporterValue);
        }
        json.writeRawValueField(N_SERVICESC, servicescFragment);
    }

    private static String[] splitKey(String key) {
        String[] segments = KEY_SEGMENTS.get(key);
        if (segments != null) {
            return segments;
        }
        segments = StringUtils.split(key, ".");
        if (KEY_SEGMENTS.size() < KEY_SEGMENTS_MAX) {
            KEY_SEGMENTS.put(key, segments);
        }
        return segments;
    }

    private static Set<String> getMarkers(List<Marker> markers) {
        Set<String> result = new TreeSet<>();
        addMarkers(result, markers.iterator());
        return result;
    }

    private static void addMarkers(Set<String> result, Iterator<Marker> iterator) {
        while (iterator.hasNext()) {
            Marker marker = iterator.next();
            result.add(marker.getName());
            if (marker.hasReferences()) {
                addMarkers(result, marker.iterator());
            }
        }
    }

    private String stackTrace(ILoggingEvent event) {
        return stackTraceRenderer.apply(event);
    }

    private static boolean isNullOrEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }
}
