package com.tsuyu.messenger;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;
import com.tsuyu.messenger.crypto.LocalMessageCache;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatMessage;
import com.tsuyu.messenger.data.ChatRepository;
import com.tsuyu.messenger.data.ChatSummary;
import com.tsuyu.messenger.data.PresenceManager;
import com.tsuyu.messenger.data.ProfileRepository;
import com.tsuyu.messenger.notifications.FirebaseMessagingSetup;
import com.tsuyu.messenger.notifications.NotificationSounds;
import com.tsuyu.messenger.ui.Ui;
import com.tsuyu.messenger.ui.ZoomImageView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends AppCompatActivity {
    private FirebaseAuth auth;
    private FirebaseDatabase database;
    private ProfileRepository profileRepository;
    private ChatRepository chatRepository;
    private PresenceManager presenceManager;
    private SignalE2ee crypto;
    private LocalMessageCache messageCache;
    private FirebaseAuth.AuthStateListener authListener;

    private String uid;
    private String activeChatId;
    private String activePeerUid;
    private Profile myProfile;
    private Profile peerProfile;
    private String page = "";
    private boolean authFormIsRegister;
    private boolean editingAvatar;
    private boolean isNewProfile;
    private boolean searchMode;
    private String replyToId;
    private String replyToText;
    private long lastTypingWrite;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService cryptoQueue = Executors.newSingleThreadExecutor();
    private final ExecutorService dataQueue = Executors.newFixedThreadPool(3);
    private final Set<String> decrypting = ConcurrentHashMap.newKeySet();
    private final Set<String> decryptFailures = ConcurrentHashMap.newKeySet();
    private final Map<String, ChatSummary> summaries = new ConcurrentHashMap<>();
    private final Map<String, DatabaseReference> summaryPresenceRefs = new ConcurrentHashMap<>();
    private final Map<String, ValueEventListener> summaryPresenceListeners = new ConcurrentHashMap<>();
    private final Map<String, DatabaseReference> summaryTypingRefs = new ConcurrentHashMap<>();
    private final Map<String, ValueEventListener> summaryTypingListeners = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> messageReactions = new ConcurrentHashMap<>();

    private ActivityResultLauncher<String> avatarPicker;
    private ActivityResultLauncher<String[]> documentPicker;
    private ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> multiPhotoPicker;
    private android.media.MediaRecorder voiceRecorder;
    private java.io.File voiceFile;
    private String pendingMediaKind;
    private String draftAvatarBase64 = "";

    private LinearLayout pageRoot;
    private LinearLayout chatList;
    private LinearLayout searchResults;
    private EditText searchInput;
    private EditText composer;
    private TextView chatStatus;
    private TextView replyBanner;
    private ScrollView messageScroll;
    private LinearLayout messageColumn;
    private ValueEventListener userChatsListener;
    private ValueEventListener messagesListener;
    private ValueEventListener typingListener;
    private ValueEventListener reactionsListener;
    private ValueEventListener receiptListener;
    private DatabaseReference receiptRef;
    private long peerReadAt;
    private ValueEventListener peerPresenceListener;
    private DatabaseReference peerPresenceRef;
    private ValueEventListener peerLastSeenListener;
    private DatabaseReference peerLastSeenRef;
    private boolean peerOnline;
    private boolean peerLastSeenVisible;
    private long peerLastSeen;
    private boolean peerTyping;
    private String listenedUserChatsUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseBootstrap.initialize(this);
        FirebaseApp app = FirebaseApp.getInstance();
        auth = FirebaseAuth.getInstance(app);
        database = FirebaseDatabase.getInstance(app);
        profileRepository = new ProfileRepository(database);
        chatRepository = new ChatRepository(database);
        presenceManager = new PresenceManager(this, database);
        NotificationSounds.ensureChannel(this);

        avatarPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            if (editingAvatar) {
                editingAvatar = false;
                try {
                    draftAvatarBase64 = imageToBase64(uri, 512, 280_000);
                    Toast.makeText(this, "Фото выбрано", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    toast("Не удалось прочитать изображение.");
                }
            } else {
                String kind = pendingMediaKind == null ? "image" : pendingMediaKind;
                sendPickedMedia(uri, kind);
            }
        });
        documentPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            if ("sound".equals(pendingMediaKind)) {
                try {
                    NotificationSounds.setSound(this, uri);
                    toast("Звук уведомлений сохранён.");
                } catch (Exception e) {
                    toast("Не удалось сохранить выбранный звук.");
                }
            } else {
                sendPickedMedia(uri, pendingMediaKind == null ? "video" : pendingMediaKind);
            }
        });
        multiPhotoPicker = registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(6), uris -> {
            if (uris != null && !uris.isEmpty()) sendPickedPhotos(uris);
        });

        authListener = firebaseAuth -> {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            if (user == null) {
                uid = null;
                crypto = null;
                messageCache = null;
                stopBackgroundSync();
                detachRealtimeListeners();
                page = "";
                showAuth();
            } else {
                handleSignedIn(user.getUid());
            }
        };
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 203);
        }
        FirebaseUser current = auth.getCurrentUser();
        if (current == null) showAuth(); else handleSignedIn(current.getUid());
        handleNotificationIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        auth.addAuthStateListener(authListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (auth != null && authListener != null) auth.removeAuthStateListener(authListener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null || auth.getCurrentUser() == null) return;
        String chatId = intent.getStringExtra("open_chat_id");
        String peerUid = intent.getStringExtra("open_peer_uid");
        if (chatId != null && peerUid != null) {
            intent.removeExtra("open_chat_id");
            intent.removeExtra("open_peer_uid");
            ui.postDelayed(() -> showChat(chatId, peerUid), 350);
        }
    }

    private void handleSignedIn(String accountUid) {
        if (accountUid == null || accountUid.equals(uid) && !page.isEmpty()) return;
        page = "loading";
        uid = accountUid;
        crypto = new SignalE2ee(this, uid);
        messageCache = new LocalMessageCache(this, uid);
        String presenceUid = accountUid;
        database.getReference("users").child(presenceUid).child("private").child("settings").child("ghost").get()
                .addOnSuccessListener(snapshot -> {
                    if (!presenceUid.equals(uid)) return;
                    if (snapshot.exists()) presenceManager.setGhostMode(presenceUid, Boolean.TRUE.equals(snapshot.getValue(Boolean.class)));
                    else presenceManager.start(presenceUid);
                }).addOnFailureListener(error -> { if (presenceUid.equals(uid)) presenceManager.start(presenceUid); });
        startBackgroundSync();
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> FirebaseMessagingSetup.saveToken(FirebaseApp.getInstance(), token));
        cryptoQueue.execute(() -> {
            try {
                Map<String, Object> bundle = crypto.publicKeyBundle();
                database.getReference("users").child(uid).child("keys").child("1").setValue(bundle);
            } catch (Exception e) {
                ui.post(() -> toast("Не удалось опубликовать публичные ключи. Проверьте правила RTDB."));
            }
        });
        profileRepository.getProfile(uid, profile -> {
            if (!uid.equals(accountUid)) return;
            if (profile == null || profile.username == null || profile.username.isEmpty()) {
                showProfileEditor(true);
            } else {
                myProfile = profile;
                attachUserChatsListener();
                showHome();
            }
        });
    }

    private void showAuth() {
        if (isFinishing()) return;
        page = "auth";
        LinearLayout root = vertical(Ui.BLACK);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 24));
        TextView logo = Ui.text(this, "TSUYU", 34, Ui.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setLetterSpacing(0.12f);
        root.addView(logo, matchWrap());
        TextView subtitle = Ui.text(this, "Личные сообщения. Ваши ключи — на вашем устройстве.", 13, 0xff888888, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 22));
        root.addView(subtitle, matchWrap());
        TextView badge = Ui.text(this, "  ◈  Сквозное шифрование", 12, Ui.SECONDARY, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        badge.setBackground(Ui.bordered(Ui.INPUT, Ui.BORDER, this, 16, 1));
        LinearLayout.LayoutParams badgeLp = wrap();
        badgeLp.bottomMargin = Ui.dp(this, 20);
        root.addView(badge, badgeLp);

        EditText email = Ui.input(this, "Почта", false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(email, fieldParams());
        EditText password = Ui.input(this, "Пароль", true);
        root.addView(password, fieldParams());
        TextView primary = Ui.button(this, authFormIsRegister ? "Создать аккаунт" : "Войти", Ui.WHITE, Ui.BLACK, 14);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        buttonLp.topMargin = Ui.dp(this, 8);
        root.addView(primary, buttonLp);
        primary.setOnClickListener(v -> {
            String mail = email.getText().toString().trim();
            String pass = password.getText().toString();
            if (mail.isEmpty() || pass.length() < 6) {
                toast("Введите почту и пароль (не короче 6 символов).");
                return;
            }
            primary.setEnabled(false);
            if (authFormIsRegister) {
                auth.createUserWithEmailAndPassword(mail, pass)
                        .addOnSuccessListener(result -> { primary.setEnabled(true); toast("Аккаунт создан. Настройте профиль."); handleSignedIn(result.getUser().getUid()); })
                        .addOnFailureListener(e -> { primary.setEnabled(true); toast(authError(e)); });
            } else {
                auth.signInWithEmailAndPassword(mail, pass)
                        .addOnSuccessListener(result -> primary.setEnabled(true))
                        .addOnFailureListener(e -> { primary.setEnabled(true); toast(authError(e)); });
            }
        });
        TextView switchMode = Ui.text(this, authFormIsRegister ? "Уже есть аккаунт? Войти" : "Нет аккаунта? Зарегистрироваться", 14, Ui.BLUE, true);
        switchMode.setGravity(Gravity.CENTER);
        switchMode.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 10));
        switchMode.setOnClickListener(v -> { authFormIsRegister = !authFormIsRegister; showAuth(); });
        root.addView(switchMode, matchWrap());
        TextView footer = Ui.text(this, "Tsuyu • только личные диалоги", 12, 0xff666666, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(-1, 0, 1);
        root.addView(footer, footLp);
        setContentView(root);
    }

    private void showProfileEditor(boolean newProfile) {
        markNoActiveChat();
        page = "profile_edit";
        isNewProfile = newProfile;
        draftAvatarBase64 = myProfile == null || myProfile.avatarBase64 == null ? "" : myProfile.avatarBase64;
        LinearLayout root = vertical(Ui.BLACK);
        addTopBar(root, newProfile ? "Ваш профиль" : "Редактировать профиль", "‹", () -> {
            if (newProfile) auth.signOut(); else showSettings();
        }, null, null);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical(Ui.BLACK);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 24));
        TextView avatar = Ui.text(this, "Изменить фото профиля", 14, Ui.WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.shape(Ui.INPUT, this, 18));
        avatar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 118), Ui.dp(this, 118)));
        avatar.setOnClickListener(v -> { editingAvatar = true; avatarPicker.launch("image/*"); });
        LinearLayout.LayoutParams avatarLp = wrap(); avatarLp.gravity = Gravity.CENTER_HORIZONTAL; avatarLp.bottomMargin = Ui.dp(this, 24);
        body.addView(avatar, avatarLp);
        EditText name = Ui.input(this, "Имя", false);
        EditText username = Ui.input(this, "@юзернейм", false);
        EditText about = Ui.input(this, "Описание", false);
        about.setSingleLine(false); about.setMinLines(2); about.setGravity(Gravity.TOP | Gravity.START);
        if (myProfile != null) {
            name.setText(myProfile.displayName);
            username.setText(myProfile.username == null ? "" : "@" + myProfile.username);
            about.setText(myProfile.about);
        }
        body.addView(name, fieldParams()); body.addView(username, fieldParams()); body.addView(about, fieldParams());
        TextView hint = Ui.text(this, "Юзернейм должен быть уникальным: латиница, цифры и _ (3–20 знаков).", 12, Ui.SECONDARY, false);
        hint.setPadding(0, 0, 0, Ui.dp(this, 16)); body.addView(hint, matchWrap());
        TextView save = Ui.button(this, "Сохранить", Ui.BLUE, Ui.WHITE, 14);
        body.addView(save, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        save.setOnClickListener(v -> {
            save.setEnabled(false);
            profileRepository.saveProfile(uid, name.getText().toString(), username.getText().toString(),
                    about.getText().toString(), draftAvatarBase64,
                    new ProfileRepository.Result<Profile>() {
                        @Override public void success(Profile profile) {
                            save.setEnabled(true); myProfile = profile; attachUserChatsListener(); showHome();
                        }
                        @Override public void error(String message) { save.setEnabled(true); toast(message); }
                    });
        });
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
    }

    private void showHome() {
        if (uid == null) { showAuth(); return; }
        markNoActiveChat();
        page = "home";
        activeChatId = null; activePeerUid = null;
        LinearLayout root = vertical(Ui.BLACK);
        LinearLayout header = horizontal(Ui.HEADER);
        header.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 10), 0);
        TextView menu = Ui.iconButton(this, "☰", Ui.WHITE);
        header.addView(menu, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        menu.setOnClickListener(v -> showSettings());
        LinearLayout titleBox = vertical(Ui.HEADER);
        TextView title = Ui.text(this, "Tsuyu", 20, Ui.WHITE, true);
        title.setLetterSpacing(0.04f);
        TextView subtitle = Ui.text(this, "Личные диалоги", 11, Ui.SECONDARY, false);
        titleBox.addView(title); titleBox.addView(subtitle);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.leftMargin = Ui.dp(this, 10);
        header.addView(titleBox, titleLp);
        TextView search = Ui.iconButton(this, "⌕", Ui.SECONDARY);
        header.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        search.setOnClickListener(v -> showSearchScreen());
        TextView profile = Ui.iconButton(this, "●", Ui.WHITE);
        header.addView(profile, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        profile.setOnClickListener(v -> showProfileEditor(false));
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        root.addView(Ui.divider(this));

        chatList = vertical(Ui.BLACK);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(chatList);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView empty = Ui.text(this, "Здесь появятся ваши диалоги.\nНайдите человека по @юзернейму.", 14, Ui.SECONDARY, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 28), Ui.dp(this, 60), Ui.dp(this, 28), Ui.dp(this, 60));
        chatList.addView(empty, matchWrap());
        root.addView(bottomBar("Диалоги"));
        setContentView(root);
        attachUserChatsListener();
        if (getIntent() != null) handleNotificationIntent(getIntent());
    }

    private void attachUserChatsListener() {
        if (uid == null || uid.equals(listenedUserChatsUid) && userChatsListener != null) return;
        detachUserChatsListener();
        listenedUserChatsUid = uid;
        userChatsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) { loadChatSummaries(snapshot); }
            @Override public void onCancelled(DatabaseError error) { toast("Не удалось загрузить диалоги: " + error.getMessage()); }
        };
        chatRepository.userChats(uid).addValueEventListener(userChatsListener);
    }

    private void detachUserChatsListener() {
        if (userChatsListener != null && listenedUserChatsUid != null) {
            chatRepository.userChats(listenedUserChatsUid).removeEventListener(userChatsListener);
        }
        userChatsListener = null;
        listenedUserChatsUid = null;
        summaries.clear();
        for (String chatId : new HashSet<>(summaryPresenceRefs.keySet())) removeSummaryRealtime(chatId);
    }

    private void loadChatSummaries(DataSnapshot snapshot) {
        summaries.clear();
        List<String> ids = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) if (child.getKey() != null) ids.add(child.getKey());
        Set<String> idSet = new HashSet<>(ids);
        for (String oldId : new HashSet<>(summaryPresenceRefs.keySet())) if (!idSet.contains(oldId)) removeSummaryRealtime(oldId);
        if (ids.isEmpty()) { renderChatList(); return; }
        for (String chatId : ids) dataQueue.execute(() -> loadOneSummary(chatId));
    }

    private void loadOneSummary(String chatId) {
        try {
            DataSnapshot members = Tasks.await(chatRepository.root().child("chats").child(chatId).child("members").get(), 10, TimeUnit.SECONDS);
            String peerUid = null;
            for (DataSnapshot child : members.getChildren()) {
                if (child.getKey() != null && !child.getKey().equals(uid)) { peerUid = child.getKey(); break; }
            }
            if (peerUid == null) return;
            DatabaseReference profileRef = chatRepository.root().child("users").child(peerUid).child("profile");
            Profile profile = new Profile(); profile.uid = peerUid;
            profile.username = Tasks.await(profileRef.child("username").get(), 10, TimeUnit.SECONDS).getValue(String.class);
            profile.displayName = readOptionalProfileField(profileRef, "displayName");
            profile.avatarBase64 = readOptionalProfileField(profileRef, "avatarBase64");
            profile.about = readOptionalProfileField(profileRef, "about");
            if (profile.username == null) return;
            DataSnapshot meta = Tasks.await(chatRepository.chatMeta(chatId).get(), 10, TimeUnit.SECONDS);
            ChatMessage last = meta.child("lastMessage").getValue(ChatMessage.class);
            if (last != null) last.id = meta.child("lastMessageId").getValue(String.class);
            long at = number(meta.child("lastAt").getValue());
            boolean online = false;
            try {
                DataSnapshot onlineSnapshot = Tasks.await(chatRepository.presence(peerUid).child("online").get(), 10, TimeUnit.SECONDS);
                online = Boolean.TRUE.equals(onlineSnapshot.getValue(Boolean.class));
            } catch (Exception hiddenOrUnavailable) { online = false; }
            DataSnapshot typing = Tasks.await(chatRepository.typing(chatId).child(peerUid).get(), 10, TimeUnit.SECONDS);
            boolean isTyping = Boolean.TRUE.equals(typing.child("typing").getValue(Boolean.class));
            long typingAt = number(typing.child("at").getValue());
            if (System.currentTimeMillis() - typingAt > 5500) isTyping = false;
            ChatSummary summary = new ChatSummary(chatId, peerUid, profile, at,
                    last == null ? null : last.cipher);
            summary.online = online;
            summary.typing = isTyping;
            summary.lastMessage = last;
            summaries.put(chatId, summary);
            watchSummaryRealtime(chatId, peerUid);
            if (last != null) maybeDecryptPreview(summary, last);
            runOnUiThread(this::renderChatList);
        } catch (Exception ignored) {
            // A missing/denied chat is omitted; the rules file documents the expected RTDB access.
        }
    }

    private String readOptionalProfileField(DatabaseReference profileRef, String field) {
        try { return Tasks.await(profileRef.child(field).get(), 10, TimeUnit.SECONDS).getValue(String.class); }
        catch (Exception deniedOrUnavailable) { return null; }
    }

    private void watchSummaryRealtime(String chatId, String peerUid) {
        if (!summaryPresenceListeners.containsKey(chatId)) {
            DatabaseReference ref = chatRepository.presence(peerUid).child("online");
            ValueEventListener listener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    ChatSummary summary = summaries.get(chatId);
                    if (summary != null) { summary.online = Boolean.TRUE.equals(snapshot.getValue(Boolean.class)); renderChatList(); }
                }
                @Override public void onCancelled(DatabaseError error) { }
            };
            summaryPresenceRefs.put(chatId, ref); summaryPresenceListeners.put(chatId, listener); ref.addValueEventListener(listener);
        }
        if (!summaryTypingListeners.containsKey(chatId)) {
            DatabaseReference ref = chatRepository.typing(chatId).child(peerUid);
            ValueEventListener listener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    ChatSummary summary = summaries.get(chatId);
                    if (summary == null) return;
                    long at = number(snapshot.child("at").getValue());
                    summary.typing = Boolean.TRUE.equals(snapshot.child("typing").getValue(Boolean.class)) && System.currentTimeMillis() - at <= 5500;
                    renderChatList();
                }
                @Override public void onCancelled(DatabaseError error) { }
            };
            summaryTypingRefs.put(chatId, ref); summaryTypingListeners.put(chatId, listener); ref.addValueEventListener(listener);
        }
    }

    private void removeSummaryRealtime(String chatId) {
        DatabaseReference p = summaryPresenceRefs.remove(chatId); ValueEventListener pl = summaryPresenceListeners.remove(chatId);
        if (p != null && pl != null) p.removeEventListener(pl);
        DatabaseReference t = summaryTypingRefs.remove(chatId); ValueEventListener tl = summaryTypingListeners.remove(chatId);
        if (t != null && tl != null) t.removeEventListener(tl);
    }

    private void maybeDecryptPreview(ChatSummary summary, ChatMessage message) {
        if (message.id == null || message.cipher == null || message.senderUid == null) return;
        String cached = messageCache == null ? null : messageCache.get(summary.chatId, message.id);
        if (cached != null || message.senderUid.equals(uid)) return;
        String key = summary.chatId + ":" + message.id;
        if (!decrypting.add(key)) return;
        cryptoQueue.execute(() -> {
            try {
                byte[] plaintext = crypto.decrypt(summary.peerUid, message.signalType, message.cipher);
                String json = new String(plaintext, StandardCharsets.UTF_8);
                messageCache.put(summary.chatId, message.id, json);
                runOnUiThread(this::renderChatList);
            } catch (Exception ignored) { }
            finally { decrypting.remove(key); }
        });
    }

    private void renderChatList() {
        if (!"home".equals(page) || chatList == null) return;
        chatList.removeAllViews();
        List<ChatSummary> items = new ArrayList<>(summaries.values());
        items.sort((a, b) -> Long.compare(b.lastAt, a.lastAt));
        if (items.isEmpty()) {
            TextView empty = Ui.text(this, "Здесь появятся ваши диалоги.\nНайдите человека по @юзернейму.", 14, Ui.SECONDARY, false);
            empty.setGravity(Gravity.CENTER); empty.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            empty.setPadding(Ui.dp(this, 28), Ui.dp(this, 60), Ui.dp(this, 28), Ui.dp(this, 60));
            chatList.addView(empty, matchWrap());
            return;
        }
        for (ChatSummary item : items) chatList.addView(chatRow(item));
    }

    private View chatRow(ChatSummary summary) {
        LinearLayout row = horizontal(Ui.BLACK);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        FrameLayout avatarWrap = new FrameLayout(this);
        ImageView avatar = avatar(summary.peer, 50);
        avatarWrap.addView(avatar, new FrameLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50)));
        View dot = new View(this);
        dot.setBackground(Ui.bordered(summary.online ? Ui.GREEN : Ui.SECONDARY, Ui.BLACK, this, 8, 2));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14), Gravity.BOTTOM | Gravity.RIGHT);
        avatarWrap.addView(dot, dotLp);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50));
        avatarLp.rightMargin = Ui.dp(this, 12);
        row.addView(avatarWrap, avatarLp);

        LinearLayout textCol = vertical(Ui.BLACK);
        LinearLayout top = horizontal(Ui.BLACK);
        TextView name = Ui.text(this, summary.peer.displayNameOrUsername(), 15, Ui.WHITE, true);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView time = Ui.text(this, summary.lastAt <= 0 ? "" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(summary.lastAt)), 11, Ui.SECONDARY, false);
        top.addView(time);
        textCol.addView(top);
        String preview = summary.typing ? "печатает…" : previewFor(summary);
        TextView last = Ui.text(this, preview, 13, summary.typing ? Ui.BLUE : 0xff888888, false);
        last.setSingleLine(true); last.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lastLp = new LinearLayout.LayoutParams(-1, -2); lastLp.topMargin = Ui.dp(this, 6);
        textCol.addView(last, lastLp);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1);
        textLp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(textCol, textLp);
        row.setMinimumHeight(Ui.dp(this, 74));
        row.setOnClickListener(v -> openChat(summary));
        row.setForeground(Ui.shape(0x00000000, this, 0));
        return row;
    }

    private String previewFor(ChatSummary summary) {
        ChatMessage msg = summary.lastMessage;
        if (msg == null) return "Сквозное шифрование · начните диалог";
        String cached = msg.id == null || messageCache == null ? null : messageCache.get(summary.chatId, msg.id);
        if (cached != null) return previewFromJson(cached);
        if (uid.equals(msg.senderUid)) return "Вы отправили сообщение";
        return msg.cipher == null ? "Сообщение" : "Зашифрованное сообщение";
    }

    private void openChat(ChatSummary summary) { showChat(summary.chatId, summary.peerUid); }

    private void showSearchScreen() {
        if (uid == null) return;
        markNoActiveChat();
        page = "search";
        searchMode = true;
        LinearLayout root = vertical(Ui.BLACK);
        LinearLayout header = horizontal(Ui.HEADER); header.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        TextView back = Ui.iconButton(this, "‹", Ui.WHITE); header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        back.setOnClickListener(v -> showHome());
        TextView title = Ui.text(this, "Поиск людей", 18, Ui.WHITE, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1); titleLp.leftMargin = Ui.dp(this, 10); header.addView(title, titleLp);
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56))); root.addView(Ui.divider(this));
        LinearLayout searchWrap = horizontal(Ui.HEADER); searchWrap.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView lens = Ui.text(this, "⌕", 22, Ui.SECONDARY, false); lens.setGravity(Gravity.CENTER); searchWrap.addView(lens, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 42)));
        searchInput = new EditText(this); searchInput.setSingleLine(true); searchInput.setTextColor(Ui.WHITE); searchInput.setHintTextColor(0xff666666); searchInput.setTextSize(14); searchInput.setHint("Введите @юзернейм"); searchInput.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 8), 0); searchInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        searchWrap.addView(searchInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        root.addView(searchWrap); root.addView(Ui.divider(this));
        ScrollView scroll = new ScrollView(this); searchResults = vertical(Ui.BLACK); scroll.addView(searchResults); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView empty = Ui.text(this, "Поиск работает по уникальному @юзернейму.", 13, Ui.SECONDARY, false); empty.setGravity(Gravity.CENTER); searchResults.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(this, 100)));
        searchInput.addTextChangedListener(new TextWatcher() {
            private final Handler debounce = new Handler(Looper.getMainLooper());
            private Runnable pending;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pending != null) debounce.removeCallbacks(pending);
                String query = s == null ? "" : s.toString();
                pending = () -> runSearch(query);
                debounce.postDelayed(pending, 260);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(bottomBar("Поиск"));
        setContentView(root);
        searchInput.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void runSearch(String query) {
        if (!"search".equals(page)) return;
        String normalized = ProfileRepository.normalizeUsername(query);
        if (normalized.length() < 1) return;
        profileRepository.searchUsers(normalized, uid, new ProfileRepository.Result<List<Profile>>() {
            @Override public void success(List<Profile> profiles) {
                if (!"search".equals(page)) return;
                searchResults.removeAllViews();
                if (profiles.isEmpty()) {
                    TextView empty = Ui.text(MainActivity.this, "Никого не найдено", 14, Ui.SECONDARY, false);
                    empty.setGravity(Gravity.CENTER); searchResults.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(MainActivity.this, 100)));
                } else {
                    for (Profile profile : profiles) {
                        profileRepository.getProfile(profile.uid, full -> {
                            if (full != null) {
                                full.uid = profile.uid;
                                searchResults.addView(searchRow(full), 0);
                                searchResults.getChildAt(0).setAlpha(0f);
                                searchResults.getChildAt(0).animate().alpha(1f).translationY(0f).setDuration(180).start();
                            }
                        });
                    }
                }
            }
            @Override public void error(String message) { toast(message); }
        });
    }

    private View searchRow(Profile profile) {
        LinearLayout row = horizontal(Ui.BLACK); row.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        ImageView avatar = avatar(profile, 48);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)); avLp.rightMargin = Ui.dp(this, 12); row.addView(avatar, avLp);
        LinearLayout col = vertical(Ui.BLACK);
        col.addView(Ui.text(this, profile.displayNameOrUsername(), 15, Ui.WHITE, true));
        TextView handle = Ui.text(this, "@" + (profile.username == null ? "" : profile.username), 12, Ui.SECONDARY, false);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-1, -2); hLp.topMargin = Ui.dp(this, 5); col.addView(handle, hLp);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView message = Ui.text(this, "Написать  ›", 12, Ui.BLUE, true); row.addView(message);
        row.setOnClickListener(v -> chatRepository.openOrCreate(uid, profile.uid, new ChatRepository.Result<String>() {
            @Override public void success(String chatId) { peerProfile = profile; showChat(chatId, profile.uid); }
            @Override public void error(String error) { toast(error); }
        }));
        row.setOnLongClickListener(v -> { peerProfile = profile; showPeerProfile(); return true; });
        return row;
    }

    private void showChat(String chatId, String peerUid) {
        if (uid == null || chatId == null || peerUid == null) return;
        detachChatListeners();
        activeChatId = chatId; activePeerUid = peerUid; replyToId = null; replyToText = null;
        getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().putString("active_chat", chatId).apply();
        page = "chat";
        LinearLayout root = vertical(Ui.BLACK);
        LinearLayout header = horizontal(Ui.HEADER); header.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        TextView back = Ui.iconButton(this, "‹", Ui.WHITE); header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38))); back.setOnClickListener(v -> { detachChatListeners(); showHome(); });
        ImageView avatar = new ImageView(this);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)); avLp.leftMargin = Ui.dp(this, 6); avLp.rightMargin = Ui.dp(this, 9); header.addView(avatar, avLp);
        LinearLayout info = vertical(Ui.HEADER);
        TextView name = Ui.text(this, "Tsuyu user", 15, Ui.WHITE, true);
        chatStatus = Ui.text(this, "сквозное шифрование", 11, Ui.SECONDARY, false);
        info.addView(name); info.addView(chatStatus);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, -2, 1); infoLp.gravity = Gravity.CENTER_VERTICAL; header.addView(info, infoLp);
        TextView more = Ui.iconButton(this, "⋮", Ui.SECONDARY); header.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        more.setOnClickListener(v -> showChatMenu(more));
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56))); root.addView(Ui.divider(this));

        messageColumn = vertical(Ui.BLACK); messageColumn.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        messageScroll = new ScrollView(this); messageScroll.setFillViewport(true); messageScroll.setClipToPadding(false); messageScroll.addView(messageColumn);
        root.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composerPanel = vertical(Ui.HEADER);
        replyBanner = Ui.text(this, "", 12, Ui.BLUE, false); replyBanner.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 4)); replyBanner.setVisibility(View.GONE); composerPanel.addView(replyBanner, matchWrap());
        LinearLayout bar = horizontal(Ui.HEADER); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 8), Ui.dp(this, 7));
        TextView attach = Ui.iconButton(this, "+", Ui.SECONDARY); bar.addView(attach, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        attach.setOnClickListener(v -> showAttachmentMenu());
        composer = new EditText(this); composer.setTextColor(Ui.WHITE); composer.setHintTextColor(0xff666666); composer.setTextSize(14); composer.setHint("Сообщение"); composer.setMaxLines(4); composer.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10)); composer.setBackground(Ui.bordered(Ui.INPUT, 0xff2a2a2c, this, 20, 1));
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, -2, 1); editLp.leftMargin = Ui.dp(this, 6); editLp.rightMargin = Ui.dp(this, 6); bar.addView(composer, editLp);
        TextView send = Ui.button(this, "➤", Ui.BLUE, Ui.WHITE, 24); send.setTextSize(19); send.setPadding(0, 0, 0, 0); bar.addView(send, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        send.setOnClickListener(v -> sendCurrentText());
        composer.setOnEditorActionListener((view, actionId, event) -> {
            if (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && !event.isShiftPressed()) { sendCurrentText(); return true; }
            return false;
        });
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (activeChatId != null && !presenceManager.isGhostMode()) {
                    long now = System.currentTimeMillis();
                    if (s != null && s.length() > 0 && now - lastTypingWrite > 800) {
                        lastTypingWrite = now; chatRepository.setTyping(activeChatId, uid, true);
                    }
                    ui.removeCallbacks(clearTyping);
                    if (s == null || s.length() == 0) chatRepository.setTyping(activeChatId, uid, false);
                    else ui.postDelayed(clearTyping, 1800);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        composerPanel.addView(bar);
        root.addView(composerPanel);
        setContentView(root);
        profileRepository.getProfile(peerUid, profile -> {
            if (!"chat".equals(page) || !peerUid.equals(activePeerUid)) return;
            peerProfile = profile;
            if (profile != null) { name.setText(profile.displayNameOrUsername()); setAvatar(avatar, profile, 38); }
        });
        peerOnline = false; peerLastSeenVisible = false; peerLastSeen = 0; peerTyping = false; peerReadAt = 0;
        attachMessagesListener(chatId, peerUid);
        attachTypingListener(chatId, peerUid);
        attachPeerPresence(chatId, peerUid);
        attachReadReceiptListener(chatId, peerUid);
        attachReactionsListener(chatId);
        if (!presenceManager.isGhostMode()) chatRepository.markRead(chatId, uid);
    }

    private final Runnable clearTyping = () -> {
        if (activeChatId != null && uid != null) chatRepository.setTyping(activeChatId, uid, false);
    };

    private void attachMessagesListener(String chatId, String peerUid) {
        messagesListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                List<ChatMessage> messages = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    ChatMessage message = child.getValue(ChatMessage.class);
                    if (message != null) { message.id = child.getKey(); messages.add(message); }
                }
                renderMessages(messages, peerUid);
                if (!presenceManager.isGhostMode()) chatRepository.markRead(chatId, uid);
            }
            @Override public void onCancelled(DatabaseError error) { toast("Не удалось открыть сообщения: " + error.getMessage()); }
        };
        chatRepository.messages(chatId).addValueEventListener(messagesListener);
    }

    private void attachTypingListener(String chatId, String peerUid) {
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!"chat".equals(page) || !peerUid.equals(activePeerUid) || chatStatus == null) return;
                peerTyping = Boolean.TRUE.equals(snapshot.child("typing").getValue(Boolean.class));
                long at = number(snapshot.child("at").getValue());
                if (System.currentTimeMillis() - at > 5500) peerTyping = false;
                updateChatStatus();
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        chatRepository.typing(chatId).child(peerUid).addValueEventListener(typingListener);
    }

    private void attachPeerPresence(String chatId, String peerUid) {
        peerPresenceRef = chatRepository.presence(peerUid).child("online");
        peerPresenceListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!"chat".equals(page) || !chatId.equals(activeChatId)) return;
                peerOnline = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                updateChatStatus();
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        peerPresenceRef.addValueEventListener(peerPresenceListener);
        peerLastSeenRef = chatRepository.presence(peerUid).child("lastSeen");
        peerLastSeenListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!"chat".equals(page) || !chatId.equals(activeChatId)) return;
                peerLastSeenVisible = true; peerLastSeen = number(snapshot.getValue()); updateChatStatus();
            }
            @Override public void onCancelled(DatabaseError error) {
                if ("chat".equals(page) && chatId.equals(activeChatId)) { peerLastSeenVisible = false; peerLastSeen = 0; updateChatStatus(); }
            }
        };
        peerLastSeenRef.addValueEventListener(peerLastSeenListener);
    }

    private void updateChatStatus() {
        if (chatStatus == null) return;
        if (peerTyping) { chatStatus.setText("печатает…"); chatStatus.setTextColor(Ui.BLUE); }
        else if (peerOnline) { chatStatus.setText("в сети"); chatStatus.setTextColor(Ui.GREEN); }
        else if (peerLastSeenVisible && peerLastSeen > 0) {
            String date = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(peerLastSeen));
            chatStatus.setText("был(а) в сети в " + date); chatStatus.setTextColor(Ui.SECONDARY);
        } else { chatStatus.setText("не в сети"); chatStatus.setTextColor(Ui.SECONDARY); }
    }

    private void attachReadReceiptListener(String chatId, String peerUid) {
        receiptRef = chatRepository.receipts(chatId).child(peerUid);
        receiptListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!"chat".equals(page) || !chatId.equals(activeChatId)) return;
                peerReadAt = number(snapshot.getValue());
                if (messagesListener != null) chatRepository.messages(chatId).get().addOnSuccessListener(data -> {
                    if ("chat".equals(page) && chatId.equals(activeChatId)) renderMessagesFromSnapshot(data, peerUid);
                });
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        receiptRef.addValueEventListener(receiptListener);
    }

    private void renderMessagesFromSnapshot(DataSnapshot snapshot, String peerUid) {
        List<ChatMessage> messages = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            ChatMessage message = child.getValue(ChatMessage.class);
            if (message != null) { message.id = child.getKey(); messages.add(message); }
        }
        renderMessages(messages, peerUid);
    }

    private void attachReactionsListener(String chatId) {
        reactionsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                messageReactions.clear();
                for (DataSnapshot message : snapshot.getChildren()) {
                    Set<String> users = new HashSet<>();
                    for (DataSnapshot reaction : message.getChildren()) if ("heart".equals(reaction.getValue(String.class))) users.add(reaction.getKey());
                    if (!users.isEmpty()) messageReactions.put(message.getKey(), users);
                }
                // Message rows redraw on the next RTDB message event; reactions are also toggled optimistically.
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        chatRepository.root().child("chats").child(chatId).child("reactions").addValueEventListener(reactionsListener);
    }

    private void renderMessages(List<ChatMessage> messages, String peerUid) {
        if (!"chat".equals(page) || messageColumn == null || activeChatId == null) return;
        final String renderChatId = activeChatId;
        final SignalE2ee activeCrypto = crypto;
        final LocalMessageCache activeCache = messageCache;
        messageColumn.removeAllViews();
        for (ChatMessage message : messages) {
            String payload = activeCache == null || message.id == null ? null : activeCache.get(renderChatId, message.id);
            final String key = renderChatId + ":" + message.id;
            if (payload == null && decryptFailures.contains(key)) payload = "{\"kind\":\"decrypt_error\"}";
            if (payload == null && !uid.equals(message.senderUid) && message.cipher != null && decrypting.add(key)) {
                cryptoQueue.execute(() -> {
                    try {
                        byte[] plain = activeCrypto.decrypt(peerUid, message.signalType, message.cipher);
                        activeCache.put(renderChatId, message.id, new String(plain, StandardCharsets.UTF_8));
                        runOnUiThread(() -> { if (renderChatId.equals(activeChatId)) renderMessages(messages, peerUid); });
                    } catch (Exception e) {
                        decryptFailures.add(key);
                        runOnUiThread(() -> { if (renderChatId.equals(activeChatId) && messageColumn != null) renderMessages(messages, peerUid); });
                    } finally { decrypting.remove(key); }
                });
            }
            messageColumn.addView(messageRow(message, payload));
        }
        ui.post(() -> { if (renderChatId.equals(activeChatId) && messageScroll != null) messageScroll.fullScroll(View.FOCUS_DOWN); });
    }

    private View messageRow(ChatMessage message, String payload) {
        boolean outgoing = uid.equals(message.senderUid);
        LinearLayout outer = horizontal(Ui.BLACK);
        outer.setGravity(outgoing ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout bubble = vertical(outgoing ? 0xff2c2c2e : 0xff1c1c1e);
        bubble.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 6));
        GradientDrawable shape = Ui.shape(outgoing ? 0xff2c2c2e : 0xff1c1c1e, this, 18);
        if (outgoing) shape.setCornerRadii(new float[]{18,18,18,18,4,4,18,18});
        else shape.setCornerRadii(new float[]{18,18,18,18,18,18,4,4});
        bubble.setBackground(shape);
        JSONObject payloadObject = null;
        String kind = "text";
        if (payload != null) {
            try { payloadObject = new JSONObject(payload); kind = payloadObject.optString("kind", "text"); }
            catch (Exception ignored) { }
        }
        boolean media = payloadObject != null && ("image".equals(kind) || "collage".equals(kind) || "video".equals(kind) || "audio".equals(kind) || "voice".equals(kind));
        String text = payload == null ? (decryptFailures.contains(activeChatId + ":" + message.id) ? "Не удалось расшифровать" : (outgoing ? "Вы отправили сообщение" : "Расшифровка…")) : textFromPayload(payload);
        TextView body = Ui.text(this, text, textSize(), Ui.WHITE, isTextBold());
        body.setTypeface(Typeface.create(Typeface.DEFAULT, textTypefaceStyle()));
        body.setTextIsSelectable(true);
        body.setMaxWidth(Ui.dp(this, 292));
        if (media) body.setVisibility(View.GONE);
        bubble.addView(body);
        if (media) addMediaPreview(bubble, payloadObject, kind);
        LinearLayout footer = horizontal(outgoing ? 0xff2c2c2e : 0xff1c1c1e);
        footer.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (message.edited) {
            TextView edited = Ui.text(this, "изменено", 10, Ui.SECONDARY, false);
            LinearLayout.LayoutParams edLp = new LinearLayout.LayoutParams(-2, -2); edLp.rightMargin = Ui.dp(this, 4); footer.addView(edited, edLp);
        }
        TextView time = Ui.text(this, message.sentAt <= 0 ? "" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(message.sentAt)), 10, Ui.SECONDARY, false);
        footer.addView(time);
        if (outgoing) {
            boolean read = message.sentAt > 0 && peerReadAt >= message.sentAt;
            TextView check = Ui.text(this, read ? "  ✓✓" : "  ✓", 11, read ? Ui.BLUE : Ui.SECONDARY, true);
            footer.addView(check);
        }
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(-1, -2); footLp.topMargin = Ui.dp(this, 4); bubble.addView(footer, footLp);
        Set<String> reactions = messageReactions.get(message.id);
        if (reactions != null && !reactions.isEmpty()) {
            TextView heart = Ui.text(this, "❤️  " + reactions.size(), 11, Ui.WHITE, true);
            heart.setPadding(Ui.dp(this, 7), Ui.dp(this, 3), Ui.dp(this, 7), Ui.dp(this, 3));
            heart.setBackground(Ui.shape(0xff242426, this, 12));
            LinearLayout.LayoutParams rp = wrap(); rp.topMargin = Ui.dp(this, 3); bubble.addView(heart, rp);
        }
        LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(-2, -2);
        bubbleLp.gravity = outgoing ? Gravity.RIGHT : Gravity.LEFT;
        bubbleLp.leftMargin = Ui.dp(this, outgoing ? 52 : 0);
        bubbleLp.rightMargin = Ui.dp(this, outgoing ? 0 : 52);
        bubble.setLayoutParams(bubbleLp);
        outer.addView(bubble);
        outer.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        final long[] lastTap = {0};
        bubble.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastTap[0] < 320) toggleHeart(message);
                lastTap[0] = now;
            }
            return false;
        });
        bubble.setOnLongClickListener(v -> { showMessageMenu(v, message, payload); return true; });
        bubble.setOnClickListener(v -> openPayload(payload));
        return outer;
    }

    private void addMediaPreview(LinearLayout bubble, JSONObject payload, String kind) {
        if ("image".equals(kind)) {
            addImageThumb(bubble, payload.optString("data", ""), 244, 190);
        } else if ("collage".equals(kind)) {
            org.json.JSONArray items = payload.optJSONArray("items");
            if (items == null) return;
            LinearLayout grid = vertical(0xff1c1c1e);
            for (int i = 0; i < items.length(); i += 2) {
                LinearLayout row = horizontal(0xff1c1c1e);
                for (int j = i; j < Math.min(i + 2, items.length()); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item == null) continue;
                    ImageView image = makeThumb(item.optString("data", ""));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 132), 1);
                    lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 2));
                    row.addView(image, lp);
                    String data = item.optString("data", "");
                    image.setOnClickListener(v -> showImageDialog(data));
                }
                grid.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, 136)));
            }
            bubble.addView(grid, new LinearLayout.LayoutParams(Ui.dp(this, 252), -2));
        } else if ("video".equals(kind)) {
            TextView card = Ui.text(this, "▶   Видео · нажмите для просмотра", 14, Ui.WHITE, true);
            card.setGravity(Gravity.CENTER);
            card.setBackground(Ui.shape(0xff111113, this, 12));
            bubble.addView(card, new LinearLayout.LayoutParams(Ui.dp(this, 244), Ui.dp(this, 150)));
            card.setOnClickListener(v -> showVideoDialog(payload.optString("data", ""), payload.optString("mime", "video/mp4")));
        } else if ("voice".equals(kind) || "audio".equals(kind)) {
            LinearLayout player = horizontal(0xff242426);
            player.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
            TextView play = Ui.text(this, "▶", 19, Ui.BLUE, true); play.setGravity(Gravity.CENTER);
            play.setBackground(Ui.shape(0xff303034, this, 22));
            player.addView(play, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            LinearLayout details = vertical(0xff242426);
            TextView label = Ui.text(this, "voice".equals(kind) ? "Голосовое" : "Аудиофайл", 13, Ui.WHITE, true);
            TextView track = Ui.text(this, "━━━━━━●━━━━━━", 11, Ui.BLUE, false);
            details.addView(label); LinearLayout.LayoutParams trackLp = wrap(); trackLp.topMargin = Ui.dp(this, 5); details.addView(track, trackLp);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(0, -2, 1); detailLp.leftMargin = Ui.dp(this, 10); player.addView(details, detailLp);
            bubble.addView(player, new LinearLayout.LayoutParams(Ui.dp(this, 250), -2));
            player.setOnClickListener(v -> playAudio(payload.optString("data", ""), payload.optString("mime", "audio/mp4")));
        }
    }

    private void addImageThumb(LinearLayout target, String encoded, int widthDp, int heightDp) {
        ImageView image = makeThumb(encoded);
        target.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, widthDp), Ui.dp(this, heightDp)));
        image.setOnClickListener(v -> showImageDialog(encoded));
    }

    private ImageView makeThumb(String encoded) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(Ui.shape(0xff111113, this, 9));
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap != null) image.setImageBitmap(bitmap);
        } catch (Exception ignored) { }
        return image;
    }

    private void openPayload(String payload) {
        if (payload == null) return;
        try {
            JSONObject object = new JSONObject(payload);
            String kind = object.optString("kind", "text");
            if ("image".equals(kind)) showImageDialog(object.optString("data", ""));
            else if ("video".equals(kind)) showVideoDialog(object.optString("data", ""), object.optString("mime", "video/mp4"));
            else if ("audio".equals(kind) || "voice".equals(kind)) playAudio(object.optString("data", ""), object.optString("mime", "audio/mp4"));
            else if ("collage".equals(kind)) {
                org.json.JSONArray items = object.optJSONArray("items");
                if (items != null && items.length() > 0) showImageDialog(items.optJSONObject(0).optString("data", ""));
            }
        } catch (Exception ignored) { }
    }

    private void showVideoDialog(String encoded, String mime) {
        dataQueue.execute(() -> {
            try {
                byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
                java.io.File file = new java.io.File(getCacheDir(), "tsuyu-video-" + System.currentTimeMillis() + (mime.contains("webm") ? ".webm" : ".mp4"));
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) { output.write(bytes); }
                runOnUiThread(() -> {
                    android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                    android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
                    frame.setBackgroundColor(Ui.BLACK);
                    android.widget.VideoView video = new android.widget.VideoView(this);
                    frame.addView(video, new android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                    android.widget.MediaController controls = new android.widget.MediaController(this);
                    controls.setAnchorView(video); video.setMediaController(controls); video.setVideoURI(Uri.fromFile(file));
                    dialog.setContentView(frame); dialog.show();
                    if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
                    video.setOnPreparedListener(mp -> video.start());
                });
            } catch (Exception e) { runOnUiThread(() -> toast("Не удалось открыть видео.")); }
        });
    }

    private void playAudio(String encoded, String mime) {
        dataQueue.execute(() -> {
            try {
                byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
                String ext = mime.contains("mpeg") ? ".mp3" : mime.contains("ogg") ? ".ogg" : ".m4a";
                java.io.File file = new java.io.File(getCacheDir(), "tsuyu-audio-" + System.currentTimeMillis() + ext);
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) { output.write(bytes); }
                runOnUiThread(() -> {
                    try {
                        if (audioPlayer != null) { audioPlayer.release(); audioPlayer = null; }
                        audioPlayer = new android.media.MediaPlayer();
                        audioPlayer.setDataSource(file.getAbsolutePath());
                        audioPlayer.setOnPreparedListener(android.media.MediaPlayer::start);
                        audioPlayer.setOnCompletionListener(mp -> { mp.release(); audioPlayer = null; });
                        audioPlayer.prepareAsync();
                    } catch (Exception e) { toast("Не удалось воспроизвести аудио."); }
                });
            } catch (Exception e) { runOnUiThread(() -> toast("Файл повреждён.")); }
        });
    }

    private android.media.MediaPlayer audioPlayer;

    private void showMessageMenu(View anchor, ChatMessage message, String payload) {
        PopupMenu popup = new PopupMenu(this, anchor);
        MenuItem heart = popup.getMenu().add("❤️  Реакция");
        MenuItem reply = popup.getMenu().add("Ответить");
        MenuItem forward = popup.getMenu().add("Переслать");
        MenuItem edit = null;
        if (uid.equals(message.senderUid)) edit = popup.getMenu().add("Редактировать");
        MenuItem delete = popup.getMenu().add("Удалить у всех");
        MenuItem editItem = edit;
        popup.setOnMenuItemClickListener(item -> {
            if (item == heart) toggleHeart(message);
            else if (item == reply) {
                replyToId = message.id;
                replyToText = payload == null ? "Сообщение" : textFromPayload(payload);
                if (replyBanner != null) { replyBanner.setText("↪  " + replyToText); replyBanner.setVisibility(View.VISIBLE); }
                if (composer != null) composer.requestFocus();
            } else if (item == forward) forwardMessage(payload);
            else if (item == editItem) editMessage(message, payload);
            else if (item == delete) confirmDelete(message);
            return true;
        });
        popup.show();
    }

    private void toggleHeart(ChatMessage message) {
        if (message.id == null || activeChatId == null) return;
        Set<String> current = messageReactions.get(message.id);
        boolean enabled = current == null || !current.contains(uid);
        chatRepository.setReaction(activeChatId, message.id, uid, enabled);
        if (current == null) current = new HashSet<>();
        if (enabled) current.add(uid); else current.remove(uid);
        if (current.isEmpty()) messageReactions.remove(message.id); else messageReactions.put(message.id, current);
        if (messagesListener != null) chatRepository.messages(activeChatId).get().addOnSuccessListener(s -> {
            List<ChatMessage> list = new ArrayList<>(); for (DataSnapshot child : s.getChildren()) { ChatMessage m = child.getValue(ChatMessage.class); if (m != null) { m.id = child.getKey(); list.add(m); } }
            renderMessages(list, activePeerUid);
        });
    }

    private void editMessage(ChatMessage message, String payload) {
        if (payload == null) { toast("Сообщение ещё не расшифровано."); return; }
        String old;
        try { old = new JSONObject(payload).optString("text", ""); } catch (Exception e) { toast("Можно редактировать только текст."); return; }
        EditText edit = new EditText(this); edit.setText(old); edit.setTextColor(Ui.WHITE); edit.setHintTextColor(Ui.SECONDARY); edit.setBackground(Ui.shape(Ui.INPUT, this, 12)); edit.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        new AlertDialog.Builder(this).setTitle("Редактировать сообщение").setView(edit)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (dialog, which) -> {
                    String text = edit.getText().toString().trim(); if (text.isEmpty()) return;
                    chatRepository.editText(activeChatId, message.id, uid, activePeerUid, crypto, text, new ChatRepository.Result<Void>() {
                        @Override public void success(Void ignored) {
                            try { JSONObject json = new JSONObject().put("version", 1).put("kind", "text").put("text", text); messageCache.put(activeChatId, message.id, json.toString()); }
                            catch (Exception ignored2) { }
                        }
                        @Override public void error(String error) { toast(error); }
                    });
                }).show();
    }

    private void confirmDelete(ChatMessage message) {
        new AlertDialog.Builder(this).setTitle("Удалить сообщение?")
                .setMessage("Сообщение будет удалено у обоих участников.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> chatRepository.deleteMessage(activeChatId, message.id, new ChatRepository.Result<Void>() {
                    @Override public void success(Void value) { toast("Сообщение удалено."); }
                    @Override public void error(String error) { toast(error); }
                })).show();
    }

    private void forwardMessage(String payload) {
        if (payload == null) { toast("Сообщение ещё не расшифровано."); return; }
        String text = textFromPayload(payload);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Tsuyu", text));
        toast("Текст скопирован. Выберите диалог и вставьте его.");
    }

    private void sendCurrentText() {
        if (composer == null || activeChatId == null || activePeerUid == null) return;
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) return;
        composer.setEnabled(false);
        String replyId = replyToId;
        try {
            JSONObject payload = new JSONObject().put("version", 1).put("kind", "text").put("text", text);
            if (replyId != null) payload.put("replyTo", replyId);
            chatRepository.sendPayload(activeChatId, uid, activePeerUid, crypto,
                    payload.toString().getBytes(StandardCharsets.UTF_8), new ChatRepository.Result<String>() {
                        @Override public void success(String messageId) {
                            if (composer != null) { composer.setText(""); composer.setEnabled(true); }
                            if (messageCache != null) messageCache.put(activeChatId, messageId, payload.toString());
                            replyToId = null; replyToText = null;
                            if (replyBanner != null) replyBanner.setVisibility(View.GONE);
                            chatRepository.setTyping(activeChatId, uid, false);
                        }
                        @Override public void error(String error) { if (composer != null) composer.setEnabled(true); toast(error); }
                    });
        } catch (Exception e) { composer.setEnabled(true); toast("Не удалось подготовить сообщение."); }
    }

    private void showChatMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        MenuItem profile = menu.getMenu().add("Профиль собеседника");
        MenuItem export = menu.getMenu().add("Экспортировать текст переписки");
        menu.getMenu().add("Управление ключами");
        menu.setOnMenuItemClickListener(item -> {
            if (item == profile) showPeerProfile();
            else if (item == export) exportCurrentChat();
            else showSettings();
            return true;
        });
        menu.show();
    }

    private void showPeerProfile() {
        if (peerProfile == null) return;
        LinearLayout content = vertical(Ui.SURFACE); content.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20));
        ImageView image = avatar(peerProfile, 90); LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 90)); imgLp.gravity = Gravity.CENTER_HORIZONTAL; content.addView(image, imgLp);
        TextView name = Ui.text(this, peerProfile.displayNameOrUsername(), 19, Ui.WHITE, true); name.setGravity(Gravity.CENTER); name.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4)); content.addView(name, matchWrap());
        TextView username = Ui.text(this, "@" + peerProfile.username, 13, Ui.BLUE, false); username.setGravity(Gravity.CENTER); content.addView(username, matchWrap());
        TextView about = Ui.text(this, peerProfile.about == null || peerProfile.about.isEmpty() ? "Нет описания" : peerProfile.about, 14, 0xffcccccc, false); about.setGravity(Gravity.CENTER); about.setPadding(0, Ui.dp(this, 12), 0, 0); content.addView(about, matchWrap());
        TextView fingerprint = Ui.text(this, "Отпечаток Signal: загружается…", 10, Ui.SECONDARY, false); fingerprint.setTextIsSelectable(true); fingerprint.setPadding(0, Ui.dp(this, 12), 0, 0); content.addView(fingerprint, matchWrap());
        TextView trustKey = Ui.button(this, "Принять ключ после сверки", Ui.INPUT, Ui.WHITE, 12); LinearLayout.LayoutParams trustLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)); trustLp.topMargin = Ui.dp(this, 8); content.addView(trustKey, trustLp);
        final Map<String, Object>[] peerBundle = new Map[]{null};
        String profilePeerUid = peerProfile.uid;
        SignalE2ee accountCrypto = crypto;
        database.getReference("users").child(profilePeerUid).child("keys").child("1").get().addOnSuccessListener(snapshot -> {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> bundle = (Map<String, Object>) snapshot.getValue();
                peerBundle[0] = bundle;
                fingerprint.setText(bundle == null ? "У пользователя ещё нет публичного ключа" : SignalE2ee.identityFingerprint(bundle));
            } catch (Exception e) { fingerprint.setText("Не удалось проверить отпечаток ключа"); }
        });
        trustKey.setOnClickListener(v -> {
            if (peerBundle[0] == null || accountCrypto == null) { toast("Публичный ключ пока недоступен."); return; }
            String shownFingerprint;
            try { shownFingerprint = SignalE2ee.identityFingerprint(peerBundle[0]); }
            catch (Exception e) { toast("Некорректный публичный ключ."); return; }
            new AlertDialog.Builder(this).setTitle("Проверить ключ")
                    .setMessage("Сравните этот SHA-256 отпечаток с собеседником по независимому каналу:\n\n" + shownFingerprint + "\n\nДоверять ключу только после совпадения.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Доверять", (dialog, which) -> cryptoQueue.execute(() -> {
                        try { accountCrypto.trustRemoteIdentity(profilePeerUid, peerBundle[0]); runOnUiThread(() -> toast("Ключ принят, Ratchet-сеанс сброшен.")); }
                        catch (Exception e) { runOnUiThread(() -> toast("Не удалось принять ключ.")); }
                    })).show();
        });
        TextView contactButton = Ui.button(this, "Добавить в контакты", Ui.INPUT, Ui.WHITE, 13);
        LinearLayout.LayoutParams contactLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)); contactLp.topMargin = Ui.dp(this, 16); content.addView(contactButton, contactLp);
        TextView accessButton = Ui.button(this, "Разрешить доступ по списку", Ui.INPUT, Ui.WHITE, 13);
        LinearLayout.LayoutParams accessLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)); accessLp.topMargin = Ui.dp(this, 8); content.addView(accessButton, accessLp);
        final boolean[] isContact = {false}; final boolean[] isAllowed = {false};
        DatabaseReference contactRef = database.getReference("users").child(uid).child("contacts").child(peerProfile.uid);
        DatabaseReference allowRef = database.getReference("users").child(uid).child("publicSettings").child("allowList").child(peerProfile.uid);
        contactRef.get().addOnSuccessListener(s -> { isContact[0] = Boolean.TRUE.equals(s.getValue(Boolean.class)); contactButton.setText(isContact[0] ? "Удалить из контактов" : "Добавить в контакты"); });
        allowRef.get().addOnSuccessListener(s -> { isAllowed[0] = Boolean.TRUE.equals(s.getValue(Boolean.class)); accessButton.setText(isAllowed[0] ? "Отозвать доступ по списку" : "Разрешить доступ по списку"); });
        contactButton.setOnClickListener(v -> contactRef.setValue(isContact[0] ? null : true).addOnSuccessListener(unused -> { isContact[0] = !isContact[0]; contactButton.setText(isContact[0] ? "Удалить из контактов" : "Добавить в контакты"); }).addOnFailureListener(e -> toast("Не удалось обновить контакты.")));
        accessButton.setOnClickListener(v -> allowRef.setValue(isAllowed[0] ? null : true).addOnSuccessListener(unused -> { isAllowed[0] = !isAllowed[0]; accessButton.setText(isAllowed[0] ? "Отозвать доступ по списку" : "Разрешить доступ по списку"); }).addOnFailureListener(e -> toast("Не удалось обновить список доступа.")));
        new AlertDialog.Builder(this).setView(content).setPositiveButton("Закрыть", null).show();
    }

    private void exportCurrentChat() {
        if (activeChatId == null) return;
        chatRepository.messages(activeChatId).get().addOnSuccessListener(snapshot -> {
            StringBuilder text = new StringBuilder("Tsuyu — расшифрованный экспорт\n");
            for (DataSnapshot child : snapshot.getChildren()) {
                ChatMessage message = child.getValue(ChatMessage.class);
                if (message == null || message.id == null) continue;
                String payload = messageCache.get(activeChatId, message.id);
                text.append(message.senderUid.equals(uid) ? "Вы" : (peerProfile == null ? "Собеседник" : peerProfile.displayNameOrUsername()))
                        .append(" · ").append(message.sentAt <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(message.sentAt))).append("\n")
                        .append(payload == null ? "[Не расшифровано на этом устройстве]" : textFromPayload(payload)).append("\n\n");
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Tsuyu-chat.txt");
            try { startActivityForResult(intent, 701); } catch (Exception e) { toast("Не удалось открыть проводник."); }
            exportText = text.toString();
        }).addOnFailureListener(e -> toast("Не удалось загрузить переписку."));
    }

    private String exportText;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 701 && resultCode == RESULT_OK && data != null && data.getData() != null && exportText != null) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) out.write(exportText.getBytes(StandardCharsets.UTF_8));
                toast("Экспорт сохранён.");
            } catch (Exception e) { toast("Не удалось записать файл."); }
        }
    }

    private void showAttachmentMenu() {
        new AlertDialog.Builder(this).setTitle("Прикрепить")
                .setItems(new String[]{"Фото", "Несколько фото", "Видео", "Аудиофайл", "Записать голосовое"}, (dialog, which) -> {
                    if (which == 0) { pendingMediaKind = "image"; avatarPicker.launch("image/*"); }
                    else if (which == 1) multiPhotoPicker.launch(new androidx.activity.result.PickVisualMediaRequest.Builder().build());
                    else if (which == 2) { pendingMediaKind = "video"; documentPicker.launch(new String[]{"video/*"}); }
                    else if (which == 3) { pendingMediaKind = "audio"; documentPicker.launch(new String[]{"audio/*"}); }
                    else toggleVoiceRecord();
                }).show();
    }

    private void toggleVoiceRecord() {
        if (voiceRecorder != null) {
            try {
                voiceRecorder.stop();
            } catch (RuntimeException ignored) { }
            voiceRecorder.release(); voiceRecorder = null;
            if (voiceFile != null && voiceFile.exists() && voiceFile.length() > 0) sendVoiceFile(voiceFile);
            else toast("Запись слишком короткая.");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 204);
            return;
        }
        try {
            voiceFile = new java.io.File(getCacheDir(), "tsuyu-voice-" + System.currentTimeMillis() + ".m4a");
            voiceRecorder = Build.VERSION.SDK_INT >= 31 ? new android.media.MediaRecorder(this) : new android.media.MediaRecorder();
            voiceRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            voiceRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            voiceRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setAudioSamplingRate(44100);
            voiceRecorder.setAudioEncodingBitRate(96000);
            voiceRecorder.setMaxDuration(180000);
            voiceRecorder.setOutputFile(voiceFile.getAbsolutePath());
            voiceRecorder.prepare(); voiceRecorder.start();
            toast("Запись идёт. Нажмите «+» → «Записать голосовое» ещё раз, чтобы отправить.");
        } catch (Exception e) {
            if (voiceRecorder != null) { voiceRecorder.release(); voiceRecorder = null; }
            toast("Не удалось начать запись. Проверьте разрешение микрофона.");
        }
    }

    private void sendVoiceFile(java.io.File file) {
        if (activeChatId == null || activePeerUid == null) return;
        String chatId = activeChatId; String peerUid = activePeerUid; String fromUid = uid;
        SignalE2ee activeCrypto = crypto; LocalMessageCache activeCache = messageCache;
        dataQueue.execute(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                JSONObject payload = new JSONObject().put("version", 1).put("kind", "voice").put("mime", "audio/mp4").put("data", base64);
                chatRepository.sendPayload(chatId, fromUid, peerUid, activeCrypto, payload.toString().getBytes(StandardCharsets.UTF_8), new ChatRepository.Result<String>() {
                    @Override public void success(String id) { if (activeCache != null) activeCache.put(chatId, id, payload.toString()); file.delete(); }
                    @Override public void error(String error) { toast(error); }
                });
            } catch (Exception e) { runOnUiThread(() -> toast("Не удалось подготовить голосовое сообщение.")); }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 204 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) toggleVoiceRecord();
        else if (requestCode == 204) toast("Для записи нужно разрешение микрофона.");
    }

    private void sendPickedMedia(Uri uri, String kind) {
        if (activeChatId == null || activePeerUid == null) return;
        String chatId = activeChatId; String peerUid = activePeerUid; String fromUid = uid;
        SignalE2ee activeCrypto = crypto; LocalMessageCache activeCache = messageCache;
        dataQueue.execute(() -> {
            try {
                String base64;
                String mime = getContentResolver().getType(uri);
                if ("image".equals(kind)) base64 = imageToBase64(uri, 1440, 950_000);
                else base64 = readBase64(uri, 3_000_000);
                JSONObject payload = new JSONObject().put("version", 1).put("kind", kind).put("mime", mime == null ? "application/octet-stream" : mime).put("data", base64);
                chatRepository.sendPayload(chatId, fromUid, peerUid, activeCrypto, payload.toString().getBytes(StandardCharsets.UTF_8), new ChatRepository.Result<String>() {
                    @Override public void success(String id) { if (activeCache != null) activeCache.put(chatId, id, payload.toString()); toast("Отправлено."); }
                    @Override public void error(String error) { toast(error); }
                });
            } catch (Exception e) { runOnUiThread(() -> toast("Файл слишком большой или не читается.")); }
        });
    }

    private void sendPickedPhotos(List<Uri> uris) {
        if (activeChatId == null || uris.isEmpty()) return;
        String chatId = activeChatId; String peerUid = activePeerUid; String fromUid = uid;
        SignalE2ee activeCrypto = crypto; LocalMessageCache activeCache = messageCache;
        dataQueue.execute(() -> {
            try {
                org.json.JSONArray items = new org.json.JSONArray();
                for (int i = 0; i < Math.min(6, uris.size()); i++) {
                    String data = imageToBase64(uris.get(i), 1200, 360_000);
                    items.put(new JSONObject().put("data", data).put("mime", "image/jpeg"));
                }
                JSONObject payload = new JSONObject().put("version", 1).put("kind", "collage").put("items", items);
                chatRepository.sendPayload(chatId, fromUid, peerUid, activeCrypto,
                        payload.toString().getBytes(StandardCharsets.UTF_8), new ChatRepository.Result<String>() {
                            @Override public void success(String id) { if (activeCache != null) activeCache.put(chatId, id, payload.toString()); }
                            @Override public void error(String error) { toast(error); }
                        });
            } catch (Exception e) { runOnUiThread(() -> toast("Не удалось подготовить коллаж.")); }
        });
    }

    private void showImageDialog(String imageBase64) {
        try {
            byte[] bytes = Base64.decode(imageBase64, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return;
            android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            FrameLayout frame = new FrameLayout(this); frame.setBackgroundColor(Ui.BLACK);
            ZoomImageView image = new ZoomImageView(this); image.setImageBitmap(bitmap);
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            TextView close = Ui.iconButton(this, "×", Ui.WHITE);
            FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.TOP | Gravity.RIGHT);
            closeLp.setMargins(0, Ui.dp(this, 18), Ui.dp(this, 16), 0); frame.addView(close, closeLp); close.setOnClickListener(v -> dialog.dismiss());
            TextView download = Ui.button(this, "Скачать", 0xff1c1c1e, Ui.WHITE, 20);
            FrameLayout.LayoutParams downloadLp = new FrameLayout.LayoutParams(-2, Ui.dp(this, 44), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            downloadLp.bottomMargin = Ui.dp(this, 26); frame.addView(download, downloadLp); download.setOnClickListener(v -> saveImage(bitmap));
            dialog.setContentView(frame); dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
        } catch (Exception ignored) { toast("Не удалось открыть изображение."); }
    }

    private void saveImage(Bitmap bitmap) {
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Tsuyu_" + System.currentTimeMillis() + ".jpg");
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Tsuyu");
            Uri uri = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException();
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output); }
            toast("Фото сохранено.");
        } catch (Exception e) { toast("Не удалось сохранить фото."); }
    }

    private void showSettings() {
        if (uid == null) return;
        markNoActiveChat();
        page = "settings";
        LinearLayout root = vertical(Ui.BLACK);
        addTopBar(root, "Настройки", "‹", this::showHome, null, null);
        ScrollView scroll = new ScrollView(this); LinearLayout body = vertical(Ui.BLACK); body.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 24));
        LinearLayout syncRow = horizontal(Ui.SURFACE); syncRow.setPadding(Ui.dp(this, 15), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9)); syncRow.setBackground(Ui.bordered(Ui.SURFACE, 0xff1c1c1c, this, 14, 1));
        LinearLayout syncText = vertical(Ui.SURFACE); syncText.addView(Ui.text(this, "Фоновое соединение", 15, Ui.WHITE, true)); TextView syncHint = Ui.text(this, "RTDB-уведомления при свёрнутом приложении", 11, Ui.SECONDARY, false); LinearLayout.LayoutParams syncHintLp = new LinearLayout.LayoutParams(-1, -2); syncHintLp.topMargin = Ui.dp(this, 4); syncText.addView(syncHint, syncHintLp); syncRow.addView(syncText, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Switch syncSwitch = new android.widget.Switch(this); syncSwitch.setChecked(getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("background_sync", true)); syncRow.addView(syncSwitch);
        LinearLayout.LayoutParams syncRowLp = new LinearLayout.LayoutParams(-1, -2); syncRowLp.bottomMargin = Ui.dp(this, 9); body.addView(syncRow, syncRowLp);
        syncSwitch.setOnCheckedChangeListener((button, checked) -> { getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().putBoolean("background_sync", checked).apply(); if (checked) startBackgroundSync(); else stopBackgroundSync(); });
        body.addView(settingRow("Профиль", "Имя, @юзернейм, описание и фото", () -> showProfileEditor(false)));
        body.addView(settingRow("Конфиденциальность", "Время захода и отметки прочтения", this::showPrivacySettings));
        body.addView(settingRow("Кастомизация", "Размер и начертание текста", this::showCustomization));
        body.addView(settingRow("Уведомления", "Звук, вибрация и разрешение Android", this::showNotificationSettings));
        body.addView(settingRow("Ключи шифрования", "Идентичность и публичные prekey", this::showKeySettings));
        TextView logout = Ui.button(this, "Выйти из аккаунта", 0xff2c1616, Ui.RED, 14);
        LinearLayout.LayoutParams logoutLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)); logoutLp.topMargin = Ui.dp(this, 18); body.addView(logout, logoutLp);
        logout.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Выйти?").setMessage("Локальная криптографическая идентичность сохранится на этом устройстве.").setNegativeButton("Отмена", null).setPositiveButton("Выйти", (d, w) -> { presenceManager.signOut(); auth.signOut(); }).show());
        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(bottomBar("Настройки")); setContentView(root);
    }

    private View settingRow(String title, String description, Runnable action) {
        LinearLayout row = vertical(Ui.SURFACE); row.setPadding(Ui.dp(this, 15), Ui.dp(this, 14), Ui.dp(this, 15), Ui.dp(this, 14)); row.setBackground(Ui.bordered(Ui.SURFACE, 0xff1c1c1c, this, 14, 1));
        TextView main = Ui.text(this, title, 15, Ui.WHITE, true); row.addView(main);
        TextView sub = Ui.text(this, description, 12, Ui.SECONDARY, false); LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2); subLp.topMargin = Ui.dp(this, 5); row.addView(sub, subLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = Ui.dp(this, 9); row.setLayoutParams(lp); row.setOnClickListener(v -> action.run()); return row;
    }

    private void showPrivacySettings() {
        String[] keys = {"messages", "lastSeen", "photo", "about", "displayName"};
        Map<String, String> initial = new HashMap<>();
        for (String key : keys) initial.put(key, getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getString("privacy_" + key, "Все"));
        DatabaseReference settingsRef = database.getReference("users").child(uid).child("publicSettings");
        settingsRef.child("privacy").get().addOnSuccessListener(privacySnapshot -> {
            for (DataSnapshot child : privacySnapshot.getChildren()) {
                String value = child.getValue(String.class); if (value != null) initial.put(child.getKey(), value);
            }
            settingsRef.child("messages").get().addOnSuccessListener(messagesSnapshot -> {
                String value = messagesSnapshot.getValue(String.class); if (value != null) initial.put("messages", value);
                showPrivacySettingsLoaded(initial);
            }).addOnFailureListener(e -> showPrivacySettingsLoaded(initial));
        }).addOnFailureListener(e -> showPrivacySettingsLoaded(initial));
    }

    private void showPrivacySettingsLoaded(Map<String, String> initial) {
        LinearLayout body = vertical(Ui.SURFACE); body.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        android.widget.Switch ghost = new android.widget.Switch(this); ghost.setText("Режим призрака"); ghost.setTextColor(Ui.WHITE); ghost.setChecked(presenceManager.isGhostMode()); body.addView(ghost, matchWrap());
        TextView note = Ui.text(this, "Приватность полей профиля и времени захода проверяется правилами RTDB. Режим призрака не отправляет отметки прочтения.", 12, Ui.SECONDARY, false); note.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 14)); body.addView(note, matchWrap());
        String[] keys = {"messages", "lastSeen", "photo", "about", "displayName"};
        String[] labels = {"Кто может писать", "Кто видит время захода", "Кто видит фото", "Кто видит описание", "Кто видит имя"};
        String[] choices = {"Все", "Только контакты", "Только @", "Никто"};
        Map<String, Object> privacy = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            android.widget.Spinner spinner = new android.widget.Spinner(this);
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
            String saved = initial.getOrDefault(key, "Все");
            int selected = java.util.Arrays.asList(choices).indexOf(saved); if (selected < 0) selected = 0;
            spinner.setSelection(selected); privacy.put(key, choices[selected]);
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { privacy.put(key, choices[position]); }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
            TextView label = Ui.text(this, labels[i], 13, Ui.WHITE, false); LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = Ui.dp(this, 10); body.addView(label, l); body.addView(spinner, matchWrap());
        }
        new AlertDialog.Builder(this).setTitle("Конфиденциальность").setView(body).setNegativeButton("Закрыть", null).setPositiveButton("Сохранить", (d, w) -> {
            Map<String, Object> publicSettings = new HashMap<>();
            for (Map.Entry<String, Object> entry : privacy.entrySet()) {
                getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().putString("privacy_" + entry.getKey(), String.valueOf(entry.getValue())).apply();
                if ("messages".equals(entry.getKey())) publicSettings.put("messages", entry.getValue());
                else publicSettings.put("privacy/" + entry.getKey(), entry.getValue());
            }
            Map<String, Object> changes = new HashMap<>();
            for (Map.Entry<String, Object> entry : publicSettings.entrySet()) changes.put("users/" + uid + "/publicSettings/" + entry.getKey(), entry.getValue());
            changes.put("users/" + uid + "/private/settings/ghost", ghost.isChecked());
            database.getReference().updateChildren(changes).addOnSuccessListener(unused -> {
                presenceManager.setGhostMode(uid, ghost.isChecked());
                if (activeChatId != null && !ghost.isChecked()) chatRepository.markRead(activeChatId, uid);
                toast("Настройки приватности сохранены.");
            }).addOnFailureListener(e -> toast("Не удалось сохранить настройки RTDB."));
        }).show();
    }

    private void showCustomization() {
        LinearLayout body = vertical(Ui.SURFACE); body.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        TextView sizeLabel = Ui.text(this, "Размер текста", 14, Ui.WHITE, true); body.addView(sizeLabel);
        SeekBar seek = new SeekBar(this); seek.setMax(10); int current = getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getInt("text_size", 14); seek.setProgress(Math.max(0, Math.min(10, current - 12))); body.addView(seek, matchWrap());
        android.widget.CheckBox bold = new android.widget.CheckBox(this); bold.setText("Жирный текст"); bold.setTextColor(Ui.WHITE); bold.setChecked(isTextBold()); body.addView(bold);
        android.widget.CheckBox italic = new android.widget.CheckBox(this); italic.setText("Курсив"); italic.setTextColor(Ui.WHITE); italic.setChecked(getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("italic", false)); body.addView(italic);
        new AlertDialog.Builder(this).setTitle("Кастомизация").setView(body).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (d, w) -> getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().putInt("text_size", 12 + seek.getProgress()).putBoolean("bold", bold.isChecked()).putBoolean("italic", italic.isChecked()).apply()).show();
    }

    private void showNotificationSettings() {
        LinearLayout body = vertical(Ui.SURFACE); body.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        android.widget.Switch sound = new android.widget.Switch(this); sound.setText("Звук уведомлений"); sound.setTextColor(Ui.WHITE); sound.setChecked(NotificationSounds.isEnabled(this)); body.addView(sound, matchWrap());
        sound.setOnCheckedChangeListener((button, checked) -> NotificationSounds.setEnabled(this, checked));
        TextView choose = Ui.button(this, "Выбрать звук из файлов", Ui.INPUT, Ui.WHITE, 12); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)); p.topMargin = Ui.dp(this, 12); body.addView(choose, p);
        choose.setOnClickListener(v -> { try { pendingMediaKind = "sound"; documentPicker.launch(new String[]{"audio/mpeg", "audio/*"}); } catch (Exception e) { toast("Не удалось открыть выбор файла."); } });
        new AlertDialog.Builder(this).setTitle("Уведомления").setView(body).setNegativeButton("Закрыть", null).show();
    }

    private void showKeySettings() {
        LinearLayout body = vertical(Ui.SURFACE); body.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        body.addView(Ui.text(this, "Signal Protocol · PQXDH + Double Ratchet", 14, Ui.WHITE, true), matchWrap());
        TextView info = Ui.text(this, "Приватная идентичность зашифрована Android Keystore и не отправляется в RTDB. Сверяйте отпечатки собеседников вне чата. Смена ключа сбросит локальные сессии; старые сообщения после этого могут стать недоступны.", 12, Ui.SECONDARY, false); info.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 14)); body.addView(info, matchWrap());
        try {
            Map<String, Object> ownBundle = crypto.publicKeyBundle();
            TextView fingerprint = Ui.text(this, SignalE2ee.identityFingerprint(ownBundle), 11, Ui.WHITE, false); fingerprint.setTextIsSelectable(true); body.addView(fingerprint, matchWrap());
            TextView copyPublic = Ui.button(this, "Скопировать публичный prekey bundle", Ui.INPUT, Ui.WHITE, 12);
            LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)); copyLp.topMargin = Ui.dp(this, 8); body.addView(copyPublic, copyLp);
            copyPublic.setOnClickListener(v -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Tsuyu public key bundle", new JSONObject(ownBundle).toString())); toast("Публичный bundle скопирован."); });
        } catch (Exception e) { body.addView(Ui.text(this, "Не удалось подготовить отпечаток ключа.", 12, Ui.RED, false), matchWrap()); }
        TextView rotate = Ui.button(this, "Создать новую идентичность", Ui.INPUT, Ui.WHITE, 12); LinearLayout.LayoutParams rotateLp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)); rotateLp.topMargin = Ui.dp(this, 8); body.addView(rotate, rotateLp);
        rotate.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Сменить ключ?").setMessage("Текущие локальные Ratchet-сессии будут удалены. Продолжить?").setNegativeButton("Отмена", null).setPositiveButton("Сменить", (d, w) -> rotateIdentity()).show());
        new AlertDialog.Builder(this).setTitle("Ключи шифрования").setView(body).setPositiveButton("Закрыть", null).show();
    }

    private void rotateIdentity() {
        cryptoQueue.execute(() -> {
            try {
                crypto.rotateIdentity();
                Map<String, Object> bundle = crypto.publicKeyBundle();
                Tasks.await(database.getReference("users").child(uid).child("keys").child("1").setValue(bundle));
                runOnUiThread(() -> toast("Новый ключ опубликован. Попросите собеседника начать новый сеанс."));
            } catch (Exception e) { runOnUiThread(() -> toast("Не удалось сменить ключ.")); }
        });
    }

    private LinearLayout bottomBar(String selected) {
        LinearLayout bar = horizontal(Ui.HEADER); bar.setGravity(Gravity.CENTER); bar.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        for (String label : new String[]{"Диалоги", "Поиск", "Настройки"}) {
            TextView item = Ui.text(this, label, 11, selected.equals(label) ? Ui.WHITE : 0xff666666, selected.equals(label)); item.setGravity(Gravity.CENTER); item.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); bar.addView(item, lp);
            item.setOnClickListener(v -> { if ("Диалоги".equals(label)) showHome(); else if ("Поиск".equals(label)) showSearchScreen(); else showSettings(); });
        }
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 50))); bar.setBackgroundColor(Ui.HEADER); bar.setElevation(Ui.dp(this, 3)); return bar;
    }

    private void addTopBar(LinearLayout root, String title, String icon, Runnable back, String actionText, Runnable action) {
        LinearLayout bar = horizontal(Ui.HEADER); bar.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 12), 0);
        TextView backButton = Ui.iconButton(this, icon, Ui.WHITE); bar.addView(backButton, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40))); backButton.setOnClickListener(v -> back.run());
        TextView label = Ui.text(this, title, 18, Ui.WHITE, true); LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1); labelLp.leftMargin = Ui.dp(this, 10); bar.addView(label, labelLp);
        if (actionText != null) { TextView a = Ui.text(this, actionText, 13, Ui.BLUE, true); bar.addView(a); a.setOnClickListener(v -> action.run()); }
        root.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56))); root.addView(Ui.divider(this));
    }

    private ImageView avatar(Profile profile, int sizeDp) {
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackground(Ui.shape(Ui.INPUT, this, sizeDp / 2f)); setAvatar(image, profile, sizeDp); return image;
    }

    private void setAvatar(ImageView image, Profile profile, int sizeDp) {
        if (profile != null && profile.avatarBase64 != null && !profile.avatarBase64.isEmpty()) {
            try {
                byte[] bytes = Base64.decode(profile.avatarBase64, Base64.NO_WRAP);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    RoundedBitmapDrawable rounded = RoundedBitmapDrawableFactory.create(getResources(), bitmap);
                    rounded.setCircular(true); image.setImageDrawable(rounded); return;
                }
            } catch (Exception ignored) { }
        }
        TextView placeholder = new TextView(this);
        String first = profile == null ? "T" : profile.displayNameOrUsername().substring(0, 1).toUpperCase(Locale.ROOT);
        image.setImageDrawable(null); image.setBackground(Ui.shape(0xff252528, this, sizeDp / 2f));
        image.setContentDescription(first);
    }

    private String imageToBase64(Uri uri, int maxDimension, int maxBytes) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, opts); }
        if (bitmap == null) throw new IllegalArgumentException("Image decode failed");
        int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (max > maxDimension) {
            float scale = (float) maxDimension / max;
            bitmap = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int quality = 82;
        do {
            out.reset(); bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out); quality -= 7;
        } while (out.size() > maxBytes && quality >= 40);
        if (out.size() > maxBytes) throw new IllegalArgumentException("Image too large");
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private String readBase64(Uri uri, int maxBytes) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024]; int read; int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read; if (total > maxBytes) throw new IllegalArgumentException("File too large"); out.write(buffer, 0, read);
            }
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        }
    }

    private String previewFromJson(String payload) {
        try {
            JSONObject object = new JSONObject(payload);
            String kind = object.optString("kind", "text");
            if ("text".equals(kind)) return object.optString("text", "Сообщение");
            if ("decrypt_error".equals(kind)) return "Не удалось расшифровать";
            if ("image".equals(kind) || "collage".equals(kind)) return "Фото";
            if ("video".equals(kind)) return "Видео";
            if ("voice".equals(kind)) return "Голосовое сообщение";
            if ("audio".equals(kind)) return "Аудиофайл";
            return "Сообщение";
        } catch (Exception e) { return "Сообщение"; }
    }

    private String textFromPayload(String payload) {
        try {
            JSONObject object = new JSONObject(payload);
            String kind = object.optString("kind", "text");
            if ("text".equals(kind)) return object.optString("text", "");
            if ("decrypt_error".equals(kind)) return "Не удалось расшифровать";
            if ("image".equals(kind) || "collage".equals(kind)) return "Фото";
            if ("video".equals(kind)) return "Видео";
            if ("voice".equals(kind)) return "Голосовое сообщение";
            if ("audio".equals(kind)) return "Аудиофайл";
            return "Сообщение";
        } catch (Exception e) { return "Сообщение"; }
    }

    private void detachChatListeners() {
        if (activeChatId != null) {
            if (messagesListener != null) chatRepository.messages(activeChatId).removeEventListener(messagesListener);
            if (typingListener != null && activePeerUid != null) chatRepository.typing(activeChatId).child(activePeerUid).removeEventListener(typingListener);
            if (peerPresenceRef != null && peerPresenceListener != null) peerPresenceRef.removeEventListener(peerPresenceListener);
            if (receiptRef != null && receiptListener != null) receiptRef.removeEventListener(receiptListener);
            if (peerLastSeenRef != null && peerLastSeenListener != null) peerLastSeenRef.removeEventListener(peerLastSeenListener);
            if (reactionsListener != null) chatRepository.root().child("chats").child(activeChatId).child("reactions").removeEventListener(reactionsListener);
            if (uid != null) chatRepository.setTyping(activeChatId, uid, false);
        }
        markNoActiveChat();
        messagesListener = null; typingListener = null; reactionsListener = null; peerPresenceListener = null; peerPresenceRef = null;
        peerLastSeenListener = null; peerLastSeenRef = null; receiptListener = null; receiptRef = null; peerReadAt = 0;
        peerTyping = false; peerOnline = false; peerLastSeenVisible = false; peerLastSeen = 0;
        messageColumn = null; messageScroll = null; composer = null; replyBanner = null; chatStatus = null;
        ui.removeCallbacks(clearTyping);
        messageReactions.clear();
    }

    private int textSize() { return getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getInt("text_size", 14); }
    private boolean isTextBold() { return getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("bold", false); }
    private int textTypefaceStyle() {
        int style = isTextBold() ? Typeface.BOLD : Typeface.NORMAL;
        if (getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("italic", false)) style |= Typeface.ITALIC;
        return style;
    }
    private void markNoActiveChat() { getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().remove("active_chat").apply(); }
    private void startBackgroundSync() {
        android.content.SharedPreferences prefs = getSharedPreferences("tsuyu_settings", MODE_PRIVATE);
        if (uid == null || !prefs.getBoolean("background_sync", true)) return;
        prefs.edit().putString("sync_uid", uid).apply();
        try {
            Intent service = new Intent(this, com.tsuyu.messenger.notifications.TsuyuSyncService.class)
                    .putExtra(com.tsuyu.messenger.notifications.TsuyuSyncService.EXTRA_UID, uid);
            ContextCompat.startForegroundService(this, service);
        } catch (Exception e) { toast("Фоновая синхронизация недоступна. Проверьте уведомления в настройках Android."); }
    }
    private void stopBackgroundSync() {
        getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().remove("sync_uid").apply();
        try { stopService(new Intent(this, com.tsuyu.messenger.notifications.TsuyuSyncService.class)); }
        catch (Exception ignored) { }
    }
    private void detachRealtimeListeners() { detachUserChatsListener(); detachChatListeners(); if (presenceManager != null) presenceManager.signOut(); }
    private void toast(String text) { if (Looper.myLooper() == Looper.getMainLooper()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); else runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }
    private String authError(Exception e) { String message = e.getLocalizedMessage(); return message == null ? "Не удалось войти. Проверьте почту и пароль." : message; }
    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0L; }
    private LinearLayout vertical(int color) { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(color); return layout; }
    private LinearLayout horizontal(int color) { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); layout.setBackgroundColor(color); return layout; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private LinearLayout.LayoutParams fieldParams() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)); lp.bottomMargin = Ui.dp(this, 11); return lp; }

    @Override
    public void onBackPressed() {
        if ("chat".equals(page)) { detachChatListeners(); showHome(); return; }
        if ("search".equals(page) || "settings".equals(page) || "profile_edit".equals(page)) { showHome(); return; }
        super.onBackPressed();
    }
}
