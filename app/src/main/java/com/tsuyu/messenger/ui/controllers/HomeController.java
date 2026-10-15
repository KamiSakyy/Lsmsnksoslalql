package com.tsuyu.messenger.ui.controllers;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import androidx.recyclerview.widget.RecyclerView;
import com.tsuyu.Lsmsnksoslalql.R;

/**
 * Контроллер экрана со списком чатов (screen_home).
 * Управляет шапкой, поисковой строкой, состояниями пустого экрана и скелетонами.
 */
public final class HomeController {

    public interface HomeEventListener {
        void onOpenDrawer();
        void onOpenEncryption();
        void onSearchQueryChanged(String query);
        void onFindPeopleClicked();
        void onNewChatClicked();
    }

    private final View root;
    private final HomeEventListener listener;

    private View menuButton;
    private View encryptButton;
    private EditText searchInput;
    private RecyclerView chatsRecyclerView;
    private View loadingState;
    private View emptyState;
    private View findPeopleButton;
    private View newChatFab;
    private View offlineBanner;

    public HomeController(View root, HomeEventListener listener) {
        this.root = root;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        menuButton = root.findViewById(R.id.home_menu_btn);
        encryptButton = root.findViewById(R.id.home_encrypt_btn);
        searchInput = root.findViewById(R.id.home_search_input);
        chatsRecyclerView = root.findViewById(R.id.chats_list);
        loadingState = root.findViewById(R.id.home_loading_state);
        emptyState = root.findViewById(R.id.home_empty_state);
        findPeopleButton = root.findViewById(R.id.home_find_people);
        newChatFab = root.findViewById(R.id.home_new_chat);
        offlineBanner = root.findViewById(R.id.home_offline_banner);

        if (menuButton != null) {
            menuButton.setOnClickListener(v -> {
                if (listener != null) listener.onOpenDrawer();
            });
        }
        if (encryptButton != null) {
            encryptButton.setOnClickListener(v -> {
                if (listener != null) listener.onOpenEncryption();
            });
        }
        if (findPeopleButton != null) {
            findPeopleButton.setOnClickListener(v -> {
                if (listener != null) listener.onFindPeopleClicked();
            });
        }
        if (newChatFab != null) {
            newChatFab.setOnClickListener(v -> {
                if (listener != null) listener.onNewChatClicked();
            });
        }
        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (listener != null) {
                        listener.onSearchQueryChanged(s != null ? s.toString() : "");
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    public void showLoading(boolean show) {
        if (loadingState != null) loadingState.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void showEmpty(boolean show) {
        if (emptyState != null) emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void showOfflineBanner(boolean show) {
        if (offlineBanner != null) offlineBanner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public RecyclerView getChatsRecyclerView() {
        return chatsRecyclerView;
    }

    public String getSearchQuery() {
        return searchInput != null ? searchInput.getText().toString() : "";
    }
}
