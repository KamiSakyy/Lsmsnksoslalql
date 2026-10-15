package com.tsuyu.messenger.ui.controllers;

import android.view.View;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.tsuyu.messenger.R;

/**
 * Контроллер экрана настроек (screen_settings).
 */
public final class SettingsController {

    public interface SettingsEventListener {
        void onBackClicked();
        void onProfileCardClicked();
        void onWallpaperClicked();
        void onCustomizationClicked();
        void onScreenGuardChanged(boolean enabled);
        void onPrivacyClicked();
        void onNotificationsClicked();
        void onKeysClicked();
        void onBackgroundSyncChanged(boolean enabled);
        void onDynamicColorsChanged(boolean enabled);
        void onLogsClicked();
        void onLogoutClicked();
    }

    private final View root;
    private final SettingsEventListener listener;

    private Toolbar toolbar;
    private View profileCard;
    private TextView avatarInitial;
    private ShapeableImageView avatarImage;
    private TextView profileNameText;
    private TextView profileHandleText;
    private View wallpaperRow;
    private View customizationRow;
    private MaterialSwitch screenGuardSwitch;
    private View privacyRow;
    private View notificationsRow;
    private View keysRow;
    private MaterialSwitch backgroundSyncSwitch;
    private MaterialSwitch dynamicColorsSwitch;
    private View logsRow;
    private View logoutButton;
    private TextView versionText;

    public SettingsController(View root, SettingsEventListener listener) {
        this.root = root;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        toolbar = root.findViewById(R.id.settings_toolbar);
        profileCard = root.findViewById(R.id.settings_profile_card);
        avatarInitial = root.findViewById(R.id.settings_avatar_initial);
        avatarImage = root.findViewById(R.id.settings_avatar);
        profileNameText = root.findViewById(R.id.settings_profile_name);
        profileHandleText = root.findViewById(R.id.settings_profile_handle);
        wallpaperRow = root.findViewById(R.id.settings_wallpaper);
        customizationRow = root.findViewById(R.id.setting_customization);
        screenGuardSwitch = root.findViewById(R.id.screen_guard_switch);
        privacyRow = root.findViewById(R.id.setting_privacy);
        notificationsRow = root.findViewById(R.id.setting_notifications);
        keysRow = root.findViewById(R.id.setting_keys);
        backgroundSyncSwitch = root.findViewById(R.id.background_sync_switch);
        dynamicColorsSwitch = root.findViewById(R.id.dynamic_colors_switch);
        logsRow = root.findViewById(R.id.settings_logs);
        logoutButton = root.findViewById(R.id.settings_logout);
        versionText = root.findViewById(R.id.settings_version);

        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> {
                if (listener != null) listener.onBackClicked();
            });
        }
        if (profileCard != null) {
            profileCard.setOnClickListener(v -> {
                if (listener != null) listener.onProfileCardClicked();
            });
        }
        if (wallpaperRow != null) {
            wallpaperRow.setOnClickListener(v -> {
                if (listener != null) listener.onWallpaperClicked();
            });
        }
        if (customizationRow != null) {
            customizationRow.setOnClickListener(v -> {
                if (listener != null) listener.onCustomizationClicked();
            });
        }
        if (screenGuardSwitch != null) {
            screenGuardSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                if (listener != null) listener.onScreenGuardChanged(isChecked);
            });
        }
        if (privacyRow != null) {
            privacyRow.setOnClickListener(v -> {
                if (listener != null) listener.onPrivacyClicked();
            });
        }
        if (notificationsRow != null) {
            notificationsRow.setOnClickListener(v -> {
                if (listener != null) listener.onNotificationsClicked();
            });
        }
        if (keysRow != null) {
            keysRow.setOnClickListener(v -> {
                if (listener != null) listener.onKeysClicked();
            });
        }
        if (backgroundSyncSwitch != null) {
            backgroundSyncSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                if (listener != null) listener.onBackgroundSyncChanged(isChecked);
            });
        }
        if (dynamicColorsSwitch != null) {
            dynamicColorsSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                if (listener != null) listener.onDynamicColorsChanged(isChecked);
            });
        }
        if (logsRow != null) {
            logsRow.setOnClickListener(v -> {
                if (listener != null) listener.onLogsClicked();
            });
        }
        if (logoutButton != null) {
            logoutButton.setOnClickListener(v -> {
                if (listener != null) listener.onLogoutClicked();
            });
        }
    }

    public void setProfile(String name, String handle, String initial) {
        if (profileNameText != null) profileNameText.setText(name);
        if (profileHandleText != null) profileHandleText.setText(handle);
        if (avatarInitial != null) avatarInitial.setText(initial);
    }

    public void setSwitches(boolean screenGuard, boolean bgSync, boolean dynamicColors) {
        if (screenGuardSwitch != null) screenGuardSwitch.setChecked(screenGuard);
        if (backgroundSyncSwitch != null) backgroundSyncSwitch.setChecked(bgSync);
        if (dynamicColorsSwitch != null) dynamicColorsSwitch.setChecked(dynamicColors);
    }

    public void setVersionText(String text) {
        if (versionText != null) versionText.setText(text);
    }

    public void setMobilePriorityStatusText(String text) {
        if (mobilePriorityStatus != null) mobilePriorityStatus.setText(text);
    }
}
