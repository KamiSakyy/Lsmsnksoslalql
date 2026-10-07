package com.meowmessenger.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String CHANNEL_ID = "meow_messages";

    private TextView tokenValue;
    private TextView statusValue;
    private EditText recipientTokenInput;
    private EditText messageInput;
    private Button sendButton;
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
            setStatus("Не найдена Firebase-конфигурация. Добавьте app/google-services.json.");
            sendButton.setEnabled(false);
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

        TextView title = text("MeowMessenger", 26, true);
        content.addView(title, matchWrap());
        addSpace(content, 8);
        content.addView(text("Прототип отправляет обычный текст через FCM. Сквозного шифрования и истории сообщений нет.", 14, false), matchWrap());
        addSpace(content, 22);

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

        addSpace(content, 16);
        content.addView(text("Токен собеседника", 16, true), matchWrap());
        recipientTokenInput = new EditText(this);
        recipientTokenInput.setHint("Вставьте FCM-токен устройства собеседника");
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
        sendButton.setText("Отправить через FCM");
        sendButton.setOnClickListener(view -> sendMessage());
        content.addView(sendButton, matchWrap());

        statusValue = text("Подключение…", 14, false);
        content.addView(statusValue, matchWrap());
        addSpace(content, 10);
        content.addView(text("Для теста скопируйте токен получателя в приложение отправителя. Токен может измениться после переустановки приложения.", 13, false), matchWrap());

        setContentView(scrollView);
    }

    private void signInAnonymously() {
        setStatus("Подключаю анонимную Firebase-сессию…");
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            signedIn = true;
            setStatus("Готово. Можно отправлять сообщения.");
            return;
        }

        auth.signInAnonymously().addOnCompleteListener(this, task -> {
            if (task.isSuccessful()) {
                signedIn = true;
                setStatus("Готово. Можно отправлять сообщения.");
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

    private void sendMessage() {
        String recipientToken = recipientTokenInput.getText().toString().trim();
        String message = messageInput.getText().toString().trim();
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
        if (!signedIn || FirebaseAuth.getInstance().getCurrentUser() == null) {
            setStatus("Ожидаю Firebase-вход. Проверьте, что Anonymous sign-in включён.");
            return;
        }

        sendButton.setEnabled(false);
        setStatus("Отправляю запрос…");
        Map<String, Object> payload = new HashMap<>();
        payload.put("recipientToken", recipientToken);
        payload.put("text", message);

        FirebaseFunctions.getInstance("europe-west1")
                .getHttpsCallable("sendMessage")
                .call(payload)
                .addOnSuccessListener(this, result -> {
                    sendButton.setEnabled(true);
                    messageInput.setText("");
                    setStatus("FCM принял сообщение для отправки.");
                })
                .addOnFailureListener(this, error -> {
                    sendButton.setEnabled(true);
                    setStatus("Ошибка отправки: " + error.getLocalizedMessage());
                });
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
}
