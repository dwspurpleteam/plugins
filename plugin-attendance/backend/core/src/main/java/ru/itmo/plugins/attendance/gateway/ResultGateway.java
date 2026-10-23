package ru.itmo.plugins.attendance.gateway;

/**
 * Result Gateway платформы: принимает результаты backend плагина и доставляет их участникам звонка.
 *
 * <p>Приём и доставка разделены: {@link #send} сообщает только, принято ли сообщение, а о доставке
 * Result Gateway сообщает позже запросом {@code POST /platform/deliveries}.
 */
public interface ResultGateway {

    /** Куда отправляются сообщения; показывается на странице разработчика. */
    String describe();

    /** Отправляет сообщение и ждёт ответа о приёме. Ошибки сети и таймауты возвращаются как {@link SendResult.Failed}. */
    SendResult send(ResultMessage<?> message);

    sealed interface SendResult {

        /** Сообщение принято, доставка асинхронная. */
        record Accepted() implements SendResult {
        }

        /** Сообщение отклонено: повтор того же сообщения не поможет. */
        record Rejected(String reason) implements SendResult {
        }

        /** Result Gateway недоступен или ответил ошибкой: сообщение могло и не дойти. */
        record Failed(String reason) implements SendResult {
        }
    }
}
