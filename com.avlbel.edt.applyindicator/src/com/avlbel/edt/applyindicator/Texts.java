package com.avlbel.edt.applyindicator;

/**
 * Строки интерфейса. Лежат в исходнике, а не в properties: NLS читает
 * properties как ISO-8859-1 и портит кириллицу.
 */
final class Texts {

    static final String NO_PROJECT = "Нет текущего проекта";
    static final String CONNECTING = "Чтение состояния применения…";
    static final String NO_SERVICE = "Сервис приложений EDT недоступен";
    static final String READ_FAILED = "Не удалось прочитать приложения проекта «%s»";
    static final String NO_APPS = "В проекте «%s» нет приложений";
    static final String APPLIED = "Базы всех приложений проекта «%s» применены";
    static final String NOT_APPLIED = "Не применено: %d из %d. Проект «%s»";
    static final String UNKNOWN = "Состояние применения неизвестно: %d из %d. Проект «%s»";
    static final String UPDATING = " Идёт применение: %d.";
    static final String CLICK_HINT = "\nЛевый щелчок — список приложений.";
    static final String MENU_EMPTY = "Нет приложений";
    static final String JOB = "Подключение индикатора применения";

    private Texts() {
    }
}
