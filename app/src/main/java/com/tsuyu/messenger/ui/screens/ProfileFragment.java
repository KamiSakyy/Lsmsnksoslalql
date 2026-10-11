package com.tsuyu.messenger.ui.screens;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import com.tsuyu.messenger.R;

/** Экран «profile»: своя разметка и свой жизненный цикл вместо одного огромного класса. */
public final class ProfileFragment extends TsuyuScreen {

    @NonNull @Override public String pageName() { return "profile"; }

    @LayoutRes @Override protected int layoutResource() { return R.layout.screen_profile_editor; }
}
