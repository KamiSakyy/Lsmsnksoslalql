package com.tsuyu.messenger.ui.viewmodel;

import androidx.lifecycle.ViewModel;

import com.tsuyu.messenger.data.ChatSummary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние списка чатов.
 *
 * <p>Раньше это всё лежало полями в MainActivity и терялось при пересоздании экрана.
 * Теперь данные списка живут отдельно от разметки: они переживают пересоздание и не
 * мешаются с кодом отрисовки.
 */
public final class HomeViewModel extends ViewModel {

    /** Строки списка: ключ — номер чата. */
    public final Map<String, ChatSummary> summaries = new ConcurrentHashMap<>();
    /** Папки чатов и их состав. */
    public final Map<String, String> folderNames = new LinkedHashMap<>();
    public final Map<String, Set<String>> folderMembers = new LinkedHashMap<>();
    /** Когда по чату последний раз спрашивали «в сети» и «печатает». */
    public final ConcurrentHashMap<String, Long> presenceCheckedAt = new ConcurrentHashMap<>();
    /** Какие строки сейчас догружаются: лишние повторы не нужны. */
    public final Set<String> loadingSummaryIds = ConcurrentHashMap.newKeySet();
}
