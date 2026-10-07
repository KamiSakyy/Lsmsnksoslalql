package com.noir.p2pchat.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;

import com.noir.p2pchat.AppKernel;
import com.noir.p2pchat.MessageNotifications;
import com.noir.p2pchat.R;
import com.noir.p2pchat.core.MessageStore;
import com.noir.p2pchat.core.P2pEngine;
import com.noir.p2pchat.service.ChatConnectionService;

import java.util.List;

public final class MainActivity extends ComponentActivity implements P2pEngine.Listener {
    private AppKernel app;
    private P2pEngine engine;
    private LinearLayout root;
    private LinearLayout inviteList;
    private LinearLayout contactList;
    private TextView uidValue;
    private TextView statusValue;
    private ActivityResultLauncher<String> notificationPermission;
    private boolean permissionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (AppKernel) getApplication();
        engine = app.p2p();
        setUpSystemBars();
        notificationPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            startConnectionService();
        });
        setContentView(buildScreen());
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left + Ui.dp(this, 20), bars.top + Ui.dp(this, 8),
                    bars.right + Ui.dp(this, 20), bars.bottom + Ui.dp(this, 8));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
        refreshIdentity();
        renderLists();
        askNotificationPermissionAndStartService();
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
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 13));
        TextView mark = Ui.text(this, "N", 22, Ui.TEXT);
        mark.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.rounded(Ui.ACCENT, Ui.dp(this, 15), Color.TRANSPARENT));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42));
        header.addView(mark, markParams);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(Ui.dp(this, 11), 0, 0, 0);
        TextView title = Ui.text(this, "NOIR", 17, Ui.TEXT);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        TextView subtitle = Ui.text(this, "PEER-TO-PEER CHAT", 9, Ui.MUTED);
        subtitle.setLetterSpacing(0.14f);
        brand.addView(title);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-2, -2);
        subtitleParams.topMargin = Ui.dp(this, 4);
        brand.addView(subtitle, subtitleParams);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = Ui.text(this, "+", 28, Ui.TEXT);
        add.setGravity(Gravity.CENTER);
        add.setBackground(Ui.rounded(Ui.SURFACE_ALT, Ui.dp(this, 17), Ui.STROKE));
        add.setContentDescription("Новый чат");
        add.setOnClickListener(v -> showAddDialog());
        header.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        root.addView(header);

        LinearLayout liveStatus = new LinearLayout(this);
        liveStatus.setGravity(Gravity.CENTER_VERTICAL);
        liveStatus.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        liveStatus.setBackground(Ui.rounded(Color.rgb(19, 28, 26), Ui.dp(this, 14), Color.rgb(33, 57, 47)));
        View dot = new View(this);
        dot.setBackground(Ui.rounded(Ui.GREEN, Ui.dp(this, 6), Color.TRANSPARENT));
        liveStatus.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8)));
        statusValue = Ui.text(this, "Запускаем сигналинг…", 12, Ui.GREEN);
        statusValue.setSingleLine(true);
        statusValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1);
        statusParams.leftMargin = Ui.dp(this, 10);
        liveStatus.addView(statusValue, statusParams);
        root.addView(liveStatus, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 22));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout identityCard = new LinearLayout(this);
        identityCard.setOrientation(LinearLayout.VERTICAL);
        identityCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 16));
        identityCard.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 24), Ui.STROKE));
        TextView yourId = Ui.label(this, "ВАШ УНИКАЛЬНЫЙ ID");
        identityCard.addView(yourId);
        uidValue = Ui.text(this, "Создаём защищённый профиль…", 15, Ui.TEXT);
        uidValue.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        uidValue.setSingleLine(true);
        uidValue.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams uidParams = new LinearLayout.LayoutParams(-1, -2);
        uidParams.topMargin = Ui.dp(this, 12);
        identityCard.addView(uidValue, uidParams);
        TextView explain = Ui.text(this, "Отправьте этот ID собеседнику, чтобы открыть P2P-чат.", 12, Ui.MUTED);
        LinearLayout.LayoutParams explainParams = new LinearLayout.LayoutParams(-1, -2);
        explainParams.topMargin = Ui.dp(this, 9);
        identityCard.addView(explain, explainParams);
        content.addView(identityCard);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = Ui.dp(this, 12);
        content.addView(actionRow, actionParams);
        android.widget.Button create = Ui.button(this, "+  Новый чат", true);
        create.setOnClickListener(v -> showAddDialog());
        actionRow.addView(create, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        android.widget.Button share = Ui.button(this, "Поделиться ID", false);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        shareParams.leftMargin = Ui.dp(this, 9);
        actionRow.addView(share, shareParams);
        share.setOnClickListener(v -> shareOwnId());

        LinearLayout copyRow = new LinearLayout(this);
        copyRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView copyHint = Ui.text(this, "ID нужен для приглашения собеседника", 11, Ui.MUTED);
        copyRow.addView(copyHint, new LinearLayout.LayoutParams(0, -2, 1));
        TextView copy = Ui.text(this, "КОПИРОВАТЬ", 10, Ui.ACCENT);
        copy.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        copy.setLetterSpacing(0.08f);
        copy.setPadding(Ui.dp(this, 5), Ui.dp(this, 10), 0, Ui.dp(this, 10));
        copy.setOnClickListener(v -> copyOwnId());
        copyRow.addView(copy);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-1, -2);
        copyParams.topMargin = Ui.dp(this, 6);
        content.addView(copyRow, copyParams);

        addSection(content, "ЗАПРОСЫ", "Появятся, когда вам напишет новый собеседник");
        inviteList = new LinearLayout(this);
        inviteList.setOrientation(LinearLayout.VERTICAL);
        content.addView(inviteList);
        addSection(content, "ВАШИ ДИАЛОГИ", "Сообщения и вложения хранятся на устройстве");
        contactList = new LinearLayout(this);
        contactList.setOrientation(LinearLayout.VERTICAL);
        content.addView(contactList);

        TextView footer = Ui.text(this, "ФОТО  ·  ВИДЕО  ·  ГОЛОС  ·  МУЗЫКА  ·  P2P", 9, Ui.MUTED);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.09f);
        root.addView(footer, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
        return root;
    }

    private void addSection(LinearLayout parent, String title, String subtitle) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = Ui.label(this, title);
        section.addView(heading);
        TextView sub = Ui.text(this, subtitle, 11, Ui.MUTED);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = Ui.dp(this, 5);
        section.addView(sub, subParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Ui.dp(this, 23);
        params.bottomMargin = Ui.dp(this, 11);
        parent.addView(section, params);
    }

    private void refreshIdentity() {
        String value = engine.getUid();
        if (value != null) uidValue.setText(value);
        statusValue.setText(engine.getStatus());
    }

    private void renderLists() {
        if (inviteList == null || contactList == null) return;
        inviteList.removeAllViews();
        List<String> invites = engine.getPendingInvites();
        for (String peerUid : invites) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 15), Ui.dp(this, 13), Ui.dp(this, 12), Ui.dp(this, 13));
            row.setBackground(Ui.rounded(Ui.SURFACE_ALT, Ui.dp(this, 16), Ui.STROKE));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            TextView label = Ui.text(this, "Входящий запрос", 13, Ui.TEXT);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            TextView id = Ui.text(this, shortId(peerUid), 11, Ui.MUTED);
            id.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            details.addView(label);
            LinearLayout.LayoutParams idParams = new LinearLayout.LayoutParams(-2, -2);
            idParams.topMargin = Ui.dp(this, 5);
            details.addView(id, idParams);
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            android.widget.Button decline = Ui.button(this, "Отклонить", false);
            decline.setOnClickListener(v -> {
                engine.declineInvite(peerUid);
                renderLists();
            });
            row.addView(decline, new LinearLayout.LayoutParams(-2, Ui.dp(this, 42)));
            android.widget.Button accept = Ui.button(this, "Принять", true);
            accept.setOnClickListener(v -> openConversation(peerUid));
            LinearLayout.LayoutParams acceptParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 42));
            acceptParams.leftMargin = Ui.dp(this, 6);
            row.addView(accept, acceptParams);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = Ui.dp(this, 8);
            inviteList.addView(row, rowParams);
        }
        if (invites.isEmpty()) {
            TextView empty = Ui.text(this, "Здесь появятся приглашения по вашему ID", 12, Ui.MUTED);
            empty.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), 0, Ui.dp(this, 2));
            inviteList.addView(empty);
        }

        contactList.removeAllViews();
        List<String> contacts = app.messages().getContacts();
        for (int i = 0; i < contacts.size(); i++) {
            String peerUid = contacts.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 13), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            row.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 18), Ui.STROKE));
            TextView avatar = Ui.text(this, "N", 16, Ui.ACCENT);
            avatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(Ui.rounded(Color.rgb(39, 34, 60), Ui.dp(this, 20), Color.TRANSPARENT));
            row.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
            TextView name = Ui.text(this, shortId(peerUid), 14, Ui.TEXT);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            MessageStore.Message latest = app.messages().getMessages(peerUid, 1).isEmpty()
                    ? null : app.messages().getMessages(peerUid, 1).get(0);
            String preview = latest == null ? "P2P-диалог · нажмите, чтобы открыть" : preview(latest);
            TextView last = Ui.text(this, preview, 11, Ui.MUTED);
            last.setSingleLine(true);
            last.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(name);
            LinearLayout.LayoutParams lastParams = new LinearLayout.LayoutParams(-1, -2);
            lastParams.topMargin = Ui.dp(this, 5);
            info.addView(last, lastParams);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            TextView arrow = Ui.text(this, "›", 24, Ui.MUTED);
            row.addView(arrow);
            row.setOnClickListener(v -> openConversation(peerUid));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = Ui.dp(this, 8);
            contactList.addView(row, rowParams);
        }
        if (contacts.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(Ui.dp(this, 20), Ui.dp(this, 25), Ui.dp(this, 20), Ui.dp(this, 25));
            empty.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(this, 20), Ui.STROKE));
            TextView title = Ui.text(this, "Пока нет диалогов", 14, Ui.TEXT);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            TextView detail = Ui.text(this, "Создайте чат и отправьте собеседнику свой ID", 12, Ui.MUTED);
            detail.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
            detailParams.topMargin = Ui.dp(this, 8);
            empty.addView(title);
            empty.addView(detail, detailParams);
            contactList.addView(empty);
        }
    }

    private static String preview(MessageStore.Message message) {
        if ("text".equals(message.kind)) return message.body;
        String prefix;
        switch (message.kind) {
            case "image": prefix = "Фото"; break;
            case "video_note": prefix = "Кружок"; break;
            case "video": prefix = "Видео"; break;
            case "voice": prefix = "Голосовое"; break;
            case "audio": prefix = "Аудио"; break;
            default: prefix = "Файл";
        }
        return prefix + " · " + message.body;
    }

    private void showAddDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Ui.dp(this, 23), Ui.dp(this, 9), Ui.dp(this, 23), 0);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setHint("Firebase UID собеседника");
        input.setTextSize(14);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setBackground(Ui.rounded(Ui.SURFACE_ALT, Ui.dp(this, 14), Ui.STROKE));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));
        container.addView(input, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));
        TextView help = Ui.text(this, "Попросите собеседника скопировать ID на главном экране.", 12, Ui.MUTED);
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(-1, -2);
        helpParams.topMargin = Ui.dp(this, 11);
        container.addView(help, helpParams);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Новый P2P-чат")
                .setView(container)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Открыть чат", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String peerUid = input.getText().toString().trim();
            if (!peerUid.matches("[A-Za-z0-9_-]{8,128}")) {
                input.setError("Проверьте ID собеседника");
                return;
            }
            dialog.dismiss();
            openConversation(peerUid);
        }));
        dialog.show();
    }

    private void openConversation(String peerUid) {
        if (peerUid == null || !peerUid.matches("[A-Za-z0-9_-]{8,128}")) return;
        engine.addContact(peerUid);
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_PEER_UID, peerUid);
        startActivity(intent);
    }

    private void copyOwnId() {
        String value = engine.getUid();
        if (value == null) {
            Toast.makeText(this, "Подождите, создаём ID…", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Noir P2P ID", value));
        Toast.makeText(this, "ID скопирован", Toast.LENGTH_SHORT).show();
    }

    private void shareOwnId() {
        String value = engine.getUid();
        if (value == null) {
            Toast.makeText(this, "Подождите, создаём ID…", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Мой ID для Noir P2P-чата: " + value);
        startActivity(Intent.createChooser(share, "Поделиться ID"));
    }

    private void askNotificationPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !permissionRequested) {
            permissionRequested = true;
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            startConnectionService();
        }
    }

    private void startConnectionService() {
        Intent intent = new Intent(this, ChatConnectionService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        engine.addListener(this);
        engine.start();
        refreshIdentity();
        renderLists();
    }

    @Override
    protected void onStop() {
        engine.removeListener(this);
        super.onStop();
    }

    @Override
    public void onIdentity(String value) {
        uidValue.setText(value);
        renderLists();
    }

    @Override
    public void onEngineStatus(String value) {
        statusValue.setText(value);
    }

    @Override
    public void onIncomingInvite(String peerUid) {
        renderLists();
    }

    @Override
    public void onContactsChanged() {
        renderLists();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderLists();
    }

    private static String shortId(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12) + "…";
    }
}
