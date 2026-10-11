package com.tsuyu.messenger.ui.viewmodel;

import androidx.lifecycle.ViewModel;

import com.tsuyu.messenger.data.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние открытой переписки: готовые тексты сообщений, отправляемые сообщения, реакции,
 * опросы и служебные наборы «что уже расшифровано».
 *
 * <p>Это состояние теперь живёт отдельно от экрана: пересоздание экрана его не теряет, а код
 * отрисовки не путается с данными.
 */
public final class ConversationViewModel extends ViewModel {

    /** Готовые тексты сообщений: читаются с диска один заходом и берутся отсюда. */
    public final ConcurrentHashMap<String, String> payloadIndex = new ConcurrentHashMap<>();
    /** Какие тексты сейчас дочитываются в фоне. */
    public final Set<String> payloadLoading = ConcurrentHashMap.newKeySet();
    /** Отправляемые сообщения, ещё не подтверждённые сервером. */
    public final Map<String, ChatMessage> optimisticMessages = new ConcurrentHashMap<>();
    /** Реакции и голоса опросов. */
    public final Map<String, Set<String>> messageReactions = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Integer>> pollVotes = new ConcurrentHashMap<>();
    /** Служебные наборы: что расшифровывается и что расшифровать не удалось. */
    public final Set<String> decrypting = ConcurrentHashMap.newKeySet();
    public final Set<String> decryptFailures = ConcurrentHashMap.newKeySet();

    /** Сообщения, показанные сейчас (переписка пересобирается из них). */
    public List<ChatMessage> lastRemoteMessages = new ArrayList<>();
    /** Копия сообщений с устройства, показанная до ответа сети. */
    public List<ChatMessage> cachedMessages = new ArrayList<>();
}
