package com.tsuyu.messenger.ui.controllers;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.tsuyu.Lsmsnksoslalql.R;

/**
 * Контроллер бокового меню (view_drawer).
 */
public final class DrawerController {

    public interface DrawerEventListener {
        void onAvatarEditClicked();
        void onProfileClicked();
        void onStealthChanged(boolean enabled);
        void onSoundChanged(boolean enabled);
        void onEncryptChanged(boolean enabled);
        void onPinClicked();
        void onClearCacheClicked();
        void onSettingsClicked();
        void onLogoutClicked();
    }

    private final DrawerLayout drawerLayout;
    private final View drawerView;
    private final DrawerEventListener listener;

    private TextView avatarInitial;
    private ShapeableImageView avatarImage;
    private TextView nameText;
    private TextView presenceText;
    private TextView statusText;
    private MaterialSwitch switchStealth;
    private MaterialSwitch switchSound;
    private MaterialSwitch switchEncrypt;
    private TextView cacheLabel;
    private TextView pinLabel;

    public DrawerController(DrawerLayout drawerLayout, View drawerView, DrawerEventListener listener) {
        this.drawerLayout = drawerLayout;
        this.drawerView = drawerView;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        avatarInitial = drawerView.findViewById(R.id.drawer_avatar_initial);
        avatarImage = drawerView.findViewById(R.id.drawer_avatar);
        nameText = drawerView.findViewById(R.id.drawer_name);
        presenceText = drawerView.findViewById(R.id.drawer_presence);
        statusText = drawerView.findViewById(R.id.drawer_status);
        switchStealth = drawerView.findViewById(R.id.drawer_switch_stealth);
        switchSound = drawerView.findViewById(R.id.drawer_switch_sound);
        switchEncrypt = drawerView.findViewById(R.id.drawer_switch_encrypt);
        cacheLabel = drawerView.findViewById(R.id.drawer_cache_label);
        pinLabel = drawerView.findViewById(R.id.drawer_pin_label);

        View avatarEdit = drawerView.findViewById(R.id.drawer_avatar_edit);
        if (avatarEdit != null) {
            avatarEdit.setOnClickListener(v -> {
                if (listener != null) listener.onAvatarEditClicked();
            });
        }

        View itemProfile = drawerView.findViewById(R.id.drawer_item_profile);
        if (itemProfile != null) {
            itemProfile.setOnClickListener(v -> {
                closeDrawer();
                if (listener != null) listener.onProfileClicked();
            });
        }

        if (switchStealth != null) {
            switchStealth.setOnCheckedChangeListener((btn, isChecked) -> {
                if (listener != null) listener.onStealthChanged(isChecked);
            });
        }
        if (switchSound != null) {
            switchSound.setOnCheckedChangeListener((btn, isChecked) -> {
                if (listener != null) listener.onSoundChanged(isChecked);
            });
        }
        if (switchEncrypt != null) {
            switchEncrypt.setOnCheckedChangeListener((btn, isChecked) -> {
                if (listener != null) listener.onEncryptChanged(isChecked);
            });
        }

        View itemPin = drawerView.findViewById(R.id.drawer_item_pin);
        if (itemPin != null) {
            itemPin.setOnClickListener(v -> {
                if (listener != null) listener.onPinClicked();
            });
        }

        View itemCache = drawerView.findViewById(R.id.drawer_item_cache);
        if (itemCache != null) {
            itemCache.setOnClickListener(v -> {
                if (listener != null) listener.onClearCacheClicked();
            });
        }

        View itemSettings = drawerView.findViewById(R.id.drawer_item_settings);
        if (itemSettings != null) {
            itemSettings.setOnClickListener(v -> {
                closeDrawer();
                if (listener != null) listener.onSettingsClicked();
            });
        }

        View itemLogout = drawerView.findViewById(R.id.drawer_item_logout);
        if (itemLogout != null) {
            itemLogout.setOnClickListener(v -> {
                closeDrawer();
                if (listener != null) listener.onLogoutClicked();
            });
        }
    }

    public void setProfile(String name, String status, String initial) {
        if (nameText != null) nameText.setText(name);
        if (statusText != null && status != null) statusText.setText(status);
        if (avatarInitial != null) avatarInitial.setText(initial);
    }

    public void setSwitches(boolean stealth, boolean sound, boolean encrypt) {
        if (switchStealth != null) switchStealth.setChecked(stealth);
        if (switchSound != null) switchSound.setChecked(sound);
        if (switchEncrypt != null) switchEncrypt.setChecked(encrypt);
    }

    public void setCacheSizeText(String text) {
        if (cacheLabel != null) cacheLabel.setText(text);
    }

    public void setPinLabelText(String text) {
        if (pinLabel != null) pinLabel.setText(text);
    }

    public void openDrawer() {
        if (drawerLayout != null) drawerLayout.openDrawer(drawerView);
    }

    public void closeDrawer() {
        if (drawerLayout != null) drawerLayout.closeDrawer(drawerView);
    }

    public boolean isDrawerOpen() {
        return drawerLayout != null && drawerLayout.isDrawerOpen(drawerView);
    }
}
