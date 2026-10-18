package io.github.olegnyr.toolslog.context;

import io.github.olegnyr.toolslog.mask.SecretMaskingRegistry;
import io.micrometer.context.ThreadLocalAccessor;

import java.util.Map;

/**
 * Пробрасывает {@link SecretMaskingRegistry} через async/reactive-границу (Micrometer
 * context-propagation; для Reactor — {@code spring.reactor.context-propagation=auto}).
 * Без этого маскировка секретов на async-потоке (например, в логе асинхронного ответа) не сработала
 * бы — реестр живёт в ThreadLocal потока запроса. Регистрируется SPI-файлом
 * {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor} (как
 * {@link MdcThreadLocalAccessor}).
 */
public class MaskingThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    public static final String MASKING_KEY = "toolslog.secret-masking";

    @Override
    public Object key() {
        return MASKING_KEY;
    }

    @Override
    public Map<String, String> getValue() {
        return SecretMaskingRegistry.getContext();
    }

    @Override
    public void setValue(Map<String, String> value) {
        SecretMaskingRegistry.setContext(value);
    }

    @Override
    public void setValue() {
        SecretMaskingRegistry.clear();
    }
}
