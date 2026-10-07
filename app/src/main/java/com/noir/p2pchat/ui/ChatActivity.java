package com.noir.p2pchat.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.noir.p2pchat.AppKernel;
import com.noir.p2pchat.R;
import com.noir.p2pchat.core.MessageStore;
import com.noir.p2pchat.core.P2pEngine;
import com.noir.p2pchat.service.ChatConnectionService;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatActivity extends ComponentActivity implements P2pEngine.Listener {
    public static final String EXTRA_PEER_UID = "peer_uid";
    public static final String EXTRA_DRAFT_TEXT = "draft_text";
    private AppKernel app;
    private P2pEngine engine;
    private String peerUid;
    private LinearLayout root;
    private LinearLayout messagesContainer;
    private ScrollView messagesScroll;
    private EditText composer;
    private TextView connectionStatus;
    private TextView identityStatusIndicator;
    private TextView recordingButton;
    private ActivityResultLauncher<String> contentPicker;
    private ActivityResultLauncher<String> microphonePermission;
    private ActivityResultLauncher<Intent> videoCapture;
    private String nextAttachmentKind = "file";
    private Uri circleCaptureUri;
    private File circleCaptureFile;
    private MediaRecorder recorder;
    private File voiceFile;
    private boolean recording;
    private MediaPlayer mediaPlayer;
    private final ExecutorService previewExecutor = Executors.newFixedThreadPool(2);
    private final Set<String> previewImageMessageIds = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (AppKernel) getApplication();
        engine = app.p2p();
        peerUid = getIntent().getStringExtra(EXTRA_PEER_UID);
        if (peerUid == null || !peerUid.matches("[A-Za-z0-9_-]{8,128}")) {
            finish();
            return;
        }
        engine.addContact(peerUid);
        setUpSystemBars();
        contentPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                if (engine.getUid() == null) Toast.makeText(this, "Подождите, пока приложение подключится к Firebase", Toast.LENGTH_LONG).show();
                else engine.sendAttachment(peerUid, uri, nextAttachmentKind);
            }
        });
        microphonePermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) startVoiceRecording();
            else Toast.makeText(this, "Для записи голосового нужно разрешить микрофон", Toast.LENGTH_SHORT).show();
        });
        videoCapture = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && circleCaptureUri != null && circleCaptureFile != null
                    && circleCaptureFile.isFile() && circleCaptureFile.length() > 0) {
                engine.sendAttachment(peerUid, circleCaptureUri, "video_note");
            } else {
                Toast.makeText(this, "Запись видео отменена или камера не сохранила файл", Toast.LENGTH_SHORT).show();
            }
        });
        setContentView(buildScreen());
        String draft = getIntent().getStringExtra(EXTRA_DRAFT_TEXT);
        if (draft != null && !draft.isEmpty()) composer.setText(draft);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left + Ui.dp(this, 14), bars.top + Ui.dp(this, 4),
                    bars.right + Ui.dp(this, 14), Math.max(bars.bottom, ime.bottom) + Ui.dp(this, 5));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
        renderMessages(false);
        updateConnectionStatus();
        startConnectionService();
    }

    private void setUpSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightNavigationBars(false);
    }

    private LinearLayout buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 12));
        TextView back = Ui.text(this, "‹", 32, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.rounded(Ui.SURFACE_ALT, Ui.dp(this, 18), Ui.STROKE));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        TextView avatar = Ui.text(this, "N", 15, Ui.ACCENT);
        avatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.rounded(Color.rgb(39, 34, 60), Ui.dp(this, 22), Color.TRANSPARENT));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        avatarParams.leftMargin = Ui.dp(this, 12);
        header.addView(avatar, avatarParams);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(Ui.dp(this, 10), 0, 0, 0);
        TextView title = Ui.text(this, shortId(peerUid), 14, Ui.TEXT);
        title.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        connectionStatus = Ui.text(this, "Ищем P2P-соединение…", 10, Ui.MUTED);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
        stateParams.topMargin = Ui.dp(this, 4);
        titleBlock.addView(title);
        titleBlock.addView(connectionStatus, stateParams);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        identityStatusIndicator = Ui.text(this, "●", 12, Ui.MUTED);
        identityStatusIndicator.setContentDescription("Signal E2E · нажмите, чтобы проверить отпечаток ключа");
        identityStatusIndicator.setPadding(Ui.dp(this, 9), Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8));
        identityStatusIndicator.setOnClickListener(v -> showSignalIdentity());
        header.addView(identityStatusIndicator);
        root.addView(header);

        messagesScroll = new ScrollView(this);
        messagesScroll.setFillViewport(true);
        messagesScroll.setClipToPadding(false);
        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 16));
        messagesScroll.addView(messagesContainer);
        root.addView(messagesScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composerRow = new LinearLayout(this);
        composerRow.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        composerRow.setOrientation(LinearLayout.HORIZONTAL);
        composerRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        TextView attach = Ui.text(this, "+", 24, Ui.ACCENT);
        attach.setGravity(Gravity.CENTER);
        attach.setBackground(Ui.rounded(Ui.SURFACE_ALT, Ui.dp(this, 17), Ui.STROKE));
        attach.setContentDescription("Вложения");
        attach.setOnClickListener(v -> showAttachmentMenu());
        composerRow.addView(attach, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 46)));

        composer = new EditText(this);
        composer.setTextColor(Ui.TEXT);
        composer.setHintTextColor(Ui.MUTED);
        composer.setHint("Сообщение");
        composer.setTextSize(14);
        composer.setMaxLines(5);
        composer.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        composer.setBackground(Ui.rounded(Ui.SURFACE_ALT, Ui.dp(this, 20), Ui.STROKE));
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, -2, 1);
        editParams.leftMargin = Ui.dp(this, 8);
        composerRow.addView(composer, editParams);

        recordingButton = Ui.text(this, "●", 17, Ui.MUTED);
        recordingButton.setGravity(Gravity.CENTER);
        recordingButton.setContentDescription("Записать голосовое сообщение");
        recordingButton.setOnClickListener(v -> toggleVoiceRecording());
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 46));
        micParams.leftMargin = Ui.dp(this, 3);
        composerRow.addView(recordingButton, micParams);

        TextView send = Ui.text(this, "↑", 23, Color.rgb(19, 17, 28));
        send.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        send.setGravity(Gravity.CENTER);
        send.setBackground(Ui.rounded(Ui.ACCENT, Ui.dp(this, 22), Color.TRANSPARENT));
        send.setContentDescription("Отправить сообщение");
        send.setOnClickListener(v -> sendText());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
        sendParams.leftMargin = Ui.dp(this, 5);
        composerRow.addView(send, sendParams);
        root.addView(composerRow);
        return root;
    }

    private void showSignalIdentity() {
        String fingerprint = engine.getPeerFingerprint(peerUid);
        if (fingerprint == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Signal E2E")
                    .setMessage("Ключ собеседника ещё не получен. Установите P2P-соединение и убедитесь, что у обоих пользователей актуальная версия Noir.")
                    .setPositiveButton("Понятно", null)
                    .show();
            return;
        }
        boolean verified = engine.isPeerIdentityVerified(peerUid);
        String message = "SHA-256 отпечаток identity key:\n" + formatFingerprint(fingerprint)
                + "\n\n" + (verified
                ? "Вы отметили этот ключ как сверенный. При смене ключа Signal-сессия будет отклонена."
                : "Сверьте весь отпечаток с собеседником по независимому каналу. До этого действует TOFU: первое полученное identity key сохранено, но вручную не подтверждено.");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Идентичность Signal")
                .setMessage(message)
                .setNegativeButton("Закрыть", null);
        if (!verified) {
            dialog.setPositiveButton("Я сверил отпечаток", (view, which) -> {
                engine.markPeerIdentityVerified(peerUid);
                if (identityStatusIndicator != null) identityStatusIndicator.setTextColor(Ui.GREEN);
                Toast.makeText(this, "Отпечаток отмечен как сверенный", Toast.LENGTH_SHORT).show();
            });
        }
        dialog.show();
    }

    private static String formatFingerprint(String fingerprint) {
        StringBuilder formatted = new StringBuilder(fingerprint.length() + fingerprint.length() / 4);
        for (int i = 0; i < fingerprint.length(); i++) {
            if (i > 0 && i % 4 == 0) formatted.append(' ');
            formatted.append(fingerprint.charAt(i));
        }
        return formatted.toString();
    }

    private void showAttachmentMenu() {
        String[] options = {"Фото", "Видео из галереи", "Записать кружок", "Голосовое сообщение", "Музыка / аудио", "Файл"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Отправить в P2P-чат")
                .setItems(options, (dialog, index) -> {
                    switch (index) {
                        case 0: pick("image/*", "image"); break;
                        case 1: pick("video/*", "video"); break;
                        case 2: recordCircle(); break;
                        case 3: toggleVoiceRecording(); break;
                        case 4: pick("audio/*", "audio"); break;
                        case 5: pick("*/*", "file"); break;
                    }
                }).show();
    }

    private void pick(String mime, String kind) {
        nextAttachmentKind = kind;
        contentPicker.launch(mime);
    }

    private void recordCircle() {
        try {
            File directory = new File(getCacheDir(), "capture");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Не удалось создать каталог записи");
            circleCaptureFile = new File(directory, "circle_" + System.currentTimeMillis() + ".mp4");
            circleCaptureUri = FileProvider.getUriForFile(this, getPackageName() + ".files", circleCaptureFile);
            Intent camera = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, circleCaptureUri);
            camera.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60);
            camera.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            videoCapture.launch(camera);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть камеру: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void toggleVoiceRecording() {
        if (recording) {
            stopVoiceRecording(true);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            startVoiceRecording();
        }
    }

    private void startVoiceRecording() {
        try {
            File directory = new File(getFilesDir(), "media");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Не удалось создать каталог записи");
            voiceFile = new File(directory, "voice_" + System.currentTimeMillis() + ".m4a");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(96_000);
            recorder.setAudioSamplingRate(44_100);
            recorder.setMaxDuration(5 * 60 * 1000);
            recorder.setOnInfoListener((activeRecorder, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && recording) stopVoiceRecording(true);
            });
            recorder.setOutputFile(voiceFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordingButton.setText("■");
            recordingButton.setTextColor(Color.rgb(255, 101, 117));
            connectionStatus.setText("ЗАПИСЬ ГОЛОСОВОГО · нажмите ■ для отправки");
        } catch (Exception e) {
            releaseRecorder();
            Toast.makeText(this, "Не удалось начать запись: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopVoiceRecording(boolean send) {
        if (!recording) return;
        boolean valid = false;
        try {
            recorder.stop();
            valid = voiceFile != null && voiceFile.isFile() && voiceFile.length() > 800;
        } catch (RuntimeException e) {
            Toast.makeText(this, "Запись слишком короткая", Toast.LENGTH_SHORT).show();
        } finally {
            releaseRecorder();
            recording = false;
            recordingButton.setText("●");
            recordingButton.setTextColor(Ui.MUTED);
            updateConnectionStatus();
        }
        if (send && valid) {
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", voiceFile);
                engine.sendAttachment(peerUid, uri, "voice");
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось подготовить голосовое", Toast.LENGTH_SHORT).show();
            }
        } else if (voiceFile != null) {
            //noinspection ResultOfMethodCallIgnored
            voiceFile.delete();
        }
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }

    private void sendText() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) return;
        if (engine.getUid() == null) {
            Toast.makeText(this, "Подождите, пока приложение подключится к Firebase", Toast.LENGTH_LONG).show();
            return;
        }
        engine.sendText(peerUid, text);
        composer.setText("");
        composer.clearFocus();
        android.view.inputmethod.InputMethodManager inputManager =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputManager != null) inputManager.hideSoftInputFromWindow(composer.getWindowToken(), 0);
    }

    private void renderMessages(boolean scrollToBottom) {
        if (messagesContainer == null || peerUid == null) return;
        boolean wasNearBottom = messagesScroll.getChildAt(0) == null
                || messagesScroll.getScrollY() + messagesScroll.getHeight()
                >= messagesScroll.getChildAt(0).getHeight() - Ui.dp(this, 100);
        messagesContainer.removeAllViews();
        List<MessageStore.Message> items = app.messages().getMessages(peerUid, 300);
        previewImageMessageIds.clear();
        for (int i = items.size() - 1; i >= 0 && previewImageMessageIds.size() < 12; i--) {
            MessageStore.Message message = items.get(i);
            if ("image".equals(message.kind)) previewImageMessageIds.add(message.id);
        }
        if (items.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Ui.dp(this, 26), Ui.dp(this, 42), Ui.dp(this, 26), Ui.dp(this, 42));
            TextView title = Ui.text(this, "Соединение напрямую", 15, Ui.TEXT);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            TextView detail = Ui.text(this, "Когда собеседник откроет Noir, канал WebRTC установится автоматически. Текст и файлы не сохраняются в Firebase.", 12, Ui.MUTED);
            detail.setGravity(Gravity.CENTER);
            detail.setLineSpacing(Ui.dp(this, 4), 1f);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = Ui.dp(this, 10);
            empty.addView(title);
            empty.addView(detail, detailParams);
            messagesContainer.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (MessageStore.Message message : items) addMessageBubble(message);
        }
        if (scrollToBottom || wasNearBottom) {
            messagesScroll.post(() -> messagesScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void addMessageBubble(MessageStore.Message message) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(message.outgoing ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = Ui.dp(this, 9);
        messagesContainer.addView(row, rowParams);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(Ui.dp(this, 13), Ui.dp(this, 10), Ui.dp(this, 13), Ui.dp(this, 8));
        bubble.setBackground(Ui.rounded(message.outgoing ? Color.rgb(45, 38, 65) : Ui.SURFACE_ALT,
                Ui.dp(this, 18), message.outgoing ? Color.rgb(63, 53, 87) : Ui.STROKE));
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(-2, -2);
        bubbleParams.width = Math.min(Ui.dp(this, 310), getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 72));
        row.addView(bubble, bubbleParams);

        if ("text".equals(message.kind)) {
            TextView body = Ui.text(this, message.body, 14, Ui.TEXT);
            body.setLineSpacing(Ui.dp(this, 3), 1f);
            body.setTextIsSelectable(true);
            bubble.addView(body);
        } else {
            addAttachmentCard(bubble, message);
        }

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView time = Ui.text(this, formatTime(message.createdAt), 9, Ui.MUTED);
        meta.addView(time);
        if (message.outgoing) {
            TextView state = Ui.text(this, messageStatus(message.status), 9,
                    "failed".equals(message.status) ? Color.rgb(255, 129, 142) : Ui.ACCENT);
            state.setPadding(Ui.dp(this, 5), 0, 0, 0);
            meta.addView(state);
        }
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.topMargin = Ui.dp(this, 6);
        bubble.addView(meta, metaParams);
    }

    private void addAttachmentCard(LinearLayout parent, MessageStore.Message message) {
        if ("image".equals(message.kind) && previewImageMessageIds.contains(message.id)) {
            addInlineImagePreview(parent, message);
        }
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = Ui.text(this, attachmentGlyph(message.kind), 18, Ui.ACCENT);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.rounded(Color.rgb(55, 46, 78), Ui.dp(this, 14), Color.TRANSPARENT));
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(this, 10), 0, 0, 0);
        TextView title = Ui.text(this, attachmentTitle(message), 13, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView subtitle = Ui.text(this, attachmentSubtitle(message), 10, Ui.MUTED);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = Ui.dp(this, 4);
        info.addView(title);
        info.addView(subtitle, subParams);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        card.setOnClickListener(v -> openAttachment(message));
        parent.setOnClickListener(v -> openAttachment(message));
        addTransferControl(parent, message);
    }

    private void addTransferControl(LinearLayout parent, MessageStore.Message message) {
        boolean active = "pending".equals(message.status) || "receiving".equals(message.status);
        boolean resumable = "paused".equals(message.status) || "failed".equals(message.status);
        if (message.transferSize <= 0L || (!active && !resumable)) return;
        TextView control = Ui.text(this, (resumable ? "▶ Продолжить" : "Ⅱ Пауза передачи"), 11, Ui.ACCENT);
        control.setGravity(Gravity.CENTER);
        control.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        control.setBackground(Ui.rounded(Color.rgb(42, 36, 59), Ui.dp(this, 12), Ui.STROKE));
        control.setClickable(true);
        control.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = Ui.dp(this, 6);
        parent.addView(control, params);
        control.setOnClickListener(v -> {
            if (resumable) engine.resumeTransfer(peerUid, message.id);
            else engine.pauseTransfer(message.id);
            renderMessages(false);
        });
    }

    private void addInlineImagePreview(LinearLayout parent, MessageStore.Message message) {
        if (message.filePath == null || (!message.outgoing && !"received".equals(message.status))) return;
        File file = new File(message.filePath);
        if (!file.isFile() || file.length() <= 0L) return;

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackground(Ui.rounded(Ui.SURFACE_ALT, Ui.dp(this, 14), Ui.STROKE));
        preview.setClipToOutline(true);
        preview.setContentDescription("Предпросмотр изображения · " + message.body);
        preview.setTag(file.getAbsolutePath());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                Math.min(Ui.dp(this, 270), getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 100)),
                Ui.dp(this, 190));
        params.bottomMargin = Ui.dp(this, 8);
        parent.addView(preview, 0, params);
        preview.setOnClickListener(v -> openAttachment(message));

        previewExecutor.execute(() -> {
            Bitmap bitmap = decodePreview(file, 768);
            if (bitmap == null) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !file.getAbsolutePath().equals(preview.getTag())) {
                    bitmap.recycle();
                    return;
                }
                preview.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap decodePreview(File file, int maxDimensionPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sampleSize = 1;
            while (bounds.outWidth / sampleSize > maxDimensionPx
                    || bounds.outHeight / sampleSize > maxDimensionPx) {
                sampleSize *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError | RuntimeException ignored) {
            return null;
        }
    }

    private String attachmentTitle(MessageStore.Message message) {
        switch (message.kind) {
            case "image": return "Фото · " + message.body;
            case "video": return "Видео · " + message.body;
            case "video_note": return "Кружок · " + message.body;
            case "voice": return "Голосовое сообщение";
            case "audio": return "Аудио · " + message.body;
            default: return message.body;
        }
    }

    private String attachmentSubtitle(MessageStore.Message message) {
        String progress = formatBytes(Math.min(message.transferOffset, message.transferSize))
                + " / " + formatBytes(message.transferSize);
        if ("receiving".equals(message.status)) return "Получение · " + progress;
        if ("paused".equals(message.status)) return "Пауза · " + progress;
        if ("failed".equals(message.status)) return "Прервано · " + progress + " · можно продолжить";
        if (message.outgoing && "pending".equals(message.status)) {
            return (message.transferOffset > 0 ? "Отправка · " + progress : "В очереди") + " · " + formatBytes(message.transferSize);
        }
        if (message.outgoing && "sent".equals(message.status)) return "Отправлено · " + formatBytes(message.transferSize);
        if (message.outgoing && "delivered".equals(message.status)) return "Доставлено · " + formatBytes(message.transferSize);
        return formatBytes(message.transferSize);
    }

    private static String attachmentGlyph(String kind) {
        switch (kind) {
            case "image": return "▧";
            case "video":
            case "video_note": return "▶";
            case "voice": return "♫";
            case "audio": return "♪";
            default: return "↧";
        }
    }

    private static String messageStatus(String status) {
        if ("pending".equals(status)) return "в очереди";
        if ("paused".equals(status)) return "пауза";
        if ("receiving".equals(status)) return "получение";
        if ("sent".equals(status)) return "отправлено";
        if ("delivered".equals(status)) return "доставлено";
        if ("failed".equals(status)) return "ошибка";
        return "";
    }

    private void openAttachment(MessageStore.Message message) {
        if ("receiving".equals(message.status) || "paused".equals(message.status)
                || (!message.outgoing && !"received".equals(message.status))) {
            Toast.makeText(this, "Файл ещё не получен полностью", Toast.LENGTH_SHORT).show();
            return;
        }
        if (message.filePath == null) return;
        File file = new File(message.filePath);
        if (!file.isFile()) {
            Toast.makeText(this, "Файл не найден на устройстве", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("voice".equals(message.kind) || "audio".equals(message.kind)) {
            playAudio(file);
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            String mime = message.mime == null ? "*/*" : message.mime;
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(uri, mime);
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(open, "Открыть файл"));
        } catch (Exception e) {
            Toast.makeText(this, "Не найдено приложение для этого файла", Toast.LENGTH_SHORT).show();
        }
    }

    private void playAudio(File file) {
        releasePlayer();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(player -> player.start());
            mediaPlayer.setOnCompletionListener(player -> releasePlayer());
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                releasePlayer();
                Toast.makeText(this, "Не удалось воспроизвести аудио", Toast.LENGTH_SHORT).show();
                return true;
            });
            mediaPlayer.prepareAsync();
            Toast.makeText(this, "Воспроизведение аудио", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            releasePlayer();
            Toast.makeText(this, "Не удалось открыть аудио", Toast.LENGTH_SHORT).show();
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) { }
            try { mediaPlayer.release(); } catch (Exception ignored) { }
            mediaPlayer = null;
        }
    }

    private void updateConnectionStatus() {
        if (connectionStatus == null) return;
        if (recording) {
            connectionStatus.setText("ЗАПИСЬ ГОЛОСОВОГО · нажмите ■ для отправки");
            return;
        }
        String state = engine.getStatus();
        connectionStatus.setText(state == null || state.isEmpty() ? "Ищем P2P-соединение…" : state);
    }

    private void startConnectionService() {
        Intent intent = new Intent(this, ChatConnectionService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private static String shortId(String value) {
        return value.length() <= 16 ? value : value.substring(0, 16) + "…";
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.0f КБ", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.1f МБ", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.addListener(this);
        engine.start();
        engine.requestConnection(peerUid);
    }

    @Override
    protected void onResume() {
        super.onResume();
        app.setVisibleChatPeer(peerUid);
        renderMessages(true);
    }

    @Override
    protected void onPause() {
        app.setVisibleChatPeer(null);
        super.onPause();
    }

    @Override
    protected void onStop() {
        engine.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopVoiceRecording(false);
        previewExecutor.shutdownNow();
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public void onPeerState(String changedPeerUid, String state) {
        if (!peerUid.equals(changedPeerUid)) return;
        if (connectionStatus != null) connectionStatus.setText(state);
        if (identityStatusIndicator != null && state.startsWith("Signal E2E активно")) {
            identityStatusIndicator.setTextColor(engine.isPeerIdentityVerified(peerUid) ? Ui.GREEN : Ui.ACCENT);
        } else if (identityStatusIndicator != null && state.startsWith("Signal E2E ошибка")) {
            identityStatusIndicator.setTextColor(Color.rgb(255, 129, 142));
        }
    }

    @Override
    public void onEngineStatus(String status) {
        if (connectionStatus != null && !recording) connectionStatus.setText(status);
    }

    @Override
    public void onMessagesChanged(String changedPeerUid) {
        if (peerUid.equals(changedPeerUid)) renderMessages(true);
    }

    @Override
    public void onIncomingInvite(String incomingPeerUid) { }
}
