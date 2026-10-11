package com.tsuyu.messenger.ui.screens;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import com.tsuyu.messenger.MainActivity;
import com.tsuyu.messenger.R;

/**
 * Список чатов. Частые подписки («в сети», «печатает») держатся только пока список на экране:
 * ушёл экран — подписки снимаются, и телефон не тратит сеть и батарею зря.
 */
public final class HomeFragment extends TsuyuScreen {

    @NonNull @Override public String pageName() { return "home"; }

    @LayoutRes @Override protected int layoutResource() { return R.layout.screen_home; }

    @Override
    public void onScreenShown(@NonNull android.view.View root) {
        MainActivity host = host();
        if (host != null) host.onHomeScreenShown();
    }

    @Override
    public void onScreenHidden() {
        MainActivity host = host();
        if (host != null) host.onHomeScreenLeft();
    }
}
