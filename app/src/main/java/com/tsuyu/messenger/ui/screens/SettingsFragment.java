package com.tsuyu.messenger.ui.screens;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import com.tsuyu.messenger.R;

/** Экран «settings»: своя разметка и свой жизненный цикл вместо одного огромного класса. */
public final class SettingsFragment extends TsuyuScreen {

    @NonNull @Override public String pageName() { return "settings"; }

    @LayoutRes @Override protected int layoutResource() { return R.layout.screen_settings; }
}
