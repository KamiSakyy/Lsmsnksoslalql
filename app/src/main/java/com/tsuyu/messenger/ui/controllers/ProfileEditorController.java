package com.tsuyu.messenger.ui.controllers;

import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.imageview.ShapeableImageView;
import com.tsuyu.messenger.R;

/**
 * Контроллер экрана редактирования профиля (screen_profile_editor).
 */
public final class ProfileEditorController {

    public interface ProfileEditorListener {
        void onBackClicked();
        void onSaveClicked(String displayName, String username, String about);
        void onChangeAvatarClicked();
    }

    private final View root;
    private final ProfileEditorListener listener;

    private Toolbar toolbar;
    private View avatarFrame;
    private TextView avatarInitial;
    private ShapeableImageView avatarImage;
    private EditText nameInput;
    private EditText usernameInput;
    private EditText aboutInput;
    private View saveButton;

    public ProfileEditorController(View root, ProfileEditorListener listener) {
        this.root = root;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        toolbar = root.findViewById(R.id.profile_toolbar);
        avatarFrame = root.findViewById(R.id.profile_avatar_container);
        avatarInitial = root.findViewById(R.id.profile_avatar_initial);
        avatarImage = root.findViewById(R.id.profile_avatar);
        nameInput = root.findViewById(R.id.profile_name);
        usernameInput = root.findViewById(R.id.profile_username);
        aboutInput = root.findViewById(R.id.profile_about);
        saveButton = root.findViewById(R.id.profile_save);

        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> {
                if (listener != null) listener.onBackClicked();
            });
        }
        if (avatarFrame != null) {
            avatarFrame.setOnClickListener(v -> {
                if (listener != null) listener.onChangeAvatarClicked();
            });
        }
        if (saveButton != null) {
            saveButton.setOnClickListener(v -> {
                if (listener != null) {
                    String name = nameInput != null ? nameInput.getText().toString().trim() : "";
                    String username = usernameInput != null ? usernameInput.getText().toString().trim() : "";
                    String about = aboutInput != null ? aboutInput.getText().toString().trim() : "";
                    listener.onSaveClicked(name, username, about);
                }
            });
        }
    }

    public void setProfile(String name, String username, String about, String initial) {
        if (nameInput != null) nameInput.setText(name);
        if (usernameInput != null) usernameInput.setText(username);
        if (aboutInput != null) aboutInput.setText(about);
        if (avatarInitial != null) avatarInitial.setText(initial);
    }
}
