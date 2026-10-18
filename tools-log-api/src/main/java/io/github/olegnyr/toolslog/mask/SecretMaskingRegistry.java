package io.github.olegnyr.toolslog.mask;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request-scoped реестр точных секрет→маска для маскировки в логах. Продьюсеры, у которых на руках
 * настоящее значение секрета (выданный токен при регистрации/refresh, выдернутый из тела PAN),
 * регистрируют пару; логгер/энкодер при записи зовёт {@link #maskAll(String)} и литерально заменяет
 * известные значения в любом поле. Так опаковый токен (который нельзя надёжно поймать regex'ом без
 * ложных срабатываний) маскируется по точному значению.
 *
 * <p>Держит состояние в {@link ThreadLocal}; через async/reactive-границу пробрасывается
 * {@code MaskingThreadLocalAccessor}'ом (Micrometer context-propagation).
 *
 * <p><b>Инвариант владельца потока.</b> Кто открыл реестр на потоке, тот обязан закрыть его
 * в {@code finally} — иначе запись переживёт запрос на пул-потоке контейнера. Точки закрытия:
 * <ul>
 *   <li>HTTP — {@code SecretMaskingFilter} ({@code finally} вокруг всей цепочки, зарегистрирован
 *       на диспетчи REQUEST и ASYNC, поэтому исключение и async re-dispatch тоже закрывают);</li>
 *   <li>Kafka — {@code LogbookKafkaRecordInterceptor} ({@code finally} вокруг записи);</li>
 *   <li>reactive-граница — {@code MaskingThreadLocalAccessor#setValue()} на восстановлении
 *       предыдущего состояния;</li>
 *   <li>ответ, уезжающий на другой поток — {@link #snapshotAndClear()}: реестр закрывается
 *       здесь же, а «контекст замены» едет дальше данными.</li>
 * </ul>
 * Утечка записи не раскрывает секрет (реестр умеет только прятать значения), но копится на
 * пул-потоке и удорожает каждую строку лога, поэтому закрытие обязательно.
 */
public final class SecretMaskingRegistry {

    private static final ThreadLocal<Map<String, String>> HOLDER = new ThreadLocal<>();

    private SecretMaskingRegistry() {
    }

    /** Регистрирует точную замену {@code secret} → {@code mask} для текущего запроса. */
    public static void register(String secret, String mask) {
        if (secret == null || secret.isEmpty()) {
            return;
        }
        Map<String, String> secrets = HOLDER.get();
        if (secrets == null) {
            secrets = new LinkedHashMap<>();
            HOLDER.set(secrets);
        }
        secrets.put(secret, mask);
    }

    /**
     * Регистрирует замену {@code secret} → результат маски для текущего запроса:
     * {@code register(phone, Masks.keepLast(2))}. Пустой секрет пропускается, маска для него не
     * вызывается.
     *
     * @param secret значение секрета
     * @param mask   маска, например из {@link Masks}
     */
    public static void register(String secret, SecretMask mask) {
        if (secret == null || secret.isEmpty()) {
            return;
        }
        register(secret, mask.apply(secret));
    }

    /** Литерально заменяет все зарегистрированные секреты в тексте. No-op при пустом реестре/null. */
    public static String maskAll(String text) {
        if (text == null) {
            return null;
        }
        Map<String, String> secrets = HOLDER.get();
        if (secrets == null || secrets.isEmpty()) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> secret : secrets.entrySet()) {
            result = result.replace(secret.getKey(), secret.getValue());
        }
        return result;
    }

    /** Снимает реестр текущего потока. Звать в finally внешнего фильтра. */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * Возвращает копию накопленных секретов текущего потока и очищает реестр. Используется, чтобы
     * вынести «контекст замены» из ThreadLocal в данные (например, асинхронный ответ), когда дальше будет смена
     * потока (async re-dispatch), через которую ThreadLocal не пробрасывается.
     */
    public static Map<String, String> snapshotAndClear() {
        Map<String, String> current = HOLDER.get();
        HOLDER.remove();
        return current == null || current.isEmpty() ? Map.of() : Map.copyOf(current);
    }

    /** Для context-propagation accessor'а: текущее состояние (может быть null). */
    public static Map<String, String> getContext() {
        return HOLDER.get();
    }

    /** Для context-propagation accessor'а: восстановить состояние на другом потоке. */
    public static void setContext(Map<String, String> context) {
        if (context == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(context);
        }
    }
}
