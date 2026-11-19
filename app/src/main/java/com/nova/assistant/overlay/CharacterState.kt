package com.nova.assistant.overlay

/** Состояния персонажа — от них зависит анимация. */
enum class CharacterState {
    IDLE,        // ожидание
    LISTENING,   // слушает
    THINKING,    // анализирует запрос
    ACTING,      // выполняет действие
    SEARCHING,   // ищет информацию
    READING,     // читает экран
    THINK_HARD,  // думает над сложной задачей
    ERROR,       // обнаружил ошибку
    DONE,        // задача завершена
    CONFIRM,     // требуется подтверждение
    HIDDEN       // скрыт (фоновый режим)
}
