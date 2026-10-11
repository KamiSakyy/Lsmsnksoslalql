package com.tsuyu.messenger.ui.screens;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import com.tsuyu.messenger.MainActivity;
import com.tsuyu.messenger.R;

/**
 * Экран переписки. Подписки на сообщения, реакции и «прочитано» живут ровно столько,
 * сколько открыт этот экран: ушёл экран — снялись и подписки.
 */
public final class ChatFragment extends TsuyuScreen {

    @NonNull @Override public String pageName() { return "chat"; }

    @LayoutRes @Override protected int layoutResource() { return R.layout.screen_chat; }

    @Override
    public void onScreenHidden() {
        MainActivity host = host();
        if (host != null) host.onChatScreenLeft();
    }
}
