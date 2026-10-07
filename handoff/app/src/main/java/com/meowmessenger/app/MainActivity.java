package com.meowmessenger.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String CHANNEL_ID = "meow_messages";
    private static final String PREFS = "meow_settings";
    private static final String PREF_RAILWAY_URL = "railway_url";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private TextView tokenValue;
    private TextView statusValue;
    private EditText railwayUrlInput;
    private EditText recipientTokenInput;
    private EditText messageInput;
    private Button sendButton;
    private Button checkServerButton;
    private boolean signedIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();
        buildScreen();

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }

        if (FirebaseApp.initializeApp(this) == null) {
            setStatus("Не найдена Firebase-конфигурация.");
            sendButton.setEnabled(false);
            checkServerButton.setEnabled(false);
            return;
        }

        signInAnonymously();
        refreshFcmToken();
    }

    private void buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(28));
        content.setBackgroundColor(Color.rgb(250, 249, 252));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(text("MeowMessenger", 26, true), matchWrap());
        addSpace(content, 8);
        content.addView(text("Android → Railway HTTPS → FCM. Текст без сквозного шифрования; истории нет.", 14, false), matchWrap());

        addSpace(content, 18);
        content.addView(text("Адрес Railway API", 16, true), matchWrap());
        railwayUrlInput = new EditText(this);
        railwayUrlInput.setSingleLine(true);
        railwayUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        railwayUrlInput.setHint("https://ваш-сервис.up.railway.app");
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        railwayUrlInput.setText(preferences.getString(PREF_RAILWAY_URL, ""));
        content.addView(railwayUrlInput, matchWrap());

        Button saveUrlButton = new Button(this);
        saveUrlButton.setText("Сохранить адрес Railway");
        saveUrlButton.setOnClickListener(view -> saveRailwayUrl());
        content.addView(saveUrlButton, matchWrap());

        checkServerButton = new Button(this);
        checkServerButton.setText("Проверить подключение к Railway");
        checkServerButton.setOnClickListener(view -> checkRailwayServer());
        content.addView(checkServerButton, matchWrap());

        addSpace(content, 14);
        content.addView(text("Ваш FCM-токен", 16, true), matchWrap());
        addSpace(content, 6);
        tokenValue = text("Получаю токен…", 12, false);
        tokenValue.setTextIsSelectable(true);
        content.addView(tokenValue, matchWrap());

        Button copyButton = new Button(this);
        copyButton.setText("Скопировать мой токен");
        copyButton.setOnClickListener(view -> copyToken());
        content.addView(copyButton, matchWrap());

        Button refreshButton = new Button(this);
        refreshButton.setText("Обновить токен");
        refreshButton.setOnClickListener(view -> refreshFcmToken());
        content.addView(refreshButton, matchWrap());

        addSpace(content, 14);
        content.addView(text("Токен собеседника", 16, true), matchWrap());
        recipientTokenInput = new EditText(this);
        recipientTokenInput.setHint("Вставьте FCM-токен получателя");
        recipientTokenInput.setSingleLine(true);
        recipientTokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        content.addView(recipientTokenInput, matchWrap());

        addSpace(content, 10);
        content.addView(text("Сообщение", 16, true), matchWrap());
        messageInput = new EditText(this);
        messageInput.setHint("Введите текст сообщения");
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        messageInput.setMinLines(3);
        messageInput.setMaxLines(6);
        messageInput.setText("Привет!");
        content.addView(messageInput, matchWrap());

        sendButton = new Button(this);
        sendButton.setText("Отправить: Railway → FCM");
        sendButton.setOnClickListener(view -> sendMessage());
        content.addView(sendButton, matchWrap());

        statusValue = text("Ожидание подключения…", 14, false);
        content.addView(statusValue, matchWrap());
        addSpace(content, 10);
        content.addView(text("Для теста на одном телефоне скопируйте свой токен и вставьте его как токен получателя. Для отправки нужен доступ к адресу Railway.", 13, false), matchWrap());

        setContentView(scrollView);
    }

    private void signInAnonymously() {
        setStatus("Подключаю Firebase Auth…");
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            signedIn = true;
            setStatus("Firebase Auth готов. Укажите Railway URL.");
            return;
        }

        auth.signInAnonymously().addOnCompleteListener(this, task -> {
            if (task.isSuccessful()) {
                signedIn = true;
                setStatus("Firebase Auth готов. Укажите Railway URL.");
            } else {
                signedIn = false;
                setStatus("Не удалось войти. В Firebase Console включите Anonymous sign-in.");
            }
        });
    }

    private void refreshFcmToken() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            return;
        }
        tokenValue.setText("Получаю токен…");
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(this, token -> tokenValue.setText(token))
                .addOnFailureListener(this, error -> tokenValue.setText("Ошибка получения FCM-токена: " + error.getLocalizedMessage()));
    }

    private void copyToken() {
        String token = tokenValue.getText().toString();
        if (token.isEmpty() || token.startsWith("Получаю") || token.startsWith("Ошибка")) {
            setStatus("Сначала дождитесь получения FCM-токена.");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MeowMessenger FCM token", token));
            setStatus("Токен скопирован.");
        }
    }

    private void saveRailwayUrl() {
        String url = normalizeUrl(railwayUrlInput.getText().toString());
        if (!isHttpsUrl(url)) {
            railwayUrlInput.setError("Введите HTTPS-адрес сервиса Railway");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_RAILWAY_URL, url).apply();
        railwayUrlInput.setText(url);
        setStatus("Адрес Railway сохранён.");
    }

    private void checkRailwayServer() {
        String baseUrl = normalizeUrl(railwayUrlInput.getText().toString());
        if (!isHttpsUrl(baseUrl)) {
            railwayUrlInput.setError("Введите HTTPS-адрес сервиса Railway");
            return;
        }
        checkServerButton.setEnabled(false);
        setStatus("Проверяю Railway…");
        networkExecutor.execute(() -> {
            try {
                String response = RailwayApiClient.checkHealth(baseUrl);
                runOnUiThread(() -> {
                    checkServerButton.setEnabled(true);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_RAILWAY_URL, baseUrl).apply();
                    setStatus("Railway отвечает: " + response);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    checkServerButton.setEnabled(true);
                    setStatus("Railway недоступен: " + error.getMessage());
                });
            }
        });
    }

    private void sendMessage() {
        String baseUrl = normalizeUrl(railwayUrlInput.getText().toString());
        String recipientToken = recipientTokenInput.getText().toString().trim();
        String message = messageInput.getText().toString().trim();

        if (!isHttpsUrl(baseUrl)) {
            railwayUrlInput.setError("Сначала укажите HTTPS-адрес Railway");
            return;
        }
        if (recipientToken.isEmpty()) {
            recipientTokenInput.setError("Нужен FCM-токен получателя");
            return;
        }
        if (message.isEmpty()) {
            messageInput.setError("Введите текст сообщения");
            return;
        }
        if (message.length() > 2000) {
            messageInput.setError("Максимум 2000 символов");
            return;
        }
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (!signedIn || user == null) {
            setStatus("Ожидаю Firebase Auth. Проверьте Anonymous sign-in.");
            return;
        }

        sendButton.setEnabled(false);
        setStatus("Получаю токен авторизации Firebase…");
        user.getIdToken(false)
                .addOnSuccessListener(this, tokenResult -> {
                    String idToken = tokenResult.getToken();
                    if (idToken == null || idToken.isEmpty()) {
                        sendButton.setEnabled(true);
                        setStatus("Firebase не выдал токен авторизации.");
                        return;
                    }
                    setStatus("Отправляю запрос на Railway…");
                    networkExecutor.execute(() -> {
                        try {
                            String response = RailwayApiClient.sendMessage(baseUrl, idToken, recipientToken, message);
                            runOnUiThread(() -> {
                                sendButton.setEnabled(true);
                                messageInput.setText("");
                                setStatus("Railway передал сообщение в FCM: " + response);
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                sendButton.setEnabled(true);
                                setStatus("Ошибка отправки: " + error.getMessage());
                            });
                        }
                    });
                })
                .addOnFailureListener(this, error -> {
                    sendButton.setEnabled(true);
                    setStatus("Не удалось получить Firebase ID token: " + error.getLocalizedMessage());
                });
    }

    private boolean isHttpsUrl(String value) {
        return value != null && value.startsWith("https://") && value.length() > "https://".length();
    }

    private String normalizeUrl(String value) {
        String url = value == null ? "" : value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID,
                    "Сообщения MeowMessenger",
                    android.app.NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Входящие сообщения FCM");
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void setStatus(String message) {
        if (statusValue != null) {
            statusValue.setText(message);
        }
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(35, 32, 40));
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addSpace(LinearLayout layout, int heightDp) {
        android.view.View space = new android.view.View(this);
        layout.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
