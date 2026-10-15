package com.tsuyu.messenger.ui.controllers;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import com.tsuyu.messenger.R;

/**
 * Контроллер экрана глобального поиска пользователей (screen_search).
 */
public final class SearchController {

    public interface SearchEventListener {
        void onBackClicked();
        void onQueryChanged(String query);
    }

    private final View root;
    private final SearchEventListener listener;

    private Toolbar toolbar;
    private EditText searchInput;
    private RecyclerView resultsRecyclerView;
    private View emptyState;

    public SearchController(View root, SearchEventListener listener) {
        this.root = root;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        toolbar = root.findViewById(R.id.search_toolbar);
        searchInput = root.findViewById(R.id.search_input);
        resultsRecyclerView = root.findViewById(R.id.search_results);
        emptyState = root.findViewById(R.id.search_empty_state);

        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> {
                if (listener != null) listener.onBackClicked();
            });
        }

        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (listener != null) {
                        listener.onQueryChanged(s != null ? s.toString().trim() : "");
                    }
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }
    }

    public void showEmpty(boolean show) {
        if (emptyState != null) emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public RecyclerView getRecyclerView() {
        return resultsRecyclerView;
    }
}
