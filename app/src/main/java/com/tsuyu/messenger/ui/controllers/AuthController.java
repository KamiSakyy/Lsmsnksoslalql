package com.tsuyu.messenger.ui.controllers;

import android.text.TextUtils;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.tsuyu.Lsmsnksoslalql.R;

/**
 * Контроллер экрана аутентификации (screen_auth).
 * Отвечает за состояние формы, переключение режимов (вход / регистрация),
 * валидацию ввода и передачу событий в активити.
 */
public final class AuthController {

    public interface AuthListener {
        void onPerformAuth(String email, String password, boolean isRegister);
    }

    private final View root;
    private final AuthListener listener;
    private boolean isRegisterMode = false;

    private EditText emailInput;
    private EditText passwordInput;
    private Button primaryButton;
    private Button switchModeButton;
    private TextView titleText;

    public AuthController(View root, AuthListener listener) {
        this.root = root;
        this.listener = listener;
        bindViews();
    }

    private void bindViews() {
        emailInput = root.findViewById(R.id.auth_email);
        passwordInput = root.findViewById(R.id.auth_password);
        primaryButton = root.findViewById(R.id.auth_primary);
        switchModeButton = root.findViewById(R.id.auth_switch_mode);
        titleText = root.findViewById(R.id.auth_title);

        if (primaryButton != null) {
            primaryButton.setOnClickListener(v -> submit());
        }
        if (switchModeButton != null) {
            switchModeButton.setOnClickListener(v -> toggleMode());
        }
    }

    private void toggleMode() {
        isRegisterMode = !isRegisterMode;
        if (titleText != null) {
            titleText.setText(R.string.auth_title);
        }
        if (primaryButton != null) {
            primaryButton.setText(isRegisterMode ? R.string.auth_register : R.string.auth_login);
        }
        if (switchModeButton != null) {
            switchModeButton.setText(isRegisterMode ? R.string.auth_have_account : R.string.auth_create_account);
        }
    }

    private void submit() {
        if (emailInput == null || passwordInput == null || listener == null) return;
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();

        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Некорректный email");
            emailInput.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(password) || password.length() < 6) {
            passwordInput.setError("Пароль не менее 6 символов");
            passwordInput.requestFocus();
            return;
        }
        listener.onPerformAuth(email, password, isRegisterMode);
    }

    public void setEnabled(boolean enabled) {
        if (primaryButton != null) primaryButton.setEnabled(enabled);
        if (switchModeButton != null) switchModeButton.setEnabled(enabled);
        if (emailInput != null) emailInput.setEnabled(enabled);
        if (passwordInput != null) passwordInput.setEnabled(enabled);
    }
}
