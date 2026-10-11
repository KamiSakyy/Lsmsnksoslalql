package com.tsuyu.messenger;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.util.Base64;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.tsuyu.messenger.ui.screens.TsuyuScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.tsuyu.messenger.data.ScheduledMessages;

import com.tsuyu.messenger.call.CallActivity;
import com.tsuyu.messenger.call.CallEngine;
import com.tsuyu.messenger.call.CallSignal;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.transition.MaterialFadeThrough;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;
import com.tsuyu.messenger.data.TsuyuStore;
import com.tsuyu.messenger.crypto.LocalMessageCache;
import com.tsuyu.messenger.crypto.LocalVault;
import com.tsuyu.messenger.crypto.SignalE2ee;
import com.tsuyu.messenger.data.ChatMessage;
import com.tsuyu.messenger.data.ChatRepository;
import com.tsuyu.messenger.data.ChatSummary;
import com.tsuyu.messenger.data.PresenceManager;
import com.tsuyu.messenger.data.ProfileRepository;
import com.tsuyu.messenger.notifications.FirebaseMessagingSetup;
import com.tsuyu.messenger.notifications.NotificationSounds;
import com.tsuyu.messenger.ui.ChatListAdapter;
import com.tsuyu.messenger.ui.MessageListAdapter;
import com.tsuyu.messenger.ui.ProfileSearchAdapter;
import com.tsuyu.messenger.ui.CircleCameraDialog;
import com.tsuyu.messenger.ui.ZoomImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private LocalVault vault;
    private TsuyuStore store;
    private static final String ARCHIVE_FOLDER_ID = "__archive";
    private String homeFilter = "all";
    private LinearLayout homeFilters;
    private EditText homeSearchInput;
    private String chatListQuery = "";
    private View chatPinnedBar;
    private TextView chatPinnedText;
    private String pinnedMessageId;
    private boolean selecting;
    private final java.util.LinkedHashSet<String> selectedMessageIds = new java.util.LinkedHashSet<>();
    private View multiSelectBar;
    private View chatVoiceButton;
    private View chatCircleButton;
    private View chatStickerButton;
    private View composerPanel;
    private TextView multiSelectCount;
    private DrawerLayout drawerLayout;
    private View drawerRoot;
    private boolean pinUnlocked;
    private long unreadDividerAt;
    private Drawable swipePinIcon;
    private Drawable swipeReadIcon;
    private final Paint swipePaint = new Paint();
    private View homeOfflineBanner;
    private TextView homeEmptyTitle;
    private TextView homeEmptyDescription;
    private View homeFindPeople;
    private View chatScrollBottom;
    private ValueEventListener connectionListener;
    private boolean onlineNow = true;
    private FirebaseAuth.AuthStateListener authListener;

    private String uid;
    private String activeChatId;
    private String activePeerUid;
    private Profile myProfile;
    private Profile peerProfile;
    // Состояние экранов вынесено в модели (MVVM): список чатов — HomeViewModel,
    // открытая переписка — ConversationViewModel. Ниже — короткие имена для удобства.
    private com.tsuyu.messenger.ui.viewmodel.HomeViewModel homeModel;
    private com.tsuyu.messenger.ui.viewmodel.ConversationViewModel conversationModel;
    private Map<String, ChatSummary> summaries;
    private Map<String, String> folderNames;
    private Map<String, Set<String>> folderMembers;
    private java.util.concurrent.ConcurrentHashMap<String, Long> presenceCheckedAt;
    private Set<String> loadingSummaryIds;
    private java.util.concurrent.ConcurrentHashMap<String, String> payloadIndex;
    private java.util.Set<String> payloadLoading;
    private Map<String, ChatMessage> optimisticMessages;
    private Map<String, Set<String>> messageReactions;
    private Map<String, Map<String, Integer>> pollVotes;
    private Set<String> decrypting;
    private Set<String> decryptFailures;

    private String page = "";
    private boolean authFormIsRegister;
    private boolean editingAvatar;
    private boolean isNewProfile;
    private boolean chatIndexLoaded;
    private String replyToId;
    private String replyToText;
    private long lastTypingWrite;

    private final Handler ui = new Handler(Looper.getMainLooper());
    // Очереди работы: расшифровка — отдельный пул, всё остальное (сеть, база, диск) — общий.
    // Один единственный поток для шифрования заставлял сообщения ждать друг друга.
    private final ExecutorService cryptoQueue = com.tsuyu.messenger.util.Background.crypto();
    private final ExecutorService dataQueue = com.tsuyu.messenger.util.Background.io();
    private final Map<String, DatabaseReference> summaryPresenceRefs = new ConcurrentHashMap<>();
    private final Map<String, ValueEventListener> summaryPresenceListeners = new ConcurrentHashMap<>();
    private final Map<String, DatabaseReference> summaryTypingRefs = new ConcurrentHashMap<>();
    private final Map<String, ValueEventListener> summaryTypingListeners = new ConcurrentHashMap<>();
    private final Map<String, DatabaseReference> summaryMetaRefs = new ConcurrentHashMap<>();
    private final Map<String, ValueEventListener> summaryMetaListeners = new ConcurrentHashMap<>();

    private ActivityResultLauncher<String> avatarPicker;
    private ActivityResultLauncher<String[]> documentPicker;
    private ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> multiPhotoPicker;
    private android.media.MediaRecorder voiceRecorder;
    private java.io.File voiceFile;
    private String pendingMediaKind;
    private String draftAvatarBase64 = "";

    private FrameLayout screenHost;
    private View currentScreen;
    private RecyclerView chatList;
    private RecyclerView searchResults;
    private RecyclerView messageList;
    private View homeEmptyState;
    private View homeLoadingState;
    private View homeNewChat;
    private View searchEmptyState;
    private View chatEmptyState;
    private TextView searchEmptyText;
    private TextInputEditText searchInput;
    private TextInputEditText composer;
    private TextView chatStatus;
    private View chatAvatarDot;
    private View replyBanner;
    private TextView replyBannerText;
    private ChatListAdapter chatListAdapter;
    private ProfileSearchAdapter profileSearchAdapter;
    private MessageListAdapter messageListAdapter;
    private String lastRemoteChatId;
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

    /** Приложение на экране: по этому признаку пуш открывает экран звонка сразу. */
    public static volatile boolean visible;
    /** Слушатель ящика вызовов — подстраховка к пушу, пока приложение открыто. */
    private com.google.firebase.database.ValueEventListener callInboxListener;
    private CallEngine.Listener callLogListener;
    private String callLogChatId;
    private String callLogPeerUid;
    private boolean callLogVideo;
    private boolean callLogIncoming;
    private long callLogActiveAt;
    private boolean callLogSent;
    /** Таймер сгорания, выбранный в меню вложений: уйдёт со следующим сообщением. */
    private int pendingBurnSeconds;
    /** Ключ обоев, которые сейчас выбираем в галерее. */
    private String pendingWallpaperKey;
    /** Голоса опросов: messageId -> (uid -> номер варианта). */
    private com.google.firebase.database.DatabaseReference pollVotesRef;
    private com.google.firebase.database.ValueEventListener pollVotesListener;
    private String burnRefreshChatId;
    /** Номер последнего разбора истории: старые результаты не должны перебивать новые. */
    private int messagesGeneration;
    /** Когда по каждому чату последний раз спрашивали «в сети» и «печатает». */
    /** Готовые тексты сообщений этого чата, прочитанные с диска одним заходом. */
    /** Какие тексты сейчас дочитываются в фоне: лишние повторы не нужны. */
    private boolean renderingMessages;
    /** Фрагмент, который сейчас показан (экраны живут отдельными классами). */
    private com.tsuyu.messenger.ui.screens.TsuyuScreen currentScreenFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Настройки подключаются один раз при старте экрана: дальше они берутся из памяти,
        // а не читаются из файла в главном потоке.
        com.tsuyu.messenger.util.Settings.ensure(this);
        // Данные экранов берём из моделей: они переживают пересоздание экрана и не мешаются
        // с кодом разметки.
        homeModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(com.tsuyu.messenger.ui.viewmodel.HomeViewModel.class);
        conversationModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(com.tsuyu.messenger.ui.viewmodel.ConversationViewModel.class);
        summaries = homeModel.summaries;
        folderNames = homeModel.folderNames;
        folderMembers = homeModel.folderMembers;
        presenceCheckedAt = homeModel.presenceCheckedAt;
        loadingSummaryIds = homeModel.loadingSummaryIds;
        payloadIndex = conversationModel.payloadIndex;
        payloadLoading = conversationModel.payloadLoading;
        optimisticMessages = conversationModel.optimisticMessages;
        messageReactions = conversationModel.messageReactions;
        pollVotes = conversationModel.pollVotes;
        decrypting = conversationModel.decrypting;
        decryptFailures = conversationModel.decryptFailures;
        // Material You: если человек включил «Цвета системы», берём палитру обоев телефона.
        if (com.tsuyu.messenger.util.Settings.getBoolean("dynamic_colors", false)) {
            try {
                com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this);
            } catch (Throwable ignored) { }
        }
        setContentView(R.layout.activity_main);
        screenHost = findViewById(R.id.screen_host);
        drawerLayout = findViewById(R.id.drawer_layout);
        drawerRoot = findViewById(R.id.drawer_root);
        if (drawerRoot != null) {
            ViewCompat.setOnApplyWindowInsetsListener(drawerRoot, (view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout());
                Insets keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
                // Клавиатура: экран поднимается вместе с ней, поле ввода всегда видно.
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return WindowInsetsCompat.CONSUMED;
            });
            ViewCompat.requestApplyInsets(drawerRoot);
        }
        setupDrawer();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(screenHost, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            // Клавиатура раньше не учитывалась вовсе: поле ввода уходило под неё.
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(screenHost);
        FirebaseBootstrap.initialize(this);
        FirebaseApp app = FirebaseApp.getInstance();
        auth = FirebaseAuth.getInstance(app);
        database = FirebaseDatabase.getInstance(app);
        profileRepository = new ProfileRepository(database);
        chatRepository = new ChatRepository(database);
        presenceManager = new PresenceManager(this, database);
        NotificationSounds.ensureChannel(this);

        avatarPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) { editingAvatar = false; return; }
            if (editingAvatar) {
                editingAvatar = false;
                try {
                    draftAvatarBase64 = imageToBase64(uri, 512, 280_000);
                    updateDraftAvatarPreview();
                    Toast.makeText(this, "Фото выбрано", Toast.LENGTH_SHORT).show();
                } catch (Throwable e) {
                    toast("Не удалось прочитать изображение.");
                }
            } else {
                String kind = pendingMediaKind == null ? "image" : pendingMediaKind;
                pendingMediaKind = null;
                if ("wallpaper".equals(kind)) {
                    saveWallpaper(uri, pendingWallpaperKey);
                    pendingWallpaperKey = null;
                } else if ("sticker".equals(kind)) {
                    showStickerEditor(uri);
                } else {
                    sendPickedMedia(uri, kind);
                }
            }
        });
        documentPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) { pendingMediaKind = null; return; }
            if ("circle".equals(pendingMediaKind)) {
                sendPickedMedia(uri, "circle");
                pendingMediaKind = null;
                return;
            }
            if ("sound".equals(pendingMediaKind)) {
                try {
                    NotificationSounds.setSound(this, uri);
                    toast("Звук уведомлений сохранён.");
                } catch (Throwable e) {
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
                store = null;
                vault = null;
                conversationModel.cachedMessages = new ArrayList<>();
                myProfile = null;
                peerProfile = null;
                isNewProfile = false;
                summaries.clear();
                optimisticMessages.clear();
                chatIndexLoaded = false;
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
        applyScreenGuard();
        FirebaseUser current = auth.getCurrentUser();
        if (current == null) showAuth(); else handleSignedIn(current.getUid());
        handleNotificationIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.tsuyu.messenger.crypto.PinLock.requireUnlock(this, uid, new com.tsuyu.messenger.crypto.PinLock.Callback() {
            @Override public void onUnlocked() { pinUnlocked = true; }
            @Override public void onCancelled() { finishAndRemoveTask(); }
        });
    }

    protected void onStart() {
        super.onStart();
        visible = true;
        auth.addAuthStateListener(authListener);
        attachCallInbox();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        // Приложение ушло с экрана: лишние подписки снимаем, сеть и батарея отдыхают.
        detachSummaryWatches();
        detachCallInbox();
        com.tsuyu.messenger.crypto.PinLock.onBackground();
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
            ui.post(() -> showChat(chatId, peerUid));
        }
    }

    private void handleSignedIn(String accountUid) {
        if (accountUid == null || accountUid.equals(uid) && !page.isEmpty()) return;
        page = "loading";
        uid = accountUid;
        // Настройки этого аккаунта читаем один раз и держим в памяти.
        com.tsuyu.messenger.util.Settings.init(this, accountUid);
        com.tsuyu.messenger.crypto.PinLock.requireUnlock(this, uid, new com.tsuyu.messenger.crypto.PinLock.Callback() {
            @Override public void onUnlocked() { pinUnlocked = true; }
            @Override public void onCancelled() { finishAndRemoveTask(); }
        });
        crypto = new SignalE2ee(this, uid);
        attachCallLogListener();
        attachCallInbox();
        // Отложенные сообщения: будильник и досылка того, что уже пора отправить.
        com.tsuyu.messenger.data.ScheduledMessages.armNext(this);
        dataQueue.execute(() -> com.tsuyu.messenger.notifications.ScheduledSendReceiver.flush(getApplicationContext()));
        messageCache = new LocalMessageCache(this, uid);
        vault = new LocalVault(uid);
        store = new TsuyuStore(this, uid, vault);
        String presenceUid = accountUid;
        database.getReference("users").child(presenceUid).child("private").child("settings").child("ghost").get()
                .addOnSuccessListener(snapshot -> {
                    if (!presenceUid.equals(uid)) return;
                    if (snapshot.exists()) presenceManager.setGhostMode(presenceUid, Boolean.TRUE.equals(snapshot.getValue(Boolean.class)));
                    else presenceManager.start(presenceUid);
                }).addOnFailureListener(error -> { if (presenceUid.equals(uid)) presenceManager.start(presenceUid); });
        startBackgroundSync();
        dataQueue.execute(this::replayOutbox);
        // Один раз на версию: убираем из базы старые «тяжёлые» строки, чтобы кеш читался мгновенно.
        dataQueue.execute(() -> {
            try {
                if (com.tsuyu.messenger.util.Settings.getBoolean("heavy_rows_purged_125", false)) return;
                int removed = store.purgeHeavyRows();
                com.tsuyu.messenger.util.Settings.putBoolean("heavy_rows_purged_125", true);
                com.tsuyu.messenger.TsuyuLogs.log(this, "КЕШ", "убрано тяжёлых строк: " + removed);
            } catch (Throwable ignored) { }
        });
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> FirebaseMessagingSetup.saveToken(FirebaseApp.getInstance(), token));
        cryptoQueue.execute(() -> {
            try {
                Map<String, Object> bundle = crypto.publicKeyBundle();
                database.getReference("users").child(uid).child("keys").child("1").setValue(bundle);
            } catch (Throwable e) {
                ui.post(() -> toast("Не удалось подключиться. Проверьте интернет и попробуйте снова."));
            }
        });
        // Локальная копия читается в фоне: список чатов показывается мгновенно,
        // диск не тормозит экран.
        dataQueue.execute(() -> {
            final boolean fromCache = seedFromLocalCache();
            ui.post(() -> profileRepository.getProfile(uid, profile -> {
                if (!uid.equals(accountUid)) return;
                if (profile == null || profile.username == null || profile.username.isEmpty()) {
                    if (fromCache || myProfile != null) {
                        if (!"home".equals(page)) { attachUserChatsListener(); showHome(); }
                    } else {
                        showProfileEditor(true);
                    }
                    return;
                }
                myProfile = profile;
                if (store != null) store.putProfile(profile);
                if (!"home".equals(page)) {
                    attachUserChatsListener();
                    showHome();
                } else {
                    renderChatList();
                }
            }));
        });
    }

    private void showAuth() {
        if (isFinishing()) return;
        page = "auth";
        View root = showScreen(R.layout.screen_auth);
        TextInputEditText email = root.findViewById(R.id.auth_email);
        TextInputEditText password = root.findViewById(R.id.auth_password);
        MaterialButton primary = root.findViewById(R.id.auth_primary);
        MaterialButton switchMode = root.findViewById(R.id.auth_switch_mode);
        primary.setText(authFormIsRegister ? R.string.auth_register : R.string.auth_login);
        switchMode.setText(authFormIsRegister ? R.string.auth_have_account : R.string.auth_create_account);
        primary.setOnClickListener(v -> {
            String mail = email.getText() == null ? "" : email.getText().toString().trim();
            String pass = password.getText() == null ? "" : password.getText().toString();
            if (mail.isEmpty() || pass.length() < 6) {
                toast("Введите почту и пароль (не короче 6 символов).");
                return;
            }
            primary.setEnabled(false);
            if (authFormIsRegister) {
                auth.createUserWithEmailAndPassword(mail, pass)
                        .addOnSuccessListener(result -> {
                            primary.setEnabled(true);
                            toast("Аккаунт создан. Настройте профиль.");
                            handleSignedIn(result.getUser().getUid());
                        })
                        .addOnFailureListener(e -> { primary.setEnabled(true); toast(authError(e)); });
            } else {
                auth.signInWithEmailAndPassword(mail, pass)
                        .addOnSuccessListener(result -> primary.setEnabled(true))
                        .addOnFailureListener(e -> { primary.setEnabled(true); toast(authError(e)); });
            }
        });
        switchMode.setOnClickListener(v -> { authFormIsRegister = !authFormIsRegister; showAuth(); });
    }

    private void showProfileEditor(boolean newProfile) {
        markNoActiveChat();
        page = "profile_edit";
        isNewProfile = newProfile;
        draftAvatarBase64 = myProfile == null || myProfile.avatarBase64 == null ? "" : myProfile.avatarBase64;
        View root = showScreen(R.layout.screen_profile_editor);
        MaterialToolbar toolbar = root.findViewById(R.id.profile_toolbar);
        toolbar.setTitle(newProfile ? "Создать профиль" : "Редактировать профиль");
        toolbar.setNavigationOnClickListener(v -> {
            if (newProfile) auth.signOut(); else showSettings();
        });
        TextInputEditText name = root.findViewById(R.id.profile_name);
        TextInputEditText username = root.findViewById(R.id.profile_username);
        TextInputEditText about = root.findViewById(R.id.profile_about);
        if (myProfile != null) {
            name.setText(myProfile.displayName == null ? "" : myProfile.displayName);
            username.setText(myProfile.username == null ? "" : myProfile.username);
            about.setText(myProfile.about == null ? "" : myProfile.about);
        }
        ShapeableImageView avatar = root.findViewById(R.id.profile_avatar);
        TextView avatarInitial = root.findViewById(R.id.profile_avatar_initial);
        bindAvatar(avatar, avatarInitial, myProfile, name.getText().toString(), draftAvatarBase64);
        View avatarContainer = root.findViewById(R.id.profile_avatar_container);
        View.OnClickListener choosePhoto = v -> { editingAvatar = true; avatarPicker.launch("image/*"); };
        avatarContainer.setOnClickListener(choosePhoto);
        root.findViewById(R.id.profile_pick_photo).setOnClickListener(choosePhoto);
        MaterialButton save = root.findViewById(R.id.profile_save);
        save.setOnClickListener(v -> {
            save.setEnabled(false);
            profileRepository.saveProfile(uid,
                    name.getText() == null ? "" : name.getText().toString(),
                    username.getText() == null ? "" : username.getText().toString(),
                    about.getText() == null ? "" : about.getText().toString(),
                    draftAvatarBase64,
                    new ProfileRepository.Result<Profile>() {
                        @Override public void success(Profile profile) {
                            save.setEnabled(true);
                            myProfile = profile;
                            isNewProfile = false;
                            attachUserChatsListener();
                            showHome();
                        }
                        @Override public void error(String message) { save.setEnabled(true); toast(message); }
                    });
        });
    }

    private void showHome() {
        if (uid == null) { showAuth(); return; }
        isNewProfile = false;
        markNoActiveChat();
        page = "home";
        activeChatId = null;
        activePeerUid = null;
        View root = showScreen(R.layout.screen_home);
        ui.post(this::showCrashReportIfAny);
        View menuButton = root.findViewById(R.id.home_menu_btn);
        if (menuButton != null) menuButton.setOnClickListener(v -> openDrawer());
        View encryptButton = root.findViewById(R.id.home_encrypt_btn);
        if (encryptButton != null) encryptButton.setOnClickListener(v -> showKeySettings());
        View titleBlock = root.findViewById(R.id.home_title_block);
        if (titleBlock != null) titleBlock.setOnClickListener(v -> openDrawer());
        bindDrawerHeader();
        homeSearchInput = root.findViewById(R.id.home_search_input);
        if (homeSearchInput != null) {
            homeSearchInput.setText(chatListQuery);
            homeSearchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    chatListQuery = s == null ? "" : s.toString().trim();
                    renderChatList();
                }
                @Override public void afterTextChanged(Editable s) { }
            });
            homeSearchInput.setOnEditorActionListener((view, actionId, event) -> {
                showSearchScreen();
                return true;
            });
        }
        chatList = root.findViewById(R.id.chats_list);
        chatList.setLayoutManager(new LinearLayoutManager(this));
        // Штатные анимации списка включены: новая строка появляется плавно. Смена содержимого
        // строки рисуется без «мигания», поэтому перерисовка одной строки остаётся мгновенной.
        androidx.recyclerview.widget.DefaultItemAnimator chatRowsAnimator =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        chatRowsAnimator.setSupportsChangeAnimations(false);
        chatList.setItemAnimator(chatRowsAnimator);
        // Плавная прокрутка без лишних событий: подписки «в сети» и «печатает» следуют за
        // видимыми строками, а не висят на всех чатах сразу.
        chatList.setItemViewCacheSize(12);
        chatList.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView view, int dx, int dy) {
                updateSummaryWatches();
            }
        });
        chatListAdapter = new ChatListAdapter(new ChatListAdapter.Listener() {
            @Override public void onChatSelected(ChatSummary summary) { openChat(summary); }
            @Override public void onChatLongPress(ChatSummary summary) { showChatMenu(summary); }
            @Override public String previewFor(ChatSummary summary) { return MainActivity.this.previewFor(summary); }
            @Override public int ownMessageState(ChatSummary summary) { return MainActivity.this.ownMessageState(summary); }
        });
        chatList.setAdapter(chatListAdapter);
        chatList.setHasFixedSize(true);
        attachSwipeActions();
        homeEmptyState = root.findViewById(R.id.home_empty_state);
        homeLoadingState = root.findViewById(R.id.home_loading_state);
        homeNewChat = root.findViewById(R.id.home_new_chat);
        homeEmptyTitle = root.findViewById(R.id.home_empty_title);
        homeEmptyDescription = root.findViewById(R.id.home_empty_description);
        homeFindPeople = root.findViewById(R.id.home_find_people);
        homeNewChat.setOnClickListener(v -> showSearchScreen());
        homeFindPeople.setOnClickListener(v -> showSearchScreen());
        homeFilters = root.findViewById(R.id.home_filters);
        loadFolders();
        rebuildFilterChips();
        homeOfflineBanner = root.findViewById(R.id.home_offline_banner);
        if (homeOfflineBanner != null) homeOfflineBanner.setVisibility(onlineNow ? View.GONE : View.VISIBLE);
        watchConnection();
        attachUserChatsListener();
        renderChatList();
        if (getIntent() != null) handleNotificationIntent(getIntent());
    }

    private void attachUserChatsListener() {
        if (uid == null || uid.equals(listenedUserChatsUid) && userChatsListener != null) return;
        detachUserChatsListener();
        listenedUserChatsUid = uid;
        userChatsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) { loadChatSummaries(snapshot); }
            @Override public void onCancelled(DatabaseError error) {
                chatIndexLoaded = true;
                renderChatList();
                toast("Не удалось загрузить чаты. Проверьте подключение и попробуйте снова.");
            }
        };
        chatRepository.userChats(uid).addValueEventListener(userChatsListener);
    }

    private void detachUserChatsListener() {
        if (userChatsListener != null && listenedUserChatsUid != null) {
            chatRepository.userChats(listenedUserChatsUid).removeEventListener(userChatsListener);
        }
        userChatsListener = null;
        listenedUserChatsUid = null;
        chatIndexLoaded = false;
        summaries.clear();
        loadingSummaryIds.clear();
        optimisticMessages.clear();
        Set<String> watched = new HashSet<>(summaryPresenceRefs.keySet());
        watched.addAll(summaryTypingRefs.keySet());
        watched.addAll(summaryMetaRefs.keySet());
        for (String chatId : watched) removeSummaryRealtime(chatId);
    }

    private void loadChatSummaries(DataSnapshot snapshot) {
        chatIndexLoaded = true;
        List<String> ids = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) if (child.getKey() != null) ids.add(child.getKey());
        Set<String> idSet = new HashSet<>(ids);
        Set<String> known = new HashSet<>(summaries.keySet());
        known.addAll(summaryPresenceRefs.keySet());
        known.addAll(summaryTypingRefs.keySet());
        known.addAll(summaryMetaRefs.keySet());
        for (String oldId : known) {
            if (!idSet.contains(oldId)) {
                summaries.remove(oldId);
                removeSummaryRealtime(oldId);
            }
        }
        for (String chatId : ids) {
            ChatSummary cached = summaries.get(chatId);
            if (cached != null && cached.peerUid != null) {
                dataQueue.execute(() -> refreshSummary(chatId, cached));
                continue;
            }
            if (!loadingSummaryIds.add(chatId)) continue;
            dataQueue.execute(() -> loadOneSummary(chatId));
        }
        renderChatList();
    }

    private void loadOneSummary(String chatId) {
        String expectedUid = uid;
        try {
            DataSnapshot members = Tasks.await(chatRepository.root().child("chats").child(chatId).child("members").get(), 10, TimeUnit.SECONDS);
            String peerUid = null;
            for (DataSnapshot child : members.getChildren()) {
                if (child.getKey() != null && !child.getKey().equals(expectedUid)) { peerUid = child.getKey(); break; }
            }
            if (peerUid == null || !expectedUid.equals(uid)) return;
            DatabaseReference profileRef = chatRepository.root().child("users").child(peerUid).child("profile");
            Profile profile = new Profile();
            profile.uid = peerUid;
            profile.username = readOptionalProfileField(profileRef, "username");
            profile.displayName = readOptionalProfileField(profileRef, "displayName");
            profile.avatarBase64 = readOptionalProfileField(profileRef, "avatarBase64");
            profile.about = readOptionalProfileField(profileRef, "about");
            if ((profile.displayName == null || profile.displayName.trim().isEmpty())
                    && (profile.username == null || profile.username.trim().isEmpty())) profile.displayName = "Собеседник";

            DataSnapshot meta = readOptionalSnapshot(chatRepository.chatMeta(chatId));
            ChatMessage last = meta == null ? null : meta.child("lastMessage").getValue(ChatMessage.class);
            if (last != null) last.id = meta.child("lastMessageId").getValue(String.class);
            long at = meta == null ? 0L : number(meta.child("lastAt").getValue());
            boolean online = false;
            boolean typingNow = false;
            // «В сети» и «печатает» обновляем не чаще раза в 20 секунд: иначе на каждый чат
            // уходило по два лишних сетевых запроса и список полз.
            Long checkedAt = presenceCheckedAt.get(chatId);
            boolean needsPresence = checkedAt == null || System.currentTimeMillis() - checkedAt > 20_000L;
            if (needsPresence) {
                presenceCheckedAt.put(chatId, System.currentTimeMillis());
                try {
                    DataSnapshot onlineSnapshot = Tasks.await(chatRepository.presence(peerUid).child("online").get(), 10, TimeUnit.SECONDS);
                    online = Boolean.TRUE.equals(onlineSnapshot.getValue(Boolean.class));
                } catch (Throwable ignored) { }
                try {
                    DataSnapshot typing = Tasks.await(chatRepository.typing(chatId).child(peerUid).get(), 10, TimeUnit.SECONDS);
                    long typingAt = number(typing.child("at").getValue());
                    typingNow = Boolean.TRUE.equals(typing.child("typing").getValue(Boolean.class))
                            && System.currentTimeMillis() - typingAt <= 5500;
                } catch (Throwable ignored) { }
            } else {
                ChatSummary previousSummary = summaries.get(chatId);
                if (previousSummary != null) {
                    online = previousSummary.online;
                    typingNow = previousSummary.typing;
                }
            }
            if (!expectedUid.equals(uid)) return;
            if (last != null) {
                last.id = meta == null ? null : meta.child("lastMessageId").getValue(String.class);
                reconcileOptimisticMessage(chatId, last);
            }
            ChatSummary summary = new ChatSummary(chatId, peerUid, profile, at, last == null ? null : last.cipher);
            summary.online = online;
            summary.typing = typingNow;
            summary.lastMessage = last;
            ChatSummary previous = summaries.get(chatId);
            if (previous != null) {
                summary.pinned = previous.pinned;
                summary.unread = previous.unread;
                summary.lastSeen = previous.lastSeen;
                boolean keepLocalPreview = previous.lastMessage != null
                        && previous.lastMessage.localPayload != null
                        && (last == null || previous.lastAt >= at);
                if (keepLocalPreview) {
                    summary.lastAt = previous.lastAt;
                    summary.lastMessage = previous.lastMessage;
                    summary.encryptedPreview = null;
                }
            }
            summaries.put(chatId, summary);
            watchSummaryRealtime(chatId, peerUid);
            if (summary.lastMessage != null) maybeDecryptPreview(summary, summary.lastMessage);
            if (store != null) {
                summary.unread = Math.max(summary.unread, store.unread(chatId));
                cachePreview(summary);
                persistSummary(summary);
            }
            refreshSummaryPreview(summary);
        } catch (Throwable error) {
            android.util.Log.w("Tsuyu", "Could not load chat summary " + chatId, error);
        } finally {
            loadingSummaryIds.remove(chatId);
            runOnUiThread(this::renderChatList);
        }
    }

    private DataSnapshot readOptionalSnapshot(DatabaseReference ref) {
        try { return Tasks.await(ref.get(), 10, TimeUnit.SECONDS); }
        catch (Throwable ignored) { return null; }
    }

    private String readOptionalProfileField(DatabaseReference profileRef, String field) {
        try { return Tasks.await(profileRef.child(field).get(), 10, TimeUnit.SECONDS).getValue(String.class); }
        catch (Throwable ignored) { return null; }
    }

    private void watchSummaryRealtime(String chatId, String peerUid) {
        watchSummaryMeta(chatId);
        watchPresenceTyping(chatId, peerUid);
    }

    /**
     * «В сети» и «печатает» слушаем только у строк, которые видно на экране: раньше на каждый
     * чат висело по три постоянных подписки, и список из десятков чатов «долбил» экран
     * событиями даже когда в нём ничего не происходило.
     */
    private void watchPresenceTyping(String chatId, String peerUid) {
        if (peerUid == null) return;
        if (!summaryPresenceListeners.containsKey(chatId)) {
            DatabaseReference ref = chatRepository.presence(peerUid).child("online");
            ValueEventListener listener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    ChatSummary summary = summaries.get(chatId);
                    if (summary != null) {
                        summary.online = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                        if (store != null) store.setPresence(chatId, summary.online, 0L);
                        requestChatListRender();
                    }
                }
                @Override public void onCancelled(DatabaseError error) { }
            };
            summaryPresenceRefs.put(chatId, ref);
            summaryPresenceListeners.put(chatId, listener);
            ref.addValueEventListener(listener);
        }
        if (!summaryTypingListeners.containsKey(chatId)) {
            DatabaseReference ref = chatRepository.typing(chatId).child(peerUid);
            ValueEventListener listener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    ChatSummary summary = summaries.get(chatId);
                    if (summary == null) return;
                    long at = number(snapshot.child("at").getValue());
                    summary.typing = Boolean.TRUE.equals(snapshot.child("typing").getValue(Boolean.class))
                            && System.currentTimeMillis() - at <= 5500;
                    requestChatListRender();
                }
                @Override public void onCancelled(DatabaseError error) { }
            };
            summaryTypingRefs.put(chatId, ref);
            summaryTypingListeners.put(chatId, listener);
            ref.addValueEventListener(listener);
        }
    }

    /**
     * Данные строки чата (последнее сообщение, счётчик непрочитанных). Тяжёлая часть —
     * чтение кэша, разбор текста, запись в базу — уходит в фоновый поток: событие из сети
     * больше не заставляет главный поток ждать.
     */
    private void watchSummaryMeta(String chatId) {
        if (summaryMetaListeners.containsKey(chatId)) return;
        DatabaseReference ref = chatRepository.chatMeta(chatId);
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                final ChatMessage last = snapshot.child("lastMessage").getValue(ChatMessage.class);
                if (last != null) last.id = snapshot.child("lastMessageId").getValue(String.class);
                final long remoteAt = number(snapshot.child("lastAt").getValue());
                dataQueue.execute(() -> {
                    final ChatSummary summary = summaries.get(chatId);
                    if (summary == null) return;
                    ChatMessage current = summary.lastMessage;
                    boolean sameOptimisticMessage = last != null && current != null
                            && current.clientId != null && current.clientId.equals(last.clientId);
                    if (sameOptimisticMessage) reconcileOptimisticMessage(chatId, last);
                    boolean preserveLocalPreview = current != null && current.localPayload != null
                            && !sameOptimisticMessage && current.sentAt >= remoteAt;
                    summary.lastAt = preserveLocalPreview ? current.sentAt : remoteAt;
                    summary.lastMessage = preserveLocalPreview ? current : last;
                    summary.encryptedPreview = summary.lastMessage == null || preserveLocalPreview
                            ? null : summary.lastMessage.cipher;
                    if (summary.lastMessage != null && !preserveLocalPreview) {
                        maybeDecryptPreview(summary, summary.lastMessage);
                    }
                    ChatMessage newest = summary.lastMessage;
                    if (newest != null && newest.id != null) {
                        String payload = newest.localPayload;
                        if (payload == null && messageCache != null) {
                            payload = payloadIndex.get(newest.id);
                            if (payload == null) {
                                payload = messageCache.get(chatId, newest.id);
                                if (payload != null) payloadIndex.put(newest.id, payload);
                            }
                        }
                        if (payload == null && newest.clientId != null) {
                            ChatMessage local = optimisticMessages.get(newest.clientId);
                            if (local != null) payload = local.localPayload;
                        }
                        if (payload != null) {
                            summary.lastPreview = previewFromJson(payload);
                            summary.lastKind = kindFromJson(payload);
                            if (store != null) store.updatePayload(newest.id, payload);
                        }
                    }
                    if (store != null) {
                        if (newest != null && !preserveLocalPreview) countIncoming(summary, newest);
                        persistSummary(summary);
                    }
                    refreshSummaryPreview(summary);
                    requestChatListRender();
                });
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        summaryMetaRefs.put(chatId, ref);
        summaryMetaListeners.put(chatId, listener);
        ref.addValueEventListener(listener);
    }

    private void removeSummaryRealtime(String chatId) {
        removePresenceTyping(chatId);
        DatabaseReference metaRef = summaryMetaRefs.remove(chatId);
        ValueEventListener metaListener = summaryMetaListeners.remove(chatId);
        if (metaRef != null && metaListener != null) metaRef.removeEventListener(metaListener);
    }

    private void maybeDecryptPreview(ChatSummary summary, ChatMessage message) {
        if (message.id == null || message.senderUid == null) return;
        String cached = messageCache == null ? null : messageCache.get(summary.chatId, message.id);
        if (cached != null) return;
        if (message.senderUid.equals(uid)) {
            ChatMessage local = message.clientId == null ? null : optimisticMessages.get(message.clientId);
            if (local != null && local.localPayload != null && messageCache != null) {
                messageCache.put(summary.chatId, message.id, local.localPayload);
            }
            return;
        }
        if (message.cipher == null) return;
        String key = summary.chatId + ":" + message.id;
        if (!decrypting.add(key)) return;
        cryptoQueue.execute(() -> {
            try {
                byte[] plaintext = crypto.decrypt(summary.peerUid, message.signalType, message.cipher);
                String json = new String(plaintext, StandardCharsets.UTF_8);
                if (messageCache != null) messageCache.put(summary.chatId, message.id, json);
                if (store != null) {
                    store.updatePayload(message.id, json);
                    if (summary.lastMessage != null && message.id != null && message.id.equals(summary.lastMessage.id)) {
                        summary.lastPreview = previewFromJson(json);
                        summary.lastKind = kindFromJson(json);
                        store.updatePreview(summary.chatId, summary.lastPreview, summary.lastKind,
                                summary.lastAt, message.senderUid, summary.lastState);
                    }
                }
                runOnUiThread(this::renderChatList);
            } catch (Throwable ignored) { }
            finally { decrypting.remove(key); }
        });
    }

    private void reconcileOptimisticMessage(String chatId, ChatMessage serverMessage) {
        if (serverMessage == null || serverMessage.clientId == null) return;
        ChatMessage local = optimisticMessages.get(serverMessage.clientId);
        if (local == null || !chatId.equals(local.localChatId)) return;
        local.id = serverMessage.id;
        local.pending = false;
        local.failed = false;
        if (messageCache != null && local.localPayload != null && serverMessage.id != null) {
            messageCache.put(chatId, serverMessage.id, local.localPayload);
        }
        optimisticMessages.remove(serverMessage.clientId, local);
        // Подтверждение пришло: черновая копия уходит с экрана в тот же миг, а не со
        // следующим снимком из сети.
        if (!renderingMessages && chatId.equals(activeChatId)) requestMessageRender();
    }

    /** Перерисовку списка собираем в одну на кадр: поток событий из сети больше не «долбит» экран. */
    private final java.util.concurrent.atomic.AtomicBoolean chatListRenderQueued =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void requestChatListRender() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // Список чатов обновляется в тот же кадр: и новый чат, и изменившаяся строка.
            if (!chatListRenderQueued.compareAndSet(false, true)) return;
            chatListRenderQueued.set(false);
            renderChatList();
            return;
        }
        if (!chatListRenderQueued.compareAndSet(false, true)) return;
        ui.post(() -> {
            chatListRenderQueued.set(false);
            renderChatList();
        });
    }

    /**
     * Подписки «в сети», «печатает» держим только для видимых строк (и пары соседних):
     * список из десятков чатов больше не тянет десятки постоянных подписок.
     */
    private void updateSummaryWatches() {
        if (chatList == null || !"home".equals(page)) return;
        android.view.View parent = (android.view.View) chatList.getParent();
        if (parent != null && parent.getVisibility() != android.view.View.VISIBLE) return;
        Set<String> wanted = new HashSet<>();
        try {
            androidx.recyclerview.widget.LinearLayoutManager manager =
                    (androidx.recyclerview.widget.LinearLayoutManager) chatList.getLayoutManager();
            if (manager != null && chatListAdapter != null && chatListAdapter.getItemCount() > 0) {
                int first = Math.max(0, manager.findFirstVisibleItemPosition() - 2);
                int last = Math.min(chatListAdapter.getItemCount() - 1,
                        manager.findLastVisibleItemPosition() + 2);
                for (int position = first; position <= last; position++) {
                    ChatSummary summary = chatListAdapter.summaryAt(position);
                    if (summary != null && summary.chatId != null) wanted.add(summary.chatId);
                }
            }
        } catch (Throwable ignored) { }
        for (String chatId : new ArrayList<>(summaryPresenceListeners.keySet())) {
            if (!wanted.contains(chatId)) removePresenceTyping(chatId);
        }
        for (String chatId : new ArrayList<>(summaryTypingListeners.keySet())) {
            if (!wanted.contains(chatId)) removePresenceTyping(chatId);
        }
        for (String chatId : wanted) {
            ChatSummary summary = summaries.get(chatId);
            if (summary != null && summary.peerUid != null) watchPresenceTyping(chatId, summary.peerUid);
        }
    }

    /** Снимает подписки «в сети» и «печатает» — например, когда список уходит с экрана. */
    private void detachSummaryWatches() {
        for (String chatId : new ArrayList<>(summaryPresenceListeners.keySet())) removePresenceTyping(chatId);
        for (String chatId : new ArrayList<>(summaryTypingListeners.keySet())) removePresenceTyping(chatId);
    }

    private void removePresenceTyping(String chatId) {
        DatabaseReference presenceRef = summaryPresenceRefs.remove(chatId);
        ValueEventListener presenceListener = summaryPresenceListeners.remove(chatId);
        if (presenceRef != null && presenceListener != null) presenceRef.removeEventListener(presenceListener);
        DatabaseReference typingRef = summaryTypingRefs.remove(chatId);
        ValueEventListener typingListener = summaryTypingListeners.remove(chatId);
        if (typingRef != null && typingListener != null) typingRef.removeEventListener(typingListener);
    }

    private void renderChatList() {
        if (!"home".equals(page) || chatList == null || chatListAdapter == null) return;
        Set<String> folderFilter = null;
        if (homeFilter != null && homeFilter.startsWith("folder:")) {
            folderFilter = folderMembers.get(homeFilter.substring("folder:".length()));
            if (folderFilter == null) {
                homeFilter = "all";
                folderFilter = null;
            }
        }
        java.util.Set<String> archived = folderMembers.get(ARCHIVE_FOLDER_ID);
        List<ChatSummary> items = new ArrayList<>();
        for (ChatSummary summary : summaries.values()) {
            boolean inArchive = summary.chatId != null && archived != null && archived.contains(summary.chatId);
            if ("archive".equals(homeFilter)) {
                if (!inArchive) continue;
            } else if (inArchive) {
                continue;
            }
            if ("unread".equals(homeFilter) && summary.unread <= 0) continue;
            if (folderFilter != null && (summary.chatId == null || !folderFilter.contains(summary.chatId))) continue;
            if (!chatListQuery.isEmpty() && !matchesChatQuery(summary)) continue;
            items.add(summary);
        }
        items.sort((a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            return Long.compare(b.lastAt, a.lastAt);
        });
        chatListAdapter.submit(items);
        updateFilterChipSelection();
        if (items.isEmpty()) {
            chatList.setVisibility(View.GONE);
            homeLoadingState.setVisibility(chatIndexLoaded ? View.GONE : View.VISIBLE);
            homeEmptyState.setVisibility(chatIndexLoaded ? View.VISIBLE : View.GONE);
            if (homeNewChat != null) homeNewChat.setVisibility(View.GONE);
            applyEmptyStateCopy(!chatListQuery.isEmpty() ? "search"
                    : "archive".equals(homeFilter) ? "archive"
                    : folderFilter != null ? "folder"
                    : "unread".equals(homeFilter) ? "unread" : "all");
        } else {
            chatList.setVisibility(View.VISIBLE);
            homeLoadingState.setVisibility(View.GONE);
            homeEmptyState.setVisibility(View.GONE);
            if (homeNewChat != null) homeNewChat.setVisibility(View.VISIBLE);
        }
        setSkeletonShimmer(homeLoadingState != null && homeLoadingState.getVisibility() == View.VISIBLE);
        updateSummaryWatches();
    }

    /**
     * Текст-подпись строки списка. Здесь нет ни чтения диска, ни разбора JSON: готовая
     * подпись готовится заранее в фоне ({@link #refreshSummaryPreview}), поэтому список
     * листается без запинок.
     */
    private String previewFor(ChatSummary summary) {
        if (summary.typing) return "печатает…";
        if (summary.previewText != null && !summary.previewText.isEmpty()) return summary.previewText;
        if (summary.lastMessage != null && summary.lastMessage.failed) {
            return "Не отправлено · нажмите, чтобы повторить";
        }
        if (summary.lastPreview != null && !summary.lastPreview.isEmpty()) return summary.lastPreview;
        return summary.lastAt > 0 ? "Сообщение" : "Начните переписку";
    }

    /**
     * Готовит подпись строки в фоне: расшифровка-из-кэша и разбор JSON выполняются вне
     * главного потока, результат складывается в строку чата.
     */
    private void refreshSummaryPreview(final ChatSummary summary) {        if (summary == null || summary.chatId == null) return;
        final String chatId = summary.chatId;
        final ChatMessage last = summary.lastMessage;
        dataQueue.execute(() -> {
            String text = null;
            try {
                String payload = last == null ? null : last.localPayload;
                if (payload == null && last != null && last.id != null && messageCache != null) {
                    payload = payloadIndex.get(last.id);
                    if (payload == null) {
                        payload = messageCache.get(chatId, last.id);
                        if (payload != null) payloadIndex.put(last.id, payload);
                    }
                }
                if (payload == null && last != null && last.clientId != null) {
                    ChatMessage local = optimisticMessages.get(last.clientId);
                    if (local != null) payload = local.localPayload;
                }
                if (payload != null) text = previewFromJson(payload);
            } catch (Throwable ignored) { }
            final String ready = text;
            ui.post(() -> {
                summary.previewText = ready == null ? "" : ready;
                if (chatId.equals(activeChatId) || "home".equals(page)) renderChatList();
            });
        });
    }

    private void openChat(ChatSummary summary) { showChat(summary.chatId, summary.peerUid); }

    private void showSearchScreen() {
        if (uid == null) return;
        markNoActiveChat();
        page = "search";
        View root = showScreen(R.layout.screen_search);
        MaterialToolbar toolbar = root.findViewById(R.id.search_toolbar);
        toolbar.setNavigationOnClickListener(v -> showHome());
        searchInput = root.findViewById(R.id.search_input);
        searchResults = root.findViewById(R.id.search_results);
        searchEmptyState = root.findViewById(R.id.search_empty_state);
        searchEmptyText = root.findViewById(R.id.search_empty_text);
        searchResults.setLayoutManager(new LinearLayoutManager(this));
        profileSearchAdapter = new ProfileSearchAdapter(profile -> chatRepository.openOrCreate(uid, profile.uid,
                new ChatRepository.Result<String>() {
                    @Override public void success(String chatId) {
                        peerProfile = profile;
                        showChat(chatId, profile.uid);
                    }
                    @Override public void error(String error) { toast(error); }
                }));
        searchResults.setAdapter(profileSearchAdapter);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Поиск идёт сразу, без ожидания: локальные данные отвечают мгновенно.
                runSearch(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        searchEmptyState.setVisibility(View.VISIBLE);
        searchInput.post(() -> {
            if (!"search".equals(page)) return;
            searchInput.requestFocus();
            Object service = getSystemService(INPUT_METHOD_SERVICE);
            if (service instanceof InputMethodManager) ((InputMethodManager) service).showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void runSearch(String query) {
        if (!"search".equals(page) || profileSearchAdapter == null) return;
        String normalized = ProfileRepository.normalizeUsername(query);
        if (normalized.isEmpty()) {
            profileSearchAdapter.submit(Collections.emptyList());
            searchEmptyText.setText(R.string.search_prompt);
            searchEmptyState.setVisibility(View.VISIBLE);
            return;
        }
        searchEmptyText.setText("Поиск…");
        searchEmptyState.setVisibility(View.VISIBLE);
        profileRepository.searchUsers(normalized, uid, new ProfileRepository.Result<List<Profile>>() {
            @Override public void success(List<Profile> profiles) {
                if (!"search".equals(page) || profileSearchAdapter == null) return;
                profileSearchAdapter.submit(profiles);
                if (profiles.isEmpty()) {
                    searchEmptyText.setText(R.string.no_search_results);
                    searchEmptyState.setVisibility(View.VISIBLE);
                } else searchEmptyState.setVisibility(View.GONE);
            }
            @Override public void error(String message) {
                if (!"search".equals(page)) return;
                profileSearchAdapter.submit(Collections.emptyList());
                searchEmptyText.setText("Не удалось выполнить поиск. Проверьте соединение.");
                searchEmptyState.setVisibility(View.VISIBLE);
                toast(message);
            }
        });
    }


    private void showChat(String chatId, String peerUid) {
        try {
            com.tsuyu.messenger.TsuyuLogs.log(this, "ЧАТ", "открываю " + chatId);
            showChatInner(chatId, peerUid);
            com.tsuyu.messenger.TsuyuLogs.log(this, "ЧАТ", "чат открыт " + chatId);
        } catch (Throwable error) {
            // Переписка не имеет права ронять приложение: показываем причину и остаёмся живы.
            recordChatError(error);
            try {
                detachChatListeners();
                showHome();
            } catch (Throwable ignored) { }
        }
    }

    /** Любая ошибка открытия чата остаётся на устройстве и показывается текстом, а не вылетом. */
    private void recordChatError(Throwable error) {
        try {
            com.tsuyu.messenger.TsuyuLogs.crash(this, "открытие чата", error);
            String reason = error == null ? "неизвестно" : error.getClass().getSimpleName();
            String message = error == null ? "" : String.valueOf(error.getMessage());
            if (!message.isEmpty()) reason = reason + ": " + message;
            if (reason.length() > 160) reason = reason.substring(0, 160);
            getSharedPreferences("tsuyu_crash", MODE_PRIVATE).edit()
                    .putString("last_chat", reason)
                    .putLong("at", System.currentTimeMillis()).apply();
            toast(getString(R.string.chat_open_failed, reason));
        } catch (Throwable ignored) { }
    }

    private void showChatInner(String chatId, String peerUid) {
        if (uid == null || chatId == null || peerUid == null) return;
        detachChatListeners();
        if (peerProfile == null || !peerUid.equals(peerProfile.uid)) peerProfile = null;
        activeChatId = chatId;
        activePeerUid = peerUid;
        replyToId = null;
        replyToText = null;
        conversationModel.lastRemoteMessages = new ArrayList<>();
        lastRemoteChatId = chatId;
        com.tsuyu.messenger.util.Settings.putString("active_chat", chatId);
        page = "chat";
        com.tsuyu.messenger.notifications.NotificationCenter.clear(this, chatId);
        // Тексты этого чата читаем с диска одним заходом — в фоне, пока рисуется экран.
        preloadPayloadIndex(chatId);
        exitSelectionMode();
        View root = showScreen(R.layout.screen_chat);
        root.findViewById(R.id.chat_back).setOnClickListener(v -> {
            detachChatListeners();
            showHome();
        });
        root.findViewById(R.id.chat_more).setOnClickListener(v -> showChatMenu());
        try {
            View voiceCall = root.findViewById(R.id.chat_voice_call);
            if (voiceCall != null) voiceCall.setOnClickListener(v -> startCall(chatId, peerUid, false));
            View videoCall = root.findViewById(R.id.chat_video_call);
            if (videoCall != null) videoCall.setOnClickListener(v -> startCall(chatId, peerUid, true));
        } catch (Throwable ignored) { }
        ShapeableImageView avatar = root.findViewById(R.id.chat_peer_avatar);
        TextView avatarInitial = root.findViewById(R.id.chat_peer_initial);
        chatStatus = root.findViewById(R.id.chat_peer_status);
        chatAvatarDot = root.findViewById(R.id.chat_peer_online_dot);
        View avatarFrame = root.findViewById(R.id.chat_peer_avatar_frame);
        View peerHeader = root.findViewById(R.id.chat_peer_header_text);
        avatarFrame.setOnClickListener(v -> showPeerProfile());
        peerHeader.setOnClickListener(v -> showPeerProfile());
        Profile firstProfile = peerProfile;
        if (firstProfile == null) {
            firstProfile = new Profile();
            firstProfile.uid = peerUid;
            firstProfile.displayName = "Собеседник";
        }
        root.<TextView>findViewById(R.id.chat_peer_name).setText(firstProfile.displayNameOrUsername());
        bindAvatar(avatar, avatarInitial, firstProfile, firstProfile.displayNameOrUsername(), firstProfile.avatarBase64);
        if (peerProfile == null && store != null) {
            Profile cachedPeer = store.getProfile(peerUid);
            if (cachedPeer != null) {
                cachedPeer.uid = peerUid;
                peerProfile = cachedPeer;
                root.<TextView>findViewById(R.id.chat_peer_name).setText(cachedPeer.displayNameOrUsername());
                bindAvatar(avatar, avatarInitial, cachedPeer, cachedPeer.displayNameOrUsername(), cachedPeer.avatarBase64);
            }
        }
        unreadDividerAt = 0;
        if (store != null) {
            ChatSummary opened = summaries.get(chatId);
            if (opened != null && opened.unread > 0) {
                long readWatermark = store.chatLastReadAt(chatId);
                if (readWatermark > 0) unreadDividerAt = readWatermark;
            }
            store.markChatOpened(chatId, null, 0L);
            if (opened != null) opened.unread = 0;
        }
        loadCachedMessages(chatId, peerUid);

        messageList = root.findViewById(R.id.message_list);
        applyScreenGuard();
        // Новые функции не мешают открытию переписки: каждая работает в своём щите.
        try { attachPollVotesListener(chatId); } catch (Throwable ignored) { }
        try { applyChatWallpaper(chatId); } catch (Throwable ignored) { }
        LinearLayoutManager messageLayout = new LinearLayoutManager(this);
        messageLayout.setStackFromEnd(true);
        messageList.setLayoutManager(messageLayout);
        // Анимации появления сообщений вернулись: пузырь вставляется плавно, а смена его
        // состояния (отправлено, прочитано) проходит без «мигания».
        androidx.recyclerview.widget.DefaultItemAnimator messageRowsAnimator =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        messageRowsAnimator.setSupportsChangeAnimations(false);
        messageList.setItemAnimator(messageRowsAnimator);
        messageListAdapter = new MessageListAdapter(uid, textSize(), isTextBold(),
                com.tsuyu.messenger.util.Settings.getBoolean("italic", false),
                new MessageListAdapter.Listener() {
                    @Override public void onOpenPayload(String payload) { openPayload(payload); }
                    @Override public void onMessageMenu(View anchor, ChatMessage message, String payload) { showMessageMenu(message, payload); }
                    @Override public void onRetry(ChatMessage message) { retryMessage(message); }
                    @Override public void onReact(ChatMessage message) { toggleHeart(message); }
                    @Override public void onGalleryImage(String encodedImage) { showImageDialog(encodedImage); }
                    @Override public void onReply(ChatMessage message) { startReply(message, message.localPayload); }
                    @Override public void onToggleSelect(ChatMessage message) { toggleMessageSelection(message); }
                    @Override public void onVote(ChatMessage message, String payload) { showPollVoteDialog(message, payload); }
                });
        messageList.setAdapter(messageListAdapter);
        chatScrollBottom = root.findViewById(R.id.chat_scroll_bottom);
        if (chatScrollBottom != null) {
            chatScrollBottom.setVisibility(View.GONE);
            chatScrollBottom.setOnClickListener(v -> {
                if (messageList != null && messageListAdapter != null && messageListAdapter.getItemCount() > 0) {
                    messageList.smoothScrollToPosition(messageListAdapter.getItemCount() - 1);
                }
                chatScrollBottom.setVisibility(View.GONE);
            });
            messageList.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                    if (chatScrollBottom == null || messageListAdapter == null) return;
                    androidx.recyclerview.widget.LinearLayoutManager manager =
                            (androidx.recyclerview.widget.LinearLayoutManager) recyclerView.getLayoutManager();
                    if (manager == null) return;
                    int last = manager.findLastVisibleItemPosition();
                    boolean awayFromBottom = last >= 0 && last < messageListAdapter.getItemCount() - 3;
                    chatScrollBottom.setVisibility(awayFromBottom ? View.VISIBLE : View.GONE);
                }
            });
        }
        chatEmptyState = root.findViewById(R.id.chat_empty_state);
        composer = root.findViewById(R.id.chat_composer);
        replyBanner = root.findViewById(R.id.reply_banner);
        replyBannerText = root.findViewById(R.id.reply_banner_text);
        root.findViewById(R.id.reply_cancel).setOnClickListener(v -> {
            replyToId = null;
            replyToText = null;
            replyBanner.setVisibility(View.GONE);
        });
        chatPinnedBar = root.findViewById(R.id.chat_pinned_bar);
        chatPinnedText = root.findViewById(R.id.pinned_message_text);
        View pinnedClose = root.findViewById(R.id.pinned_message_close);
        if (pinnedClose != null) pinnedClose.setOnClickListener(v -> unpinMessage());
        if (chatPinnedBar != null) {
            chatPinnedBar.setOnClickListener(v -> scrollToPinnedMessage());
            restorePinnedMessage(chatId);
        }
        composerPanel = root.findViewById(R.id.composer_panel);
        multiSelectBar = root.findViewById(R.id.multi_select_bar);
        multiSelectCount = root.findViewById(R.id.multi_select_count);
        if (multiSelectBar != null) {
            root.findViewById(R.id.multi_select_cancel).setOnClickListener(v -> exitSelectionMode());
            root.findViewById(R.id.multi_select_forward).setOnClickListener(v -> forwardSelectedMessages());
            root.findViewById(R.id.multi_select_delete).setOnClickListener(v -> deleteSelectedMessages());
        }
        root.findViewById(R.id.chat_attach).setOnClickListener(v -> showAttachmentMenu());
        chatVoiceButton = root.findViewById(R.id.chat_voice);
        if (chatVoiceButton != null) chatVoiceButton.setOnClickListener(v -> toggleVoiceRecord());
        chatCircleButton = root.findViewById(R.id.chat_circle);
        if (chatCircleButton != null) chatCircleButton.setOnClickListener(v -> openCircleCamera());
        chatStickerButton = root.findViewById(R.id.chat_sticker);
        if (chatStickerButton != null) chatStickerButton.setOnClickListener(v -> {
            pendingMediaKind = "sticker";
            avatarPicker.launch("image/*");
        });
        View sendButton = root.findViewById(R.id.chat_send);
        applySendButtonStyle(sendButton);
        sendButton.setEnabled(false);
        sendButton.setVisibility(View.GONE);
        sendButton.setOnClickListener(v -> sendCurrentText());
        setComposerMediaVisible(true);
        composer.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN && !event.isShiftPressed())) {
                sendCurrentText();
                return true;
            }
            return false;
        });
        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s != null && s.toString().trim().length() > 0;
                sendButton.setEnabled(hasText);
                sendButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
                setComposerMediaVisible(!hasText);
                if (activeChatId != null && !presenceManager.isGhostMode()) {
                    long now = System.currentTimeMillis();
                    if (s != null && s.length() > 0 && now - lastTypingWrite > 800) {
                        lastTypingWrite = now;
                        chatRepository.setTyping(activeChatId, uid, true);
                    }
                    ui.removeCallbacks(clearTyping);
                    if (s == null || s.length() == 0) chatRepository.setTyping(activeChatId, uid, false);
                    else ui.postDelayed(clearTyping, 1800);
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        profileRepository.getProfile(peerUid, profile -> {
            if (!"chat".equals(page) || !peerUid.equals(activePeerUid)) return;
            if (profile == null) {
                profile = new Profile();
                profile.uid = peerUid;
                profile.displayName = "Собеседник";
            }
            profile.uid = peerUid;
            peerProfile = profile;
            root.<TextView>findViewById(R.id.chat_peer_name).setText(profile.displayNameOrUsername());
            bindAvatar(avatar, avatarInitial, profile, profile.displayNameOrUsername(), profile.avatarBase64);
        });
        peerOnline = false;
        peerLastSeenVisible = false;
        peerLastSeen = 0;
        peerTyping = false;
        peerReadAt = 0;
        updateChatStatus();
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
                // Разбор всей истории уходит в фон: главный поток не ждёт перебора сообщений.
                final int generation = ++messagesGeneration;
                dataQueue.execute(() -> {
                    List<ChatMessage> messages = new ArrayList<>();
                    try {
                        for (DataSnapshot child : snapshot.getChildren()) {
                            ChatMessage message = child.getValue(ChatMessage.class);
                            if (message != null) { message.id = child.getKey(); messages.add(message); }
                        }
                    } catch (Throwable error) {
                        com.tsuyu.messenger.TsuyuLogs.crash(MainActivity.this, "разбор истории", error);
                        return;
                    }
                    ui.post(() -> {
                        if (generation != messagesGeneration) return;
                        if (store != null) {
                            ChatMessage newest = null;
                            for (ChatMessage message : messages) {
                                if (newest == null || message.sentAt >= newest.sentAt) newest = message;
                            }
                            if (newest != null) store.markChatOpened(chatId, newest.id, newest.sentAt);
                            ChatSummary openedSummary = summaries.get(chatId);
                            if (openedSummary != null) openedSummary.unread = 0;
                        }
                        try {
                            renderMessages(messages, peerUid);
                        } catch (Throwable error) {
                            recordChatError(error);
                        }
                        if (!presenceManager.isGhostMode()) chatRepository.markRead(chatId, uid);
                    });
                });
            }
            @Override public void onCancelled(DatabaseError error) { toast("Не удалось загрузить сообщения. Проверьте подключение и попробуйте снова."); }
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
        if (chatAvatarDot != null) {
            chatAvatarDot.setVisibility(peerOnline ? View.VISIBLE : View.GONE);
            if (peerOnline && chatAvatarDot.getAnimation() == null) {
                chatAvatarDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.online_pulse));
            } else if (!peerOnline) chatAvatarDot.clearAnimation();
        }
        if (chatStatus == null) return;
        if (peerTyping) {
            chatStatus.setText("печатает…");
            chatStatus.setTextColor(ContextCompat.getColor(this, R.color.tsuyu_blue));
        } else if (peerOnline) {
            chatStatus.setText("в сети");
            chatStatus.setTextColor(ContextCompat.getColor(this, R.color.tsuyu_secondary));
        } else if (peerLastSeenVisible && peerLastSeen > 0) {
            String date = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(peerLastSeen));
            chatStatus.setText("был(а) в сети в " + date);
            chatStatus.setTextColor(ContextCompat.getColor(this, R.color.tsuyu_secondary));
        } else {
            chatStatus.setText("не в сети");
            chatStatus.setTextColor(ContextCompat.getColor(this, R.color.tsuyu_secondary));
        }
    }

    private void attachReadReceiptListener(String chatId, String peerUid) {
        receiptRef = chatRepository.receipts(chatId).child(peerUid);
        receiptListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                if (!"chat".equals(page) || !chatId.equals(activeChatId)) return;
                peerReadAt = number(snapshot.getValue());
                // Прочитано — просто помечаем галочки: перечитывать всю историю не нужно.
                requestMessageRender();
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        receiptRef.addValueEventListener(receiptListener);
    }

    private void renderMessagesFromSnapshot(DataSnapshot snapshot, String peerUid) {
        // Разбор снимка — в фоне: главный поток занят только экраном.
        dataQueue.execute(() -> {
            List<ChatMessage> messages = new ArrayList<>();
            try {
                for (DataSnapshot child : snapshot.getChildren()) {
                    ChatMessage message = child.getValue(ChatMessage.class);
                    if (message != null) { message.id = child.getKey(); messages.add(message); }
                }
            } catch (Throwable error) {
                com.tsuyu.messenger.TsuyuLogs.crash(this, "разбор истории", error);
                return;
            }
            ui.post(() -> {
                if ("chat".equals(page) && peerUid != null && peerUid.equals(activePeerUid)) {
                    renderMessages(messages, peerUid);
                }
            });
        });
    }

    private void attachReactionsListener(String chatId) {
        reactionsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                messageReactions.clear();
        pollVotes.clear();
        pollVotesRef = null; pollVotesListener = null;
        burnRefreshChatId = null;
                for (DataSnapshot message : snapshot.getChildren()) {
                    Set<String> users = new HashSet<>();
                    for (DataSnapshot reaction : message.getChildren()) {
                        if ("heart".equals(reaction.getValue(String.class))) users.add(reaction.getKey());
                    }
                    if (!users.isEmpty()) messageReactions.put(message.getKey(), users);
                }
                if (chatId.equals(activeChatId)) renderMessages(conversationModel.lastRemoteMessages, activePeerUid);
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        chatRepository.root().child("chats").child(chatId).child("reactions").addValueEventListener(reactionsListener);
    }

    private void renderMessages(List<ChatMessage> messages, String peerUid) {
        if (!"chat".equals(page) || messageList == null || messageListAdapter == null || activeChatId == null) return;
        final String renderChatId = activeChatId;
        final boolean keepBottomPinned = messageListAdapter.getItemCount() == 0 || !messageList.canScrollVertically(1);
        final SignalE2ee activeCrypto = crypto;
        final LocalMessageCache activeCache = messageCache;
        lastRemoteChatId = renderChatId;
        conversationModel.lastRemoteMessages = new ArrayList<>(messages);

        Set<String> serverClientIds = new HashSet<>();
        renderingMessages = true;
        try {
            for (ChatMessage message : conversationModel.lastRemoteMessages) {
                if (message.clientId != null) {
                    serverClientIds.add(message.clientId);
                    reconcileOptimisticMessage(renderChatId, message);
                }
            }
        } finally {
            renderingMessages = false;
        }
        List<ChatMessage> visible = new ArrayList<>(conversationModel.lastRemoteMessages);
        for (ChatMessage local : optimisticMessages.values()) {
            if (!renderChatId.equals(local.localChatId)) continue;
            if (local.clientId != null && serverClientIds.contains(local.clientId)) continue;
            visible.add(local);
        }
        Set<String> visibleIds = new HashSet<>();
        for (ChatMessage visibleMessage : visible) if (visibleMessage.id != null) visibleIds.add(visibleMessage.id);
        for (ChatMessage cachedMessage : conversationModel.cachedMessages) {
            if (cachedMessage.id == null || !renderChatId.equals(cachedMessage.localChatId)) continue;
            if (!visibleIds.add(cachedMessage.id)) continue;
            visible.add(cachedMessage);
        }
        // Одна запись на сообщение: даже если оно пришло и из сети, и из кеша, и как
        // «отправляемое», на экране оно будет один раз.
        java.util.LinkedHashMap<String, ChatMessage> unique = new java.util.LinkedHashMap<>();
        for (ChatMessage message : visible) {
            // Номер отправителя важнее серверного: черновая копия и серверная копия одного
            // сообщения — это одна строка, а не две.
            String uniqueKey = message.clientId != null && !message.clientId.isEmpty() ? "client:" + message.clientId
                    : message.id != null && !message.id.isEmpty() ? "id:" + message.id
                    : "at:" + message.sentAt + ":" + message.senderUid;
            ChatMessage known = unique.get(uniqueKey);
            if (known == null) {
                unique.put(uniqueKey, message);
            } else if (known.localPayload == null && message.localPayload != null) {
                // Из двух копий оставляем ту, что уже расшифрована.
                unique.put(uniqueKey, message);
            }
        }
        visible = new ArrayList<>(unique.values());
        visible.sort((first, second) -> {
            int time = Long.compare(first.sentAt, second.sentAt);
            if (time != 0) return time;
            return String.valueOf(first.id).compareTo(String.valueOf(second.id));
        });

        List<MessageListAdapter.Row> rows = new ArrayList<>();
        final List<ChatMessage> persistBatch = new ArrayList<>();
        final List<String> persistPayloadBatch = new ArrayList<>();
        String lastDayKey = null;
        boolean unreadDividerPlaced = false;
        boolean hasBurnTimer = false;
        int visibleIndex = 0;
        for (ChatMessage message : visible) {
            visibleIndex++;
            String payload = message.localPayload;
            // Тексты этого чата уже прочитаны с диска одним заходом — берём готовое.
            if (payload == null && message.id != null) payload = payloadIndex.get(message.id);
            if (payload == null && activeCache != null && message.id != null) {
                // Не нашли — дочитываем в фоне и перерисуем: главный поток диск не читает.
                final String missingId = message.id;
                if (payloadLoading.add(missingId)) {
                    dataQueue.execute(() -> {
                        try {
                            String loaded = activeCache.get(renderChatId, missingId);
                            if (loaded != null) {
                                payloadIndex.put(missingId, loaded);
                                requestMessageRender();
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            payloadLoading.remove(missingId);
                        }
                    });
                }
            }
            if (payload != null) {
                // Фото и кружочки сразу уходят файлами в кеш на диске: в памяти остаётся ссылка,
                // поэтому переписка не растёт и не тормозит.
                payload = com.tsuyu.messenger.data.TsuyuMedia.spill(this, payload);
                message.localPayload = payload;
                // Расшифрованный текст уже на диске — тяжёлую копию в памяти не держим.
                if (message.cipher != null) message.cipher = null;
            }
            String key = renderChatId + ":" + message.id;
            try {
                if (burnSeconds(payload) > 0) hasBurnTimer = true;
                if (isBurnExpired(payload, message)) {
                    // Сообщение с истёкшим таймером сгорания исчезает само: у автора — из базы.
                    if (uid != null && uid.equals(message.senderUid) && message.id != null
                            && !message.id.startsWith("local_")) {
                        chatRepository.deleteMessage(renderChatId, message.id, NOOP_RESULT);
                    }
                    continue;
                }
            } catch (Throwable ignored) {
                // Таймер сгорания не имеет права ломать отрисовку переписки.
            }
            if (payload == null && decryptFailures.contains(key)) payload = "{\"kind\":\"decrypt_error\"}";
            if (payload == null && message.senderUid != null && !uid.equals(message.senderUid)
                    && message.cipher != null && decrypting.add(key)) {
                final String messageId = message.id;
                cryptoQueue.execute(() -> {
                    try {
                        byte[] plain = activeCrypto.decrypt(peerUid, message.signalType, message.cipher);
                        String text = com.tsuyu.messenger.data.TsuyuMedia.spill(this,
                                new String(plain, StandardCharsets.UTF_8));
                        if (activeCache != null) activeCache.put(renderChatId, messageId, text);
                        // Один общий запрос на перерисовку: если расшифровались сразу несколько
                        // сообщений, экран обновится один раз и мгновенно.
                        requestMessageRender();
                    } catch (Throwable error) {
                        decryptFailures.add(key);
                        requestMessageRender();
                    } finally { decrypting.remove(key); }
                });
            }
            if (message.sentAt > 0) {
                String dayKey = dayKey(message.sentAt);
                if (!dayKey.equals(lastDayKey)) {
                    rows.add(new MessageListAdapter.Row(dateLabelFor(message.sentAt)));
                    lastDayKey = dayKey;
                }
            }
            if (unreadDividerAt > 0 && !unreadDividerPlaced && message.senderUid != null
                    && !message.senderUid.equals(uid) && message.sentAt > unreadDividerAt) {
                rows.add(MessageListAdapter.Row.unreadDivider());
                unreadDividerPlaced = true;
            }
            if (payload != null && message.id != null && !message.id.startsWith("local_")
                    && visible.size() - visibleIndex < 90) {
                persistBatch.add(message);
                persistPayloadBatch.add(payload);
            }
            Set<String> reactions = messageReactions.get(message.id);
            int reactionCount = reactions == null ? 0 : reactions.size();
            boolean read = message.sentAt > 0 && peerReadAt >= message.sentAt;
            rows.add(new MessageListAdapter.Row(message, payload, reactionCount, read));
        }

        final TsuyuStore activeStore = store;
        if (activeStore != null && !persistBatch.isEmpty()) {
            final String storeChatId = renderChatId;
            final List<ChatMessage> batch = new ArrayList<>(persistBatch);
            final List<String> batchPayloads = new ArrayList<>(persistPayloadBatch);
            final long readWatermark = peerReadAt;
            dataQueue.execute(() -> {
                for (int i = 0; i < batch.size(); i++) {
                    ChatMessage message = batch.get(i);
                    int state = message.pending ? TsuyuStore.STATE_PENDING
                            : (message.failed ? TsuyuStore.STATE_FAILED
                            : (readWatermark > 0 && message.sentAt > 0 && readWatermark >= message.sentAt
                                    ? TsuyuStore.STATE_READ : TsuyuStore.STATE_SENT));
                    activeStore.upsertMessage(storeChatId, message, batchPayloads.get(i), state);
                }
            });
        }
        messageListAdapter.setTextStyle(textSize(), isTextBold(),
                com.tsuyu.messenger.util.Settings.getBoolean("italic", false));
        try {
            messageListAdapter.setPollVotes(pollVotes);
        } catch (Throwable ignored) { }
        messageListAdapter.submit(rows);
        if (hasBurnTimer) scheduleBurnRefresh(renderChatId);
        if (chatEmptyState != null) chatEmptyState.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (!rows.isEmpty() && keepBottomPinned) messageList.post(() -> {
            if (renderChatId.equals(activeChatId) && messageListAdapter.getItemCount() > 0) {
                messageList.scrollToPosition(messageListAdapter.getItemCount() - 1);
            }
        });
    }

    private void openPayload(String payload) {
        if (payload == null) return;
        try {
            JSONObject object = new JSONObject(payload);
            String kind = object.optString("kind", "text");
            if ("image".equals(kind)) showImageDialog(object.optString("data", ""));
            else if ("circle".equals(kind)) showVideoDialog(object.optString("data", ""), object.optString("mime", "video/mp4"), true);
            else if ("sticker".equals(kind)) showImageDialog(object.optString("data", ""));
            else if ("video".equals(kind)) showVideoDialog(object.optString("data", ""), object.optString("mime", "video/mp4"), false);
            else if ("audio".equals(kind) || "voice".equals(kind)) playAudio(object.optString("data", ""), object.optString("mime", "audio/mp4"));
            else if ("collage".equals(kind)) {
                JSONArray items = object.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    JSONObject first = items.optJSONObject(0);
                    if (first != null) showImageDialog(first.optString("data", ""));
                }
            }
        } catch (Throwable ignored) { }
    }

    private void showVideoDialog(String encoded, String mime, boolean circle) {
        dataQueue.execute(() -> {
            try {
                java.io.File file = com.tsuyu.messenger.data.TsuyuMedia.file(this, encoded);
                boolean temporary = false;
                if (file == null) {
                    byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
                    file = new java.io.File(getCacheDir(), "tsuyu-video-" + System.currentTimeMillis() + (mime.contains("webm") ? ".webm" : ".mp4"));
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) { output.write(bytes); }
                    temporary = true;
                }
                final boolean removeAfter = temporary;
                final java.io.File videoFile = file;
                runOnUiThread(() -> {
                    View content = getLayoutInflater().inflate(R.layout.dialog_video_viewer, null, false);
                    android.widget.VideoView video = content.findViewById(R.id.viewer_video);
                    if (circle) {
                        // A video note stays round, like on the web client.
                        int side = Math.round(Math.min(getResources().getDisplayMetrics().widthPixels,
                                getResources().getDisplayMetrics().heightPixels) * 0.78f);
                        android.widget.FrameLayout.LayoutParams params =
                                (android.widget.FrameLayout.LayoutParams) video.getLayoutParams();
                        params.width = side;
                        params.height = side;
                        params.gravity = android.view.Gravity.CENTER;
                        video.setLayoutParams(params);
                        video.setBackgroundResource(R.drawable.bg_circle_video);
                        video.setClipToOutline(true);
                    }
                    MaterialButton toggle = content.findViewById(R.id.viewer_video_toggle);
                    android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                    dialog.setContentView(content);
                    dialog.setOnDismissListener(ignored -> { video.stopPlayback(); if (removeAfter) videoFile.delete(); });
                    dialog.show();
                    if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
                    video.setVideoURI(Uri.fromFile(videoFile));
                    video.setOnPreparedListener(player -> {
                        video.start();
                        toggle.setIconResource(R.drawable.ic_pause);
                        toggle.setContentDescription("Пауза");
                    });
                    video.setOnCompletionListener(player -> {
                        toggle.setIconResource(R.drawable.ic_play);
                        toggle.setContentDescription("Воспроизвести");
                    });
                    toggle.setOnClickListener(v -> {
                        if (video.isPlaying()) {
                            video.pause();
                            toggle.setIconResource(R.drawable.ic_play);
                            toggle.setContentDescription("Воспроизвести");
                        } else {
                            video.start();
                            toggle.setIconResource(R.drawable.ic_pause);
                            toggle.setContentDescription("Пауза");
                        }
                    });
                });
            } catch (Throwable error) { runOnUiThread(() -> toast("Не удалось открыть видео.")); }
        });
    }

    private void playAudio(String encoded, String mime) {
        dataQueue.execute(() -> {
            try {
                java.io.File file = com.tsuyu.messenger.data.TsuyuMedia.file(this, encoded);
                if (file == null) {
                    byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
                    String ext = mime.contains("mpeg") ? ".mp3" : mime.contains("ogg") ? ".ogg" : ".m4a";
                    file = new java.io.File(getCacheDir(), "tsuyu-audio-" + System.currentTimeMillis() + ext);
                    try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) { output.write(bytes); }
                }
                final java.io.File audioFile = file;
                runOnUiThread(() -> {
                    try {
                        if (audioPlayer != null) { audioPlayer.release(); audioPlayer = null; }
                        audioPlayer = new android.media.MediaPlayer();
                        audioPlayer.setDataSource(audioFile.getAbsolutePath());
                        audioPlayer.setOnPreparedListener(android.media.MediaPlayer::start);
                        audioPlayer.setOnCompletionListener(mp -> { mp.release(); audioPlayer = null; });
                        audioPlayer.prepareAsync();
                    } catch (Throwable e) { toast("Не удалось воспроизвести аудио."); }
                });
            } catch (Throwable e) { runOnUiThread(() -> toast("Файл повреждён.")); }
        });
    }

    private android.media.MediaPlayer audioPlayer;

    private void showMessageMenu(ChatMessage message, String payload) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_message_actions, null, false);
        boolean persisted = message.id != null && !message.id.startsWith("local_") && !message.pending;
        boolean ownMessage = uid != null && uid.equals(message.senderUid);

        boolean pinnedAlready = message.id != null && message.id.equals(pinnedMessageId);
        View pinLabel = content.findViewById(R.id.message_pin_label);
        if (pinLabel instanceof TextView) {
            ((TextView) pinLabel).setText(pinnedAlready ? R.string.msg_unpin : R.string.msg_pin);
        }
        int rowState = persisted ? View.VISIBLE : View.GONE;
        content.findViewById(R.id.message_reply).setVisibility(rowState);
        content.findViewById(R.id.message_pin).setVisibility(rowState);
        content.findViewById(R.id.message_forward).setVisibility(rowState);
        content.findViewById(R.id.message_copy).setVisibility(rowState);
        content.findViewById(R.id.message_edit).setVisibility(persisted && ownMessage ? View.VISIBLE : View.GONE);

        content.findViewById(R.id.message_reply).setOnClickListener(v -> {
            sheet.dismiss();
            startReply(message, payload);
        });
        content.findViewById(R.id.message_pin).setOnClickListener(v -> {
            sheet.dismiss();
            if (pinnedAlready) unpinMessage(); else pinMessage(message, payload);
        });
        content.findViewById(R.id.message_copy).setOnClickListener(v -> {
            sheet.dismiss();
            copyMessageText(payload);
        });
        content.findViewById(R.id.message_cancel).setOnClickListener(v -> sheet.dismiss());
        content.findViewById(R.id.message_forward).setOnClickListener(v -> {
            sheet.dismiss();
            forwardMessage(payload);
        });
        content.findViewById(R.id.message_edit).setOnClickListener(v -> {
            sheet.dismiss();
            editMessage(message, payload);
        });
        content.findViewById(R.id.message_delete).setOnClickListener(v -> {
            sheet.dismiss();
            confirmDelete(message);
        });

        sheet.setContentView(content);
        sheet.show();
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
        if (payload == null) { toast("Сообщение загружается. Попробуйте ещё раз чуть позже."); return; }
        String old;
        try { old = new JSONObject(payload).optString("text", ""); } catch (Throwable e) { toast("Можно редактировать только текст."); return; }
        View editor = getLayoutInflater().inflate(R.layout.dialog_edit_message, null, false);
        TextInputEditText edit = editor.findViewById(R.id.edit_message_input);
        edit.setText(old);
        new AlertDialog.Builder(this).setTitle("Редактировать сообщение").setView(editor)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", (dialog, which) -> {
                    String text = edit.getText().toString().trim(); if (text.isEmpty()) return;
                    chatRepository.editText(activeChatId, message.id, uid, activePeerUid, crypto, text, new ChatRepository.Result<Void>() {
                        @Override public void success(Void ignored) {
                            try { JSONObject json = new JSONObject().put("version", 1).put("kind", "text").put("text", text); messageCache.put(activeChatId, message.id, json.toString()); }
                            catch (Throwable ignored2) { }
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
        if (payload == null) { toast("Сообщение загружается. Попробуйте ещё раз чуть позже."); return; }
        String text = textFromPayload(payload);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Tsuyu", text));
        toast("Текст скопирован. Выберите диалог и вставьте его.");
    }

    private void sendCurrentText() {
        if (composer == null || activeChatId == null || activePeerUid == null) return;
        String text = composer.getText() == null ? "" : composer.getText().toString().trim();
        if (text.isEmpty()) return;
        String chatId = activeChatId;
        String peerUid = activePeerUid;
        String senderUid = uid;
        String replyId = replyToId;
        try {
            JSONObject payload = new JSONObject().put("version", 1).put("kind", "text").put("text", text);
            if (replyId != null) payload.put("replyTo", replyId);
            if (pendingBurnSeconds > 0) payload.put("burn", pendingBurnSeconds);
            pendingBurnSeconds = 0;
            String payloadJson = payload.toString();
            composer.setText("");
            replyToId = null;
            replyToText = null;
            if (replyBanner != null) replyBanner.setVisibility(View.GONE);
            chatRepository.setTyping(chatId, senderUid, false);
            unreadDividerAt = 0;
            enqueueOptimisticMessage(chatId, peerUid, senderUid, payloadJson);
        } catch (Throwable error) {
            toast("Не удалось подготовить сообщение.");
        }
    }

    /** Creates the XML-backed bubble before handing the payload to asynchronous encryption/RTDB. */
    private ChatMessage enqueueOptimisticMessage(String chatId, String peerUid, String senderUid, String payloadJson) {
        String clientId = UUID.randomUUID().toString();
        ChatMessage local = new ChatMessage();
        local.id = "local_" + clientId;
        local.clientId = clientId;
        local.senderUid = senderUid;
        local.sentAt = System.currentTimeMillis();
        local.pending = true;
        local.localChatId = chatId;
        local.localPayload = payloadJson;
        optimisticMessages.put(clientId, local);

        ChatSummary summary = summaries.get(chatId);
        if (summary == null) {
            Profile peer = peerProfile != null && peerUid.equals(peerProfile.uid) ? peerProfile : new Profile();
            peer.uid = peerUid;
            if (peer.displayName == null) peer.displayName = "Собеседник";
            summary = new ChatSummary(chatId, peerUid, peer, local.sentAt, null);
            summaries.put(chatId, summary);
        }
        summary.lastAt = local.sentAt;
        summary.lastMessage = local;
        summary.encryptedPreview = null;
        summary.lastPreview = previewFromJson(payloadJson);
        summary.lastKind = kindFromJson(payloadJson);
        summary.lastState = TsuyuStore.STATE_PENDING;
        // Подпись строки уже есть (текст только что написан) — показываем её сразу.
        summary.previewText = summary.lastPreview == null ? "" : summary.lastPreview;
        // Всё, что касается диска, уходит в фон: пузырь показывается сразу, без ожидания
        // записи в кэш и в базу.
        final ChatSummary pendingSummary = summary;
        final ChatMessage pendingLocal = local;
        dataQueue.execute(() -> {
            try {
                if (messageCache != null) messageCache.put(chatId, pendingLocal.id, payloadJson);
                if (store != null) {
                    store.upsertMessage(chatId, pendingLocal, payloadJson, TsuyuStore.STATE_PENDING);
                    store.enqueue(clientId, chatId, peerUid, payloadJson);
                    persistSummary(pendingSummary);
                }
            } catch (Throwable ignored) { }
        });
        watchSummaryRealtime(chatId, peerUid);
        renderChatList();
        requestMessageRender();
        dispatchOptimisticMessage(local, peerUid);
        return local;
    }

    private void dispatchOptimisticMessage(ChatMessage local, String peerUid) {
        String chatId = local.localChatId;
        String senderUid = local.senderUid;
        if (chatId == null || peerUid == null || senderUid == null || crypto == null || local.localPayload == null) {
            local.pending = false;
            local.failed = true;
            if (store != null && local.id != null) store.updateState(local.id, TsuyuStore.STATE_FAILED);
            if (chatId != null && chatId.equals(activeChatId)) renderMessages(conversationModel.lastRemoteMessages, activePeerUid);
            return;
        }
        SignalE2ee activeCrypto = crypto;
        LocalMessageCache activeCache = messageCache;
        try {
            chatRepository.sendPayload(chatId, senderUid, peerUid, activeCrypto,
                    local.localPayload.getBytes(StandardCharsets.UTF_8), local.clientId,
                    new ChatRepository.Result<String>() {
                        @Override public void success(String messageId) {
                            ChatMessage current = optimisticMessages.get(local.clientId);
                            if (current != null) {
                                current.id = messageId;
                                current.pending = false;
                                current.failed = false;
                            }
                            payloadIndex.put(messageId, local.localPayload);
                            ChatSummary stored = summaries.get(chatId);
                            if (stored != null) stored.lastState = TsuyuStore.STATE_SENT;
                            // Запись в кэш и базу — в фоне: экран не ждёт диск.
                            dataQueue.execute(() -> {
                                try {
                                    if (activeCache != null) activeCache.put(chatId, messageId, local.localPayload);
                                    if (store != null && local.id != null) {
                                        store.promoteLocalMessage(local.id, messageId, local.localPayload);
                                        store.dequeue(local.clientId);
                                        if (stored != null) persistSummary(stored);
                                    }
                                } catch (Throwable ignored) { }
                            });
                            if (chatId.equals(activeChatId)) requestMessageRender();
                            ChatSummary summary = summaries.get(chatId);
                            if (summary != null && summary.lastMessage != null
                                    && local.clientId.equals(summary.lastMessage.clientId)) {
                                summary.lastMessage.id = messageId;
                                summary.lastMessage.pending = false;
                                renderChatList();
                            }
                        }
                        @Override public void error(String error) {
                            ChatMessage current = optimisticMessages.get(local.clientId);
                            if (current != null) {
                                current.pending = false;
                                current.failed = true;
                            }
                            ChatSummary stored = summaries.get(chatId);
                            if (stored != null) stored.lastState = TsuyuStore.STATE_FAILED;
                            dataQueue.execute(() -> {
                                try {
                                    if (store != null && local.id != null) {
                                        store.updateState(local.id, TsuyuStore.STATE_FAILED);
                                        store.noteAttempt(local.clientId);
                                        if (stored != null) persistSummary(stored);
                                    }
                                } catch (Throwable ignored) { }
                            });
                            if (chatId.equals(activeChatId)) requestMessageRender();
                            renderChatList();
                            toast(error);
                        }
                    });
        } catch (Throwable error) {
            local.pending = false;
            local.failed = true;
            if (store != null && local.id != null) store.updateState(local.id, TsuyuStore.STATE_FAILED);
            if (chatId.equals(activeChatId)) renderMessages(conversationModel.lastRemoteMessages, peerUid);
            toast("Не удалось отправить сообщение.");
        }
    }

    private void retryMessage(ChatMessage failed) {
        if (failed == null || !failed.failed || failed.localPayload == null || failed.localChatId == null) return;
        if (!failed.localChatId.equals(activeChatId)) {
            toast("Откройте этот чат, чтобы повторить отправку.");
            return;
        }
        optimisticMessages.remove(failed.clientId, failed);
        if (store != null) {
            if (failed.id != null) store.deleteMessage(failed.id);
            if (failed.clientId != null) store.dequeue(failed.clientId);
        }
        ChatMessage retry = new ChatMessage();
        retry.clientId = UUID.randomUUID().toString();
        retry.id = "local_" + retry.clientId;
        retry.senderUid = uid;
        retry.sentAt = System.currentTimeMillis();
        retry.pending = true;
        retry.localChatId = failed.localChatId;
        retry.localPayload = failed.localPayload;
        optimisticMessages.put(retry.clientId, retry);
        if (messageCache != null) messageCache.put(retry.localChatId, retry.id, retry.localPayload);
        if (store != null) {
            store.upsertMessage(retry.localChatId, retry, retry.localPayload, TsuyuStore.STATE_PENDING);
            if (activePeerUid != null) store.enqueue(retry.clientId, retry.localChatId, activePeerUid, retry.localPayload);
        }
        ChatSummary summary = summaries.get(retry.localChatId);
        if (summary != null) { summary.lastAt = retry.sentAt; summary.lastMessage = retry; renderChatList(); }
        renderMessages(conversationModel.lastRemoteMessages, activePeerUid);
        dispatchOptimisticMessage(retry, activePeerUid);
    }


    private void showChatMenu() { showChatMenu(null); }

    /** Bottom-sheet actions for the active chat or for a row long-pressed in the list. */
    private void showChatMenu(ChatSummary target) {
        final String chatId = target != null ? target.chatId : activeChatId;
        final String targetPeerUid = target != null ? target.peerUid : activePeerUid;
        if (chatId == null) return;
        final boolean fromList = target != null;
        ChatSummary known = summaries.get(chatId);
        boolean pinned = known != null && known.pinned;
        int unread = known == null ? 0 : known.unread;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_chat_actions, null, false);
        TextView pinTitle = content.findViewById(R.id.chat_menu_pin_title);
        TextView pinSubtitle = content.findViewById(R.id.chat_menu_pin_subtitle);
        if (pinned) {
            pinTitle.setText(R.string.chat_menu_unpin_title);
            pinSubtitle.setText(R.string.chat_menu_unpin_subtitle);
        }
        TextView readTitle = content.findViewById(R.id.chat_menu_read_title);
        TextView readSubtitle = content.findViewById(R.id.chat_menu_read_subtitle);
        if (unread > 0) {
            readTitle.setText("Отметить как прочитанное");
            readSubtitle.setText("Сбросить значок непрочитанного");
        } else {
            readTitle.setText(R.string.chat_menu_mark_unread_title);
            readSubtitle.setText(R.string.chat_menu_mark_unread_subtitle);
        }
        if (fromList) {
            content.findViewById(R.id.chat_menu_export).setVisibility(View.GONE);
            content.findViewById(R.id.chat_menu_keys).setVisibility(View.GONE);
        }
        final boolean currentlyPinned = pinned;
        final boolean currentlyUnread = unread > 0;
        content.findViewById(R.id.chat_menu_call).setOnClickListener(v -> {
            sheet.dismiss();
            startCall(chatId, targetPeerUid, false);
        });
        content.findViewById(R.id.chat_menu_video).setOnClickListener(v -> {
            sheet.dismiss();
            startCall(chatId, targetPeerUid, true);
        });
        content.findViewById(R.id.chat_menu_profile).setOnClickListener(v -> {
            sheet.dismiss();
            openPeerProfile(targetPeerUid);
        });
        content.findViewById(R.id.chat_menu_pin).setOnClickListener(v -> {
            sheet.dismiss();
            togglePinned(chatId, !currentlyPinned);
        });
        content.findViewById(R.id.chat_menu_read).setOnClickListener(v -> {
            sheet.dismiss();
            if (currentlyUnread) markChatRead(chatId); else markChatUnread(chatId);
        });
        content.findViewById(R.id.chat_menu_export).setOnClickListener(v -> {
            sheet.dismiss();
            showChat(chatId, targetPeerUid);
            exportCurrentChat();
        });
        content.findViewById(R.id.chat_menu_keys).setOnClickListener(v -> {
            sheet.dismiss();
            showKeySettings();
        });
        final boolean archived = isChatArchived(chatId);
        TextView archiveTitle = content.findViewById(R.id.chat_menu_archive_title);
        if (archiveTitle != null) archiveTitle.setText(archived ? R.string.chat_menu_unarchive_title : R.string.chat_menu_archive_title);
        TextView archiveSubtitle = content.findViewById(R.id.chat_menu_archive_subtitle);
        if (archiveSubtitle != null) archiveSubtitle.setText(archived ? R.string.chat_menu_unarchive_subtitle : R.string.chat_menu_archive_subtitle);
        content.findViewById(R.id.chat_menu_archive).setOnClickListener(v -> {
            sheet.dismiss();
            toggleChatArchive(chatId);
        });
        content.findViewById(R.id.chat_menu_select).setOnClickListener(v -> {
            sheet.dismiss();
            if (!"chat".equals(page) || !chatId.equals(activeChatId)) showChat(chatId, targetPeerUid);
            enterSelectionMode();
        });
        content.findViewById(R.id.chat_menu_folders).setOnClickListener(v -> {
            sheet.dismiss();
            showFolderPicker(chatId);
        });
        TextView guardSubtitle = content.findViewById(R.id.chat_menu_guard_subtitle);
        SharedPreferences guardPrefs = com.tsuyu.messenger.util.Settings.raw();
        boolean guardedChat = guardPrefs != null && guardPrefs
                .getStringSet("screen_guard_exceptions", Collections.emptySet()).contains(chatId);
        if (guardSubtitle != null) {
            guardSubtitle.setText(guardedChat ? R.string.guard_exception_on : R.string.guard_exception_off);
        }
        content.findViewById(R.id.chat_menu_guard).setOnClickListener(v -> {
            sheet.dismiss();
            toggleGuardException(chatId);
        });
        content.findViewById(R.id.chat_menu_wallpaper).setOnClickListener(v -> {
            sheet.dismiss();
            showWallpaperDialog(chatId, false);
        });
        content.findViewById(R.id.chat_menu_clear).setOnClickListener(v -> {
            sheet.dismiss();
            confirmClearHistory(chatId);
        });
        sheet.setContentView(content);
        sheet.show();
    }

    private void openPeerProfile(String peerUid) {
        if (peerUid == null) return;
        if (peerProfile == null || !peerUid.equals(peerProfile.uid)) {
            Profile cached = store == null ? null : store.getProfile(peerUid);
            if (cached == null) {
                cached = new Profile();
                cached.displayName = "Собеседник";
            }
            cached.uid = peerUid;
            peerProfile = cached;
        }
        showPeerProfile();
        profileRepository.getProfile(peerUid, profile -> {
            if (profile == null) return;
            profile.uid = peerUid;
            peerProfile = profile;
        });
    }

    private void togglePinned(String chatId, boolean pinned) {
        ChatSummary summary = summaries.get(chatId);
        if (summary != null) {
            summary.pinned = pinned;
            renderChatList();
        }
        if (store != null) store.setPinned(chatId, pinned);
        toast(getString(pinned ? R.string.chat_pinned_toast : R.string.chat_unpinned_toast));
    }

    private void markChatRead(String chatId) {
        ChatSummary summary = summaries.get(chatId);
        if (summary != null) {
            summary.unread = 0;
            renderChatList();
        }
        if (store != null) store.setUnread(chatId, 0);
    }

    private void markChatUnread(String chatId) {
        ChatSummary summary = summaries.get(chatId);
        if (summary != null) {
            summary.unread = Math.max(1, summary.unread);
            renderChatList();
        }
        if (store != null) store.setUnread(chatId, 1);
    }

    private void confirmClearHistory(final String chatId) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_clear_title)
                .setMessage(R.string.chat_clear_message)
                .setNegativeButton("Отмена", null)
                .setPositiveButton(R.string.chat_clear_confirm, (dialog, which) -> {
                    if (store != null) store.clearMessages(chatId);
                    ChatSummary summary = summaries.get(chatId);
                    if (summary != null) {
                        summary.lastMessage = null;
                        summary.lastPreview = null;
                        summary.unread = 0;
                        renderChatList();
                    }
                    if (chatId.equals(activeChatId)) {
                        conversationModel.cachedMessages = new ArrayList<>();
                        conversationModel.lastRemoteMessages = new ArrayList<>();
                        optimisticMessages.clear();
                        renderMessages(conversationModel.lastRemoteMessages, activePeerUid);
                    }
                    toast(getString(R.string.chat_clear_done));
                }).show();
    }

    private void showPeerProfile() {
        if (peerProfile == null) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_peer_profile, null, false);
        TextView name = content.findViewById(R.id.peer_profile_name);
        TextView username = content.findViewById(R.id.peer_profile_username);
        TextView about = content.findViewById(R.id.peer_profile_about);
        TextView fingerprint = content.findViewById(R.id.peer_fingerprint);
        ShapeableImageView avatar = content.findViewById(R.id.peer_avatar);
        TextView avatarInitial = content.findViewById(R.id.peer_avatar_initial);
        MaterialButton trustKey = content.findViewById(R.id.peer_trust_key);
        MaterialButton contactButton = content.findViewById(R.id.peer_contact_button);
        MaterialButton accessButton = content.findViewById(R.id.peer_access_button);

        String peerName = peerProfile.displayNameOrUsername();
        name.setText(peerName);
        username.setText(peerProfile.username == null ? "" : "@" + peerProfile.username);
        about.setText(peerProfile.about == null || peerProfile.about.isEmpty() ? "Нет описания" : peerProfile.about);
        bindAvatar(avatar, avatarInitial, peerProfile, peerName, peerProfile.avatarBase64);

        final Map<String, Object>[] peerBundle = new Map[]{null};
        String profilePeerUid = peerProfile.uid;
        SignalE2ee accountCrypto = crypto;
        database.getReference("users").child(profilePeerUid).child("keys").child("1").get().addOnSuccessListener(snapshot -> {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> bundle = (Map<String, Object>) snapshot.getValue();
                peerBundle[0] = bundle;
                fingerprint.setText(bundle == null ? "Код безопасности недоступен" : SignalE2ee.identityFingerprint(bundle));
            } catch (Throwable error) { fingerprint.setText("Не удалось загрузить код безопасности."); }
        });
        trustKey.setOnClickListener(v -> {
            if (peerBundle[0] == null || accountCrypto == null) { toast("Код безопасности пока недоступен."); return; }
            String shownFingerprint;
            try { shownFingerprint = SignalE2ee.identityFingerprint(peerBundle[0]); }
            catch (Throwable error) { toast("Не удалось проверить код безопасности."); return; }
            new AlertDialog.Builder(this).setTitle("Проверка кода безопасности")
                    .setMessage("Сравните этот код с собеседником лично или по телефону:\n\n" + shownFingerprint + "\n\nПодтвердите только в том случае, если коды совпадают.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Совпадает", (dialog, which) -> cryptoQueue.execute(() -> {
                        try {
                            accountCrypto.trustRemoteIdentity(profilePeerUid, peerBundle[0]);
                            runOnUiThread(() -> toast("Код безопасности подтверждён."));
                        } catch (Throwable error) { runOnUiThread(() -> toast("Не удалось подтвердить код безопасности.")); }
                    })).show();
        });

        final boolean[] isContact = {false};
        final boolean[] isAllowed = {false};
        DatabaseReference contactRef = database.getReference("users").child(uid).child("contacts").child(profilePeerUid);
        DatabaseReference allowRef = database.getReference("users").child(uid).child("publicSettings").child("allowList").child(profilePeerUid);
        contactRef.get().addOnSuccessListener(snapshot -> {
            isContact[0] = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
            contactButton.setText(isContact[0] ? "Удалить из контактов" : "Добавить в контакты");
        });
        allowRef.get().addOnSuccessListener(snapshot -> {
            isAllowed[0] = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
            accessButton.setText(isAllowed[0] ? "Отозвать доступ по списку" : "Разрешить доступ по списку");
        });
        contactButton.setOnClickListener(v -> contactRef.setValue(isContact[0] ? null : true)
                .addOnSuccessListener(unused -> {
                    isContact[0] = !isContact[0];
                    contactButton.setText(isContact[0] ? "Удалить из контактов" : "Добавить в контакты");
                }).addOnFailureListener(error -> toast("Не удалось обновить контакты.")));
        accessButton.setOnClickListener(v -> allowRef.setValue(isAllowed[0] ? null : true)
                .addOnSuccessListener(unused -> {
                    isAllowed[0] = !isAllowed[0];
                    accessButton.setText(isAllowed[0] ? "Отозвать доступ по списку" : "Разрешить доступ по списку");
                }).addOnFailureListener(error -> toast("Не удалось обновить список доступа.")));
        new AlertDialog.Builder(this).setView(content).setPositiveButton("Закрыть", null).show();
    }


    private void exportCurrentChat() {
        if (activeChatId == null) return;
        chatRepository.messages(activeChatId).get().addOnSuccessListener(snapshot -> {
            StringBuilder text = new StringBuilder("Переписка в Tsuyu\n");
            for (DataSnapshot child : snapshot.getChildren()) {
                ChatMessage message = child.getValue(ChatMessage.class);
                if (message == null || message.id == null) continue;
                String payload = messageCache.get(activeChatId, message.id);
                text.append(message.senderUid.equals(uid) ? "Вы" : (peerProfile == null ? "Собеседник" : peerProfile.displayNameOrUsername()))
                        .append(" · ").append(message.sentAt <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(message.sentAt))).append("\n")
                        .append(payload == null ? "[Сообщение недоступно]" : textFromPayload(payload)).append("\n\n");
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Tsuyu-chat.txt");
            try { startActivityForResult(intent, 701); } catch (Throwable e) { toast("Не удалось открыть проводник."); }
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
            } catch (Throwable e) { toast("Не удалось записать файл."); }
        }
    }

    private void showAttachmentMenu() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_attachments, null, false);
        content.findViewById(R.id.attachment_photo).setOnClickListener(v -> {
            sheet.dismiss();
            pendingMediaKind = "image";
            avatarPicker.launch("image/*");
        });
        content.findViewById(R.id.attachment_photos).setOnClickListener(v -> {
            sheet.dismiss();
            multiPhotoPicker.launch(new androidx.activity.result.PickVisualMediaRequest.Builder().build());
        });
        content.findViewById(R.id.attachment_video).setOnClickListener(v -> {
            sheet.dismiss();
            pendingMediaKind = "video";
            documentPicker.launch(new String[]{"video/*"});
        });
        content.findViewById(R.id.attachment_audio).setOnClickListener(v -> {
            sheet.dismiss();
            pendingMediaKind = "audio";
            documentPicker.launch(new String[]{"audio/*"});
        });
        content.findViewById(R.id.attachment_poll).setOnClickListener(v -> {
            sheet.dismiss();
            showPollDialog();
        });
        content.findViewById(R.id.attachment_contact).setOnClickListener(v -> {
            sheet.dismiss();
            showContactPicker();
        });
        content.findViewById(R.id.attachment_schedule).setOnClickListener(v -> {
            sheet.dismiss();
            showScheduleDialog();
        });
        content.findViewById(R.id.attachment_burn).setOnClickListener(v -> {
            sheet.dismiss();
            showBurnDialog();
        });
        MaterialButton voice = content.findViewById(R.id.attachment_voice);
        if (voiceRecorder != null) voice.setText("Остановить и отправить голосовое");
        voice.setOnClickListener(v -> {
            sheet.dismiss();
            toggleVoiceRecord();
        });
        sheet.setContentView(content);
        sheet.show();
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
        } catch (Throwable e) {
            if (voiceRecorder != null) { voiceRecorder.release(); voiceRecorder = null; }
            toast("Не удалось начать запись. Проверьте разрешение микрофона.");
        }
    }

    private void sendVoiceFile(java.io.File file) {
        if (activeChatId == null || activePeerUid == null) return;
        String chatId = activeChatId;
        String peerUid = activePeerUid;
        String senderUid = uid;
        dataQueue.execute(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                String payload = new JSONObject().put("version", 1).put("kind", "voice")
                        .put("mime", "audio/mp4").put("data", base64).toString();
                file.delete();
                runOnUiThread(() -> enqueueOptimisticMessage(chatId, peerUid, senderUid, payload));
            } catch (Throwable error) {
                runOnUiThread(() -> toast("Не удалось подготовить голосовое сообщение."));
            }
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
        String chatId = activeChatId;
        String peerUid = activePeerUid;
        String senderUid = uid;
        dataQueue.execute(() -> {
            try {
                String mime = getContentResolver().getType(uri);
                String base64 = "image".equals(kind) ? imageToBase64(uri, 1440, 950_000) : readBase64(uri, 3_000_000);
                JSONObject media = new JSONObject().put("version", 1).put("kind", kind)
                        .put("mime", mime == null ? "application/octet-stream" : mime)
                        .put("data", base64);
                if (pendingBurnSeconds > 0) media.put("burn", pendingBurnSeconds);
                pendingBurnSeconds = 0;
                String payload = media.toString();
                runOnUiThread(() -> enqueueOptimisticMessage(chatId, peerUid, senderUid, payload));
            } catch (Throwable error) {
                runOnUiThread(() -> toast("Файл слишком большой или не читается."));
            }
        });
    }

    private void sendPickedPhotos(List<Uri> uris) {
        if (activeChatId == null || activePeerUid == null || uris.isEmpty()) return;
        String chatId = activeChatId;
        String peerUid = activePeerUid;
        String senderUid = uid;
        dataQueue.execute(() -> {
            try {
                org.json.JSONArray items = new org.json.JSONArray();
                for (int i = 0; i < Math.min(6, uris.size()); i++) {
                    String data = imageToBase64(uris.get(i), 1200, 360_000);
                    items.put(new JSONObject().put("data", data).put("mime", "image/jpeg"));
                }
                String payload = new JSONObject().put("version", 1).put("kind", "collage").put("items", items).toString();
                runOnUiThread(() -> enqueueOptimisticMessage(chatId, peerUid, senderUid, payload));
            } catch (Throwable error) {
                runOnUiThread(() -> toast("Не удалось подготовить коллаж."));
            }
        });
    }

    private void showImageDialog(String imageBase64) {
        try {
            Bitmap bitmap = com.tsuyu.messenger.TsuyuPhoto.decodeScaled(
                    com.tsuyu.messenger.data.TsuyuMedia.bytes(this, imageBase64), 2048);
            if (bitmap == null) return;
            View content = getLayoutInflater().inflate(R.layout.dialog_image_viewer, null, false);
            ZoomImageView image = content.findViewById(R.id.viewer_image);
            image.setImageBitmap(bitmap);
            android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            content.findViewById(R.id.viewer_close).setOnClickListener(v -> dialog.dismiss());
            content.findViewById(R.id.viewer_download).setOnClickListener(v -> saveImage(bitmap));
            dialog.setContentView(content);
            dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
        } catch (Throwable ignored) { toast("Не удалось открыть изображение."); }
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
        } catch (Throwable e) { toast("Не удалось сохранить фото."); }
    }

    private void showSettings() {
        if (uid == null) return;
        markNoActiveChat();
        page = "settings";
        View root = showScreen(R.layout.screen_settings);
        bindScreenGuardSwitch(root);
        bindDynamicColorsSwitch(root);
        TextView versionView = root.findViewById(R.id.settings_version);
        if (versionView != null) {
            versionView.setText(versionLabel());
        }
        View logsRow = root.findViewById(R.id.settings_logs);
        if (logsRow != null) logsRow.setOnClickListener(v -> showLogsDialog(getString(R.string.logs_title)));
        View wallpaperRow = root.findViewById(R.id.settings_wallpaper);
        if (wallpaperRow != null) wallpaperRow.setOnClickListener(v -> showWallpaperDialog(null, true));

        // Приоритет мобильного трафика: приложение и звонки через сотовую сеть, не через Wi-Fi.
        com.google.android.material.materialswitch.MaterialSwitch priority =
                root.findViewById(R.id.mobile_priority_switch);
        TextView priorityStatus = root.findViewById(R.id.mobile_priority_status);
        if (priority != null) {
            priority.setChecked(com.tsuyu.messenger.call.NetworkPriority.enabled(this));
            if (priorityStatus != null) {
                priorityStatus.setText(com.tsuyu.messenger.call.NetworkPriority.status(this));
            }
            priority.setOnCheckedChangeListener((button, checked) -> {
                com.tsuyu.messenger.call.NetworkPriority.setEnabled(this, checked);
                toast(getString(checked ? R.string.mobile_priority_on : R.string.mobile_priority_off));
                if (priorityStatus != null) {
                    priorityStatus.setText(com.tsuyu.messenger.call.NetworkPriority.status(this));
                }
            });
        }
        MaterialToolbar toolbar = root.findViewById(R.id.settings_toolbar);
        toolbar.setNavigationOnClickListener(v -> showHome());
        View profileCard = root.findViewById(R.id.settings_profile_card);
        TextView profileName = root.findViewById(R.id.settings_profile_name);
        TextView profileHandle = root.findViewById(R.id.settings_profile_handle);
        ShapeableImageView avatar = root.findViewById(R.id.settings_avatar);
        TextView initial = root.findViewById(R.id.settings_avatar_initial);
        String displayName = myProfile == null ? "Мой профиль" : myProfile.displayNameOrUsername();
        profileName.setText(displayName);
        profileHandle.setText(myProfile == null || myProfile.username == null ? "" : "@" + myProfile.username);
        bindAvatar(avatar, initial, myProfile, displayName, myProfile == null ? null : myProfile.avatarBase64);
        profileCard.setOnClickListener(v -> showProfileEditor(false));

        MaterialSwitch syncSwitch = root.findViewById(R.id.background_sync_switch);
        syncSwitch.setChecked(com.tsuyu.messenger.util.Settings.getBoolean("background_sync", true));
        syncSwitch.setOnCheckedChangeListener((button, checked) -> {
            com.tsuyu.messenger.util.Settings.putBoolean("background_sync", checked);
            if (checked) startBackgroundSync(); else stopBackgroundSync();
        });
        root.findViewById(R.id.setting_privacy).setOnClickListener(v -> showPrivacySettings());
        root.findViewById(R.id.setting_customization).setOnClickListener(v -> showCustomization());
        root.findViewById(R.id.setting_notifications).setOnClickListener(v -> showNotificationSettings());
        root.findViewById(R.id.setting_keys).setOnClickListener(v -> showKeySettings());
        root.findViewById(R.id.settings_logout).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Выйти?")
                .setMessage("Вы сможете войти снова в любое время.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выйти", (dialog, which) -> { presenceManager.signOut(); auth.signOut(); })
                .show());
    }


    private void showPrivacySettings() {
        String[] keys = {"messages", "lastSeen", "photo", "about", "displayName"};
        Map<String, String> initial = new HashMap<>();
        for (String key : keys) initial.put(key, com.tsuyu.messenger.util.Settings.getString("privacy_" + key, "Все"));
        DatabaseReference settingsRef = database.getReference("users").child(uid).child("publicSettings");
        settingsRef.child("privacy").get().addOnSuccessListener(privacySnapshot -> {
            for (DataSnapshot child : privacySnapshot.getChildren()) {
                String value = child.getValue(String.class);
                if (value != null) initial.put(child.getKey(), value);
            }
            settingsRef.child("messages").get().addOnSuccessListener(messagesSnapshot -> {
                String value = messagesSnapshot.getValue(String.class);
                if (value != null) initial.put("messages", value);
                showPrivacySettingsLoaded(initial);
            }).addOnFailureListener(error -> showPrivacySettingsLoaded(initial));
        }).addOnFailureListener(error -> showPrivacySettingsLoaded(initial));
    }

    private void showPrivacySettingsLoaded(Map<String, String> initial) {
        View body = getLayoutInflater().inflate(R.layout.dialog_privacy, null, false);
        MaterialSwitch ghost = body.findViewById(R.id.privacy_ghost);
        ghost.setChecked(presenceManager.isGhostMode());
        String[] keys = {"messages", "lastSeen", "photo", "about", "displayName"};
        int[] spinnerIds = {R.id.privacy_messages, R.id.privacy_last_seen, R.id.privacy_photo, R.id.privacy_about, R.id.privacy_display_name};
        String[] choices = {"Все", "Только контакты", "Только @", "Никто"};
        Map<String, Object> privacy = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            android.widget.Spinner spinner = body.findViewById(spinnerIds[i]);
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                    R.layout.row_spinner_item, choices);
            adapter.setDropDownViewResource(R.layout.row_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            String saved = initial.getOrDefault(key, "Все");
            int selected = java.util.Arrays.asList(choices).indexOf(saved);
            if (selected < 0) selected = 0;
            spinner.setSelection(selected);
            privacy.put(key, choices[selected]);
            String selectedKey = key;
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    privacy.put(selectedKey, choices[position]);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
        }
        new AlertDialog.Builder(this).setTitle("Конфиденциальность").setView(body)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    Map<String, Object> publicSettings = new HashMap<>();
                    for (Map.Entry<String, Object> entry : privacy.entrySet()) {
                        com.tsuyu.messenger.util.Settings.putString("privacy_" + entry.getKey(), String.valueOf(entry.getValue()));
                        if ("messages".equals(entry.getKey())) publicSettings.put("messages", entry.getValue());
                        else publicSettings.put("privacy/" + entry.getKey(), entry.getValue());
                    }
                    Map<String, Object> changes = new HashMap<>();
                    for (Map.Entry<String, Object> entry : publicSettings.entrySet()) {
                        changes.put("users/" + uid + "/publicSettings/" + entry.getKey(), entry.getValue());
                    }
                    changes.put("users/" + uid + "/private/settings/ghost", ghost.isChecked());
                    database.getReference().updateChildren(changes).addOnSuccessListener(unused -> {
                        presenceManager.setGhostMode(uid, ghost.isChecked());
                        if (activeChatId != null && !ghost.isChecked()) chatRepository.markRead(activeChatId, uid);
                        toast("Настройки приватности сохранены.");
                    }).addOnFailureListener(error -> toast("Не удалось сохранить настройки конфиденциальности."));
                }).show();
    }

    private void showCustomization() {
        View body = getLayoutInflater().inflate(R.layout.dialog_customization, null, false);
        Slider size = body.findViewById(R.id.customization_text_size);
        int current = com.tsuyu.messenger.util.Settings.getInt("text_size", 15);
        size.setValue(Math.max(12, Math.min(22, current)));
        Slider iconSize = body.findViewById(R.id.customization_icon_size);
        iconSize.setValue(Math.max(40, Math.min(64, com.tsuyu.messenger.util.Settings.getInt("icon_size", 54))));
        Slider mediaSize = body.findViewById(R.id.customization_media_size);
        mediaSize.setValue(Math.max(60, Math.min(100, com.tsuyu.messenger.util.Settings.getInt("media_scale", 100))));
        MaterialCheckBox bold = body.findViewById(R.id.customization_bold);
        MaterialCheckBox italic = body.findViewById(R.id.customization_italic);
        bold.setChecked(isTextBold());
        italic.setChecked(com.tsuyu.messenger.util.Settings.getBoolean("italic", false));
        com.google.android.material.materialswitch.MaterialSwitch sendDark =
                body.findViewById(R.id.customization_send_dark);
        sendDark.setChecked(com.tsuyu.messenger.util.Settings.getBoolean("send_dark", false));
        // Ползунки показывают своё значение прямо над собой: видно, что выставляешь.
        TextView textLabel = body.findViewById(R.id.customization_text_label);
        TextView iconLabel = body.findViewById(R.id.customization_icon_label);
        TextView mediaLabel = body.findViewById(R.id.customization_media_label);
        size.addOnChangeListener((slider, value, fromUser) ->
                textLabel.setText(getString(R.string.customization_text_size) + ": " + Math.round(value)));
        iconSize.addOnChangeListener((slider, value, fromUser) ->
                iconLabel.setText(getString(R.string.customization_icon_size) + ": " + Math.round(value) + " dp"));
        mediaSize.addOnChangeListener((slider, value, fromUser) ->
                mediaLabel.setText(getString(R.string.customization_media_size) + ": " + Math.round(value) + "%"));
        textLabel.setText(getString(R.string.customization_text_size) + ": " + Math.round(size.getValue()));
        iconLabel.setText(getString(R.string.customization_icon_size) + ": " + Math.round(iconSize.getValue()) + " dp");
        mediaLabel.setText(getString(R.string.customization_media_size) + ": " + Math.round(mediaSize.getValue()) + "%");
        AlertDialog customizationDialog = new AlertDialog.Builder(this)
                .setTitle("Кастомизация")
                .setView(body)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    com.tsuyu.messenger.util.Settings.putInt("text_size", Math.round(size.getValue()));
                    com.tsuyu.messenger.util.Settings.putInt("icon_size", Math.round(iconSize.getValue()));
                    com.tsuyu.messenger.util.Settings.putInt("media_scale", Math.round(mediaSize.getValue()));
                    com.tsuyu.messenger.util.Settings.putBoolean("bold", bold.isChecked());
                    com.tsuyu.messenger.util.Settings.putBoolean("italic", italic.isChecked());
                    com.tsuyu.messenger.util.Settings.putBoolean("send_dark", sendDark.isChecked());
                    applySendButtonStyle(findViewById(R.id.chat_send));
                    if (chatListAdapter != null) renderChatList();
                    if (messageListAdapter != null && messageList != null && "chat".equals(page)) {
                        renderMessages(conversationModel.lastRemoteMessages, activePeerUid);
                    }
                })
                .create();
        View wallpaperRow = body.findViewById(R.id.customization_wallpaper);
        if (wallpaperRow != null) {
            wallpaperRow.setOnClickListener(v -> {
                customizationDialog.dismiss();
                showWallpaperDialog(null, true);
            });
        }
        customizationDialog.show();
    }

    private void showNotificationSettings() {
        View body = getLayoutInflater().inflate(R.layout.dialog_notifications, null, false);
        MaterialSwitch sound = body.findViewById(R.id.notifications_sound_switch);
        MaterialButton choose = body.findViewById(R.id.notifications_choose_sound);
        sound.setChecked(NotificationSounds.isEnabled(this));
        sound.setOnCheckedChangeListener((button, checked) -> NotificationSounds.setEnabled(this, checked));
        choose.setOnClickListener(v -> {
            try { pendingMediaKind = "sound"; documentPicker.launch(new String[]{"audio/mpeg", "audio/*"}); }
            catch (Throwable error) { toast("Не удалось открыть выбор файла."); }
        });
        new AlertDialog.Builder(this).setTitle("Уведомления").setView(body).setNegativeButton("Закрыть", null).show();
    }

    private void showKeySettings() {
        View body = getLayoutInflater().inflate(R.layout.dialog_key_settings, null, false);
        TextView fingerprint = body.findViewById(R.id.key_fingerprint);
        MaterialButton copyPublic = body.findViewById(R.id.key_copy_bundle);
        MaterialButton rotate = body.findViewById(R.id.key_rotate);
        try {
            Map<String, Object> ownBundle = crypto.publicKeyBundle();
            String safetyCode = SignalE2ee.identityFingerprint(ownBundle);
            fingerprint.setText(safetyCode);
            copyPublic.setOnClickListener(v -> {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                        ClipData.newPlainText("Код безопасности", safetyCode));
                toast("Код скопирован.");
            });
        } catch (Throwable error) {
            fingerprint.setText("Не удалось загрузить код безопасности.");
            copyPublic.setEnabled(false);
        }
        rotate.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Обновить код безопасности?")
                .setMessage("После обновления попросите собеседников сверить новый код. Старые сообщения могут стать недоступны. Продолжить?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Обновить", (dialog, which) -> rotateIdentity()).show());
        new AlertDialog.Builder(this).setTitle("Защита сообщений").setView(body)
                .setPositiveButton("Закрыть", null).show();
    }

    private void rotateIdentity() {
        cryptoQueue.execute(() -> {
            try {
                crypto.rotateIdentity();
                Map<String, Object> bundle = crypto.publicKeyBundle();
                Tasks.await(database.getReference("users").child(uid).child("keys").child("1").setValue(bundle));
                runOnUiThread(() -> toast("Код безопасности обновлён. Попросите собеседника сверить его."));
            } catch (Throwable error) { runOnUiThread(() -> toast("Не удалось обновить код безопасности.")); }
        });
    }

    /**
     * Показывает экран как отдельный фрагмент: у каждого экрана свой класс со своим
     * жизненным циклом, а не общий «мешок» в одном классе. Переключение мгновенное —
     * без переходов и затуханий.
     */
    private View showScreen(int layoutResource) {
        if (screenHost == null) {
            setContentView(R.layout.activity_main);
            screenHost = findViewById(R.id.screen_host);
        }
        TsuyuScreen screen = screenFor(layoutResource);
        String tag = TsuyuScreen.tagFor(layoutResource);
        TsuyuScreen previous = currentScreenFragment;
        if (previous != null && !previous.equals(screen)) {
            try { previous.onScreenHidden(); } catch (Throwable ignored) { }
        }
        try {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.screen_host, screen, tag)
                    .commitNow();
        } catch (Throwable error) {
            com.tsuyu.messenger.TsuyuLogs.crash(this, "показ экрана", error);
        }
        View next = screen.getView();
        if (next == null) {
            // Фрагмент не отдал разметку: рисуем напрямую, чтобы экран не остался пустым.
            next = getLayoutInflater().inflate(layoutResource, screenHost, false);
            screenHost.removeAllViews();
            screenHost.addView(next);
        }
        currentScreen = next;
        currentScreenFragment = screen;
        if (next != null) {
            try { screen.onScreenShown(next); } catch (Throwable ignored) { }
        }
        return next;
    }

    private TsuyuScreen screenFor(int layoutResource) {
        if (currentScreenFragment != null && currentScreenFragment.layoutId() == layoutResource) {
            return currentScreenFragment;
        }
        if (layoutResource == R.layout.screen_home) return new com.tsuyu.messenger.ui.screens.HomeFragment();
        if (layoutResource == R.layout.screen_chat) return new com.tsuyu.messenger.ui.screens.ChatFragment();
        if (layoutResource == R.layout.screen_settings) return new com.tsuyu.messenger.ui.screens.SettingsFragment();
        if (layoutResource == R.layout.screen_search) return new com.tsuyu.messenger.ui.screens.SearchFragment();
        if (layoutResource == R.layout.screen_profile_editor) return new com.tsuyu.messenger.ui.screens.ProfileFragment();
        return new com.tsuyu.messenger.ui.screens.AuthFragment();
    }


    private void bindAvatar(ShapeableImageView image, TextView fallback, Profile profile,
                            String displayName, String encoded) {
        String label = profile == null ? null : profile.displayName;
        if (label == null || label.trim().isEmpty()) label = profile == null ? null : profile.username;
        if (label == null || label.trim().isEmpty()) label = displayName;
        label = label == null ? "T" : label.trim().replaceFirst("^@", "");
        fallback.setText(label.isEmpty() ? "T" : label.substring(0, 1).toUpperCase(Locale.ROOT));
        if (encoded == null || encoded.isEmpty()) {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            return;
        }
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            Bitmap bitmap = com.tsuyu.messenger.TsuyuPhoto.decodeScaled(bytes, 512);
            if (bitmap == null) throw new IllegalArgumentException("Invalid avatar data");
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
        } catch (Throwable error) {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
        }
    }

    private void updateDraftAvatarPreview() {
        if (!"profile_edit".equals(page) || currentScreen == null) return;
        ShapeableImageView image = currentScreen.findViewById(R.id.profile_avatar);
        TextView initial = currentScreen.findViewById(R.id.profile_avatar_initial);
        TextInputEditText name = currentScreen.findViewById(R.id.profile_name);
        Profile draft = new Profile();
        draft.uid = uid;
        draft.displayName = name == null || name.getText() == null ? null : name.getText().toString();
        draft.username = myProfile == null ? null : myProfile.username;
        bindAvatar(image, initial, draft, draft.displayName, draftAvatarBase64);
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
            if ("decrypt_error".equals(kind)) return "Не удалось открыть сообщение";
            if ("image".equals(kind) || "collage".equals(kind)) return "Фото";
            if ("video".equals(kind)) return "Видео";
            if ("voice".equals(kind)) return "Голосовое сообщение";
            if ("audio".equals(kind)) return "Аудиофайл";
            if ("circle".equals(kind)) return "Кружочек";
            if ("sticker".equals(kind)) return "Стикер";
            return "Сообщение";
        } catch (Throwable e) { return "Сообщение"; }
    }

    private String textFromPayload(String payload) {
        try {
            JSONObject object = new JSONObject(payload);
            String kind = object.optString("kind", "text");
            if ("text".equals(kind)) return object.optString("text", "");
            if ("decrypt_error".equals(kind)) return "Не удалось открыть сообщение";
            if ("image".equals(kind) || "collage".equals(kind)) return "Фото";
            if ("video".equals(kind)) return "Видео";
            if ("voice".equals(kind)) return "Голосовое сообщение";
            if ("audio".equals(kind)) return "Аудиофайл";
            if ("circle".equals(kind)) return "Кружочек";
            if ("sticker".equals(kind)) return "Стикер";
            return "Сообщение";
        } catch (Throwable e) { return "Сообщение"; }
    }

    private void detachChatListeners() {
        if (activeChatId != null) {
            if (messagesListener != null) chatRepository.messages(activeChatId).removeEventListener(messagesListener);
            if (typingListener != null && activePeerUid != null) chatRepository.typing(activeChatId).child(activePeerUid).removeEventListener(typingListener);
            if (peerPresenceRef != null && peerPresenceListener != null) peerPresenceRef.removeEventListener(peerPresenceListener);
            if (receiptRef != null && receiptListener != null) receiptRef.removeEventListener(receiptListener);
            if (peerLastSeenRef != null && peerLastSeenListener != null) peerLastSeenRef.removeEventListener(peerLastSeenListener);
            if (reactionsListener != null) chatRepository.root().child("chats").child(activeChatId).child("reactions").removeEventListener(reactionsListener);
            if (pollVotesRef != null && pollVotesListener != null) pollVotesRef.removeEventListener(pollVotesListener);
            if (uid != null) chatRepository.setTyping(activeChatId, uid, false);
        }
        markNoActiveChat();
        messagesListener = null; typingListener = null; reactionsListener = null; peerPresenceListener = null; peerPresenceRef = null;
        peerLastSeenListener = null; peerLastSeenRef = null; receiptListener = null; receiptRef = null; peerReadAt = 0;
        peerTyping = false; peerOnline = false; peerLastSeenVisible = false; peerLastSeen = 0;
        messageList = null; messageListAdapter = null; chatEmptyState = null; chatScrollBottom = null;
        conversationModel.cachedMessages = new ArrayList<>();
        unreadDividerAt = 0;
        composer = null; replyBanner = null; replyBannerText = null; chatStatus = null; chatAvatarDot = null;
        ui.removeCallbacks(clearTyping);
        messageReactions.clear();
    }

    // Настройки берём из памяти: чтение файла настроек на каждую строку тормозило отрисовку.
    private int textSize() { return com.tsuyu.messenger.util.Settings.getInt("text_size", 15); }
    private boolean isTextBold() { return com.tsuyu.messenger.util.Settings.getBoolean("bold", false); }
    /** v3-дизайн: «Защита от скриншотов» — системный флаг, снимки и превью в задачах блокируются. */
    private void applyScreenGuard() {
        SharedPreferences guardPrefs = com.tsuyu.messenger.util.Settings.raw();
        boolean guarded = com.tsuyu.messenger.util.Settings.getBoolean("screen_guard", false);
        // Исключение: в отмеченных чатах защита снимается, остальное приложение остаётся закрытым.
        if (guarded && guardPrefs != null && activeChatId != null && "chat".equals(page)
                && guardPrefs.getStringSet("screen_guard_exceptions", Collections.emptySet())
                        .contains(activeChatId)) {
            guarded = false;
        }
        if (guarded) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    /**
     * Material You: оформление подстраивается под обои телефона. По умолчанию выключено —
     * фирменные цвета Tsuyu остаются как есть, пока человек сам не включит.
     */
    private void bindDynamicColorsSwitch(View root) {
        com.google.android.material.materialswitch.MaterialSwitch toggle =
                root.findViewById(R.id.dynamic_colors_switch);
        if (toggle == null) return;
        toggle.setChecked(com.tsuyu.messenger.util.Settings.getBoolean("dynamic_colors", false));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            com.tsuyu.messenger.util.Settings.putBoolean("dynamic_colors", checked);
            toast(checked ? "Цвета системы: включены" : "Цвета Tsuyu: включены");
            // Оформление применяется сразу: экран пересобирается с новыми цветами.
            recreate();
        });
        View row = (View) toggle.getParent();
        if (row != null) row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
    }

    private void bindScreenGuardSwitch(View root) {
        com.google.android.material.materialswitch.MaterialSwitch guard = root.findViewById(R.id.screen_guard_switch);
        if (guard == null) return;
        guard.setChecked(com.tsuyu.messenger.util.Settings.getBoolean("screen_guard", false));
        guard.setOnCheckedChangeListener((button, checked) -> {
            com.tsuyu.messenger.util.Settings.putBoolean("screen_guard", checked);
            applyScreenGuard();
        });
        View row = (View) guard.getParent();
        if (row != null) row.setOnClickListener(v -> guard.setChecked(!guard.isChecked()));
    }

    /** Версия и номер сборки — их видно в самом приложении, чтобы точно знать, что установлено. */
    private String versionLabel() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "Tsuyu " + info.versionName + " · сборка " + info.versionCode;
        } catch (Throwable ignored) {
            return "Tsuyu";
        }
    }

    /** Причина последнего сбоя — прямо на экране: её можно скопировать и прислать. */
    /** Окно с журналом: сбой и все события можно скопировать или отправить без компьютера. */
    private void showCrashReportIfAny() {
        if (isFinishing() || isDestroyed()) return;
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("tsuyu_crash", MODE_PRIVATE);
            long when = prefs.getLong("at", 0L);
            if (when <= 0L || prefs.getString("last", null) == null) return;
            // Показываем один раз на каждый сбой, а не при каждом возврате в список чатов.
            if (when <= prefs.getLong("shown", 0L)) return;
            prefs.edit().putLong("shown", when).apply();
            showLogsDialog(getString(R.string.crash_title) + " · " + scheduleTimeLabel(when));
        } catch (Throwable ignored) { }
    }

    /** Журнал приложения: полностью на экране, с копированием и отправкой. */
    private void showLogsDialog(String title) {
        if (isFinishing() || isDestroyed()) return;
        try {
            View content = getLayoutInflater().inflate(R.layout.dialog_logs, null);
            TextView body = content.findViewById(R.id.dialog_logs_text);
            if (body != null) body.setText(com.tsuyu.messenger.TsuyuLogs.read(this));
            final AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(title == null ? getString(R.string.logs_title) : title)
                    .setView(content)
                    .setNegativeButton(R.string.crash_close, null)
                    .create();
            View copy = content.findViewById(R.id.dialog_logs_copy);
            if (copy != null) copy.setOnClickListener(v -> {
                String text = com.tsuyu.messenger.TsuyuLogs.read(this);
                if (com.tsuyu.messenger.TsuyuLogs.copy(this, text)) toast(getString(R.string.logs_copied));
                else toast(getString(R.string.logs_copy_failed));
            });
            View share = content.findViewById(R.id.dialog_logs_share);
            if (share != null) share.setOnClickListener(v -> {
                try {
                    startActivity(com.tsuyu.messenger.TsuyuLogs.share(com.tsuyu.messenger.TsuyuLogs.read(this)));
                } catch (Throwable error) {
                    toast(getString(R.string.logs_copy_failed));
                }
            });
            View clear = content.findViewById(R.id.dialog_logs_clear);
            if (clear != null) clear.setOnClickListener(v -> {
                com.tsuyu.messenger.TsuyuLogs.clear(this);
                if (body != null) body.setText(com.tsuyu.messenger.TsuyuLogs.read(this));
                toast(getString(R.string.logs_empty));
            });
            dialog.show();
        } catch (Throwable error) {
            com.tsuyu.messenger.TsuyuLogs.crash(this, "окно журнала", error);
            // Если окно не получилось — хотя бы сразу кладём журнал в буфер обмена.
            com.tsuyu.messenger.TsuyuLogs.copy(this, com.tsuyu.messenger.TsuyuLogs.read(this));
            toast(getString(R.string.logs_copied));
        }
    }


    /**
     * Один заход на диск: читаем готовые тексты всего чата и складываем в память.
     * Дальше переписка рисуется без обращения к хранилищу — поэтому мгновенно.
     */
    private void preloadPayloadIndex(final String chatId) {
        payloadIndex.clear();
        final TsuyuStore activeStore = store;
        final LocalMessageCache activeCache = messageCache;
        if (activeStore == null && activeCache == null) return;
        dataQueue.execute(() -> {
            try {
                if (activeStore != null) {
                    List<ChatMessage> stored = activeStore.messages(chatId, 200);
                    for (ChatMessage message : stored) {
                        if (message.id != null && message.localPayload != null) {
                            payloadIndex.put(message.id, message.localPayload);
                        }
                    }
                }
            } catch (Throwable ignored) { }
            if (!chatId.equals(activeChatId)) return;
            requestMessageRender();
        });
    }

    /**
     * Просьба перерисовать переписку. Несколько событий подряд (расшифровка, реакции,
     * опрос) собираются в одну перерисовку — экран обновляется ровно один раз.
     */
    private void requestMessageRender() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // Мы уже на главном потоке — рисуем в тот же кадр, без ожидания очереди.
            ui.removeCallbacks(renderPending);
            renderPending.run();
            return;
        }
        ui.removeCallbacks(renderPending);
        ui.post(renderPending);
    }

    private final Runnable renderPending = () -> {
        String chatId = activeChatId;
        if (chatId == null || !"chat".equals(page)) return;
        if (!chatId.equals(lastRemoteChatId)) return;
        renderMessages(conversationModel.lastRemoteMessages, activePeerUid);
    };

    /** Список чатов снова на экране: частые подписки возвращаются. */
    public void onHomeScreenShown() {
        try { updateSummaryWatches(); } catch (Throwable ignored) { }
    }

    /** Список чатов ушёл с экрана: подписки «в сети» и «печатает» снимаются. */
    public void onHomeScreenLeft() {
        try { detachSummaryWatches(); } catch (Throwable ignored) { }
    }

    /** Экран переписки закрыт: подписки на сообщения, реакции и метки сняты. */
    public void onChatScreenLeft() {
        try { detachChatListeners(); } catch (Throwable ignored) { }
    }

    /** Кнопка отправки: по умолчанию как на сайте — синий круг и белая стрелка; можно сделать тёмной. */
    private void applySendButtonStyle(View sendButton) {
        if (sendButton == null) return;
        boolean dark = com.tsuyu.messenger.util.Settings.getBoolean("send_dark", false);
        sendButton.setBackgroundResource(dark ? R.drawable.bg_send_dark : R.drawable.bg_scroll_fab);
        if (sendButton instanceof android.widget.ImageView) {
            ((android.widget.ImageView) sendButton).setImageTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.tsuyu_white)));
        }
    }

    private void markNoActiveChat() {
        com.tsuyu.messenger.util.Settings.remove("active_chat");
        applyScreenGuard();
    }
    private void startBackgroundSync() {
        if (uid == null || !com.tsuyu.messenger.util.Settings.getBoolean("background_sync", true)) return;
        com.tsuyu.messenger.util.Settings.putString("sync_uid", uid);
        try {
            Intent service = new Intent(this, com.tsuyu.messenger.notifications.TsuyuSyncService.class)
                    .putExtra(com.tsuyu.messenger.notifications.TsuyuSyncService.EXTRA_UID, uid);
            ContextCompat.startForegroundService(this, service);
        } catch (Throwable e) { toast("Фоновая синхронизация недоступна. Проверьте уведомления в настройках Android."); }
    }
    private void stopBackgroundSync() {
        com.tsuyu.messenger.util.Settings.remove("sync_uid");
        try { stopService(new Intent(this, com.tsuyu.messenger.notifications.TsuyuSyncService.class)); }
        catch (Throwable ignored) { }
    }
    private void detachRealtimeListeners() {
        detachUserChatsListener();
        detachChatListeners();
        if (connectionListener != null && database != null) {
            database.getReference(".info/connected").removeEventListener(connectionListener);
            connectionListener = null;
        }
        homeFilters = null;
        homeOfflineBanner = null;
        homeLoadingState = null;
        setSkeletonShimmer(false);
        homeEmptyTitle = null;
        homeEmptyDescription = null;
        homeFindPeople = null;
        if (presenceManager != null) presenceManager.signOut();
    }
    // ------------------------------------------------------------------
    // Offline-first local layer: instant list, cached history, outbox replay
    // ------------------------------------------------------------------

    /** Paints the device copy of the chat list before any network round-trip happens. */
    private boolean seedFromLocalCache() {
        if (store == null) return false;
        boolean hadData = false;
        Profile cachedProfile = store.getProfile(uid);
        if (cachedProfile != null && cachedProfile.username != null && !cachedProfile.username.trim().isEmpty()) {
            // Аватар в кеше лежит файлом: для отправки в сеть разворачиваем его обратно один раз.
            if (cachedProfile.avatarBase64 != null && cachedProfile.avatarBase64.startsWith("file:")) {
                byte[] raw = com.tsuyu.messenger.data.TsuyuMedia.bytes(this, cachedProfile.avatarBase64);
                cachedProfile.avatarBase64 = raw == null ? null
                        : android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
            }
            myProfile = cachedProfile;
            hadData = true;
        }
        List<ChatSummary> cached = store.listChats();
        for (ChatSummary summary : cached) {
            if (summary.chatId == null) continue;
            summaries.put(summary.chatId, summary);
            hadData = true;
        }
        if (!cached.isEmpty()) chatIndexLoaded = true;
        return hadData;
    }

    /** Refreshes a conversation that is already painted from the local cache. */
    private void refreshSummary(String chatId, ChatSummary summary) {
        if (summary == null || !loadingSummaryIds.add(chatId)) return;
        try {
            if (summary.peerUid != null) watchSummaryRealtime(chatId, summary.peerUid);
            DataSnapshot meta = readOptionalSnapshot(chatRepository.chatMeta(chatId));
            Long at = meta == null ? null : number(meta.child("lastAt").getValue());
            ChatMessage last = meta == null ? null : meta.child("lastMessage").getValue(ChatMessage.class);
            if (last != null) last.id = meta.child("lastMessageId").getValue(String.class);
            if (last != null) reconcileOptimisticMessage(chatId, last);
            boolean keepLocal = summary.lastMessage != null && summary.lastMessage.localPayload != null
                    && (at == null || summary.lastAt >= at);
            if (!keepLocal && at != null) {
                summary.lastAt = at;
                summary.lastMessage = last;
                summary.encryptedPreview = last == null ? null : last.cipher;
                if (last != null) {
                    if (last.senderUid != null && last.senderUid.equals(uid)) {
                        ChatMessage local = last.clientId == null ? null : optimisticMessages.get(last.clientId);
                        if (local != null && local.localPayload != null) {
                            summary.lastPreview = previewFromJson(local.localPayload);
                            summary.lastKind = kindFromJson(local.localPayload);
                        }
                    } else {
                        maybeDecryptPreview(summary, last);
                    }
                }
            }
            if (store != null) {
                summary.unread = Math.max(summary.unread, store.unread(chatId));
                cachePreview(summary);
                persistSummary(summary);
            }
            refreshSummaryPreview(summary);
            runOnUiThread(this::renderChatList);
        } catch (Throwable error) {
            android.util.Log.w("Tsuyu", "Could not refresh chat summary " + chatId, error);
        } finally {
            loadingSummaryIds.remove(chatId);
        }
    }

    private void persistSummary(ChatSummary summary) {
        if (store == null || summary == null || summary.chatId == null) return;
        store.upsertChat(summary);
        store.setPresence(summary.chatId, summary.online, summary.lastSeen);
    }

    private void cachePreview(ChatSummary summary) {
        if (summary == null) return;
        String preview = previewFor(summary);
        if (preview != null && !preview.isEmpty() && !"Начните переписку".equals(preview)) {
            summary.lastPreview = preview;
            if (summary.lastMessage != null) summary.lastKind = kindFromJson(summary.lastMessage.localPayload);
        }
    }

    private void countIncoming(ChatSummary summary, ChatMessage message) {
        if (store == null || summary == null || summary.chatId == null || message == null) return;
        if (message.senderUid == null || message.senderUid.equals(uid)) return;
        if (summary.chatId.equals(activeChatId)) {
            store.markChatOpened(summary.chatId, message.id, message.sentAt);
            summary.unread = 0;
            return;
        }
        store.countIncoming(summary.chatId, message.id, message.sentAt);
        summary.unread = store.unread(summary.chatId);
    }

    private static String kindFromJson(String payload) {
        if (payload == null) return null;
        try { return new JSONObject(payload).optString("kind", null); }
        catch (Throwable ignored) { return null; }
    }

    private static String dayKey(long at) {
        return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date(at));
    }

    private static String dateLabelFor(long at) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(at);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            if (now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Сегодня";
            Calendar yesterday = Calendar.getInstance();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);
            if (yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) return "Вчера";
        }
        return new SimpleDateFormat(now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ? "d MMMM" : "d MMMM yyyy",
                new Locale("ru")).format(new Date(at));
    }

    private int ownMessageState(ChatSummary summary) {
        if (summary == null || summary.lastMessage == null || uid == null) return 0;
        ChatMessage last = summary.lastMessage;
        if (!uid.equals(last.senderUid)) return 0;
        if (last.pending || last.failed) return 1;
        return summary.peerReadAt > 0 && last.sentAt > 0 && summary.peerReadAt >= last.sentAt ? 2 : 1;
    }

    private void watchConnection() {
        if (connectionListener != null || database == null) return;
        connectionListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                onlineNow = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                if (homeOfflineBanner != null) homeOfflineBanner.setVisibility(onlineNow ? View.GONE : View.VISIBLE);
                if (onlineNow) dataQueue.execute(MainActivity.this::replayOutbox);
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        database.getReference(".info/connected").addValueEventListener(connectionListener);
    }

    /** Re-sends everything that was queued while the device had no connectivity. */
    private void replayOutbox() {
        final TsuyuStore activeStore = store;
        if (activeStore == null || crypto == null || uid == null) return;
        List<TsuyuStore.OutboxItem> queued = activeStore.outbox();
        int replayed = 0;
        for (TsuyuStore.OutboxItem item : queued) {
            if (item.chatId == null || item.peerUid == null || item.payload == null) {
                activeStore.dequeue(item.clientId);
                continue;
            }
            if (activeStore.isDelivered(item.clientId)) {
                activeStore.dequeue(item.clientId);
                continue;
            }
            if (item.attempts > 6) {
                activeStore.updateState("local_" + item.clientId, TsuyuStore.STATE_FAILED);
                continue;
            }
            final TsuyuStore.OutboxItem entry = item;
            ChatMessage message = new ChatMessage();
            message.id = "local_" + entry.clientId;
            message.clientId = entry.clientId;
            message.senderUid = uid;
            message.sentAt = entry.createdAt > 0 ? entry.createdAt : System.currentTimeMillis();
            message.pending = true;
            message.localChatId = entry.chatId;
            message.localPayload = entry.payload;
            ui.post(() -> {
                optimisticMessages.put(entry.clientId, message);
                activeStore.noteAttempt(entry.clientId);
                dispatchOptimisticMessage(message, entry.peerUid);
            });
            replayed++;
            if (replayed >= 8) break;
        }
    }

    /** Reads the device copy of a conversation so the screen is never empty offline. */
    private void loadCachedMessages(final String chatId, final String peerUid) {
        final TsuyuStore activeStore = store;
        if (activeStore == null) return;
        dataQueue.execute(() -> {
            List<ChatMessage> cached = activeStore.messages(chatId, 90);
            List<ChatMessage> pending = activeStore.pendingMessages(chatId);
            ui.post(() -> {
                if (!chatId.equals(activeChatId)) return;
                List<ChatMessage> page = new ArrayList<>(cached);
                Set<String> known = new HashSet<>();
                for (ChatMessage message : cached) if (message.id != null) known.add(message.id);
                for (ChatMessage message : pending) {
                    if (message.id != null && known.add(message.id)) page.add(message);
                }
                page.sort((first, second) -> {
                    int time = Long.compare(first.sentAt, second.sentAt);
                    if (time != 0) return time;
                    return String.valueOf(first.id).compareTo(String.valueOf(second.id));
                });
                conversationModel.cachedMessages = page;
                renderMessages(conversationModel.lastRemoteMessages, peerUid);
            });
        });
    }

    private void loadFolders() {
        folderNames.clear();
        folderMembers.clear();
        if (store == null) return;
        for (String[] row : store.listFolders()) {
            if (row[0] == null) continue;
            if (ARCHIVE_FOLDER_ID.equals(row[0])) continue;
            folderNames.put(row[0], row[1] == null ? "Папка" : row[1]);
        }
        java.util.Set<String> members = new HashSet<>(store.folderChats(java.util.Collections.singletonList(ARCHIVE_FOLDER_ID)));
        folderMembers.put(ARCHIVE_FOLDER_ID, members);
        for (String folderId : folderNames.keySet()) {
            folderMembers.put(folderId, new HashSet<>(store.folderChats(java.util.Collections.singletonList(folderId))));
        }
    }

    /** Rebuilds the chip strip above the chat list: Личные, Непрочитанные, Архив, folders, «+». */
    private void rebuildFilterChips() {
        if (homeFilters == null) return;
        homeFilters.removeAllViews();
        addFilterChip(getString(R.string.home_filter_personal), "all", false);
        addFilterChip(getString(R.string.home_filter_unread), "unread", false);
        addFilterChip(getString(R.string.home_filter_archive), "archive", false);
        for (Map.Entry<String, String> folder : folderNames.entrySet()) {
            addFilterChip(folder.getValue(), "folder:" + folder.getKey(), true);
        }
        addFilterChip("+", "new", false);
        updateFilterChipSelection();
    }

    private void addFilterChip(String label, String key, boolean folderChip) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(14f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setTag(key);
        // Строка папок как в ТГ: подпись и подчёркивание у выбранной папки.
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        params.setMarginEnd(dp(20));
        chip.setLayoutParams(params);
        chip.setPadding(dp(2), 0, dp(2), 0);
        chip.setBackground(folderTabBackground(false));
        chip.setTextColor(getColor(R.color.tsuyu_secondary));
        if ("new".equals(key)) {
            chip.setContentDescription(getString(R.string.folder_new));
            chip.setTextSize(16f);
            chip.setOnClickListener(v -> createFolderDialog());
        } else {
            chip.setOnClickListener(v -> {
                homeFilter = key;
                renderChatList();
            });
            if (folderChip) {
                chip.setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    openFolderActions(key.substring("folder:".length()));
                    return true;
                });
            }
        }
        homeFilters.addView(chip);
    }

    private void updateFilterChipSelection() {
        if (homeFilters == null) return;
        for (int index = 0; index < homeFilters.getChildCount(); index++) {
            View child = homeFilters.getChildAt(index);
            if (child == null || child.getTag() == null) continue;
            boolean selected = child.getTag().toString().equals(homeFilter);
            styleFilterChip((TextView) child, selected);
        }
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }

    private void createFolderDialog() { openFolderNameDialog(null, null); }

    private void openFolderNameDialog(final String folderId, String currentName) {
        View content = getLayoutInflater().inflate(R.layout.dialog_folder_name, null, false);
        final TextInputEditText input = content.findViewById(R.id.folder_name_input);
        if (currentName != null) input.setText(currentName);
        new AlertDialog.Builder(this)
                .setTitle(folderId == null ? R.string.folder_new : R.string.folder_rename)
                .setView(content)
                .setNegativeButton("Отмена", null)
                .setPositiveButton(folderId == null ? R.string.folder_create : R.string.folder_rename_confirm,
                        (dialog, which) -> {
                            String name = input.getText() == null ? "" : input.getText().toString().trim();
                            if (name.isEmpty() || store == null) return;
                            if (folderId == null) {
                                store.createFolder(UUID.randomUUID().toString(), name);
                                toast(getString(R.string.folder_created));
                            } else {
                                store.renameFolder(folderId, name);
                            }
                            loadFolders();
                            rebuildFilterChips();
                            renderChatList();
                        }).show();
    }

    private void openFolderActions(final String folderId) {
        final String name = folderNames.get(folderId);
        if (name == null) return;
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"Переименовать", "Удалить"}, (dialog, which) -> {
                    if (which == 0) {
                        openFolderNameDialog(folderId, name);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.folder_delete_title)
                                .setMessage(R.string.folder_delete_message)
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton(R.string.folder_delete_confirm, (confirm, w) -> {
                                    if (store != null) store.deleteFolder(folderId);
                                    if (("folder:" + folderId).equals(homeFilter)) homeFilter = "all";
                                    loadFolders();
                                    rebuildFilterChips();
                                    renderChatList();
                                    toast(getString(R.string.folder_deleted));
                                }).show();
                    }
                }).show();
    }

    /** Picker shown from the chat menu: put the conversation into any number of folders. */
    private void showFolderPicker(final String chatId) {
        View content = getLayoutInflater().inflate(R.layout.dialog_folder_pick, null, false);
        LinearLayout list = content.findViewById(R.id.folder_pick_list);
        List<String> current = store == null ? new ArrayList<>() : store.chatFolderIds(chatId);
        final boolean keepArchived = current.contains(ARCHIVE_FOLDER_ID);
        final Map<String, MaterialCheckBox> boxes = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> folder : folderNames.entrySet()) {
            MaterialCheckBox box = new MaterialCheckBox(this);
            box.setText(folder.getValue());
            box.setTextColor(getColor(R.color.tsuyu_white));
            box.setChecked(current.contains(folder.getKey()));
            box.setPadding(0, dp(6), 0, dp(6));
            boxes.put(folder.getKey(), box);
            list.addView(box);
        }
        if (folderNames.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Папок пока нет — создайте первую.");
            empty.setTextColor(getColor(R.color.tsuyu_secondary));
            empty.setPadding(0, dp(4), 0, dp(8));
            list.addView(empty);
        }
        TextView create = new TextView(this);
        create.setText("+ " + getString(R.string.folder_new));
        create.setTextColor(getColor(R.color.tsuyu_blue));
        create.setPadding(0, dp(10), 0, dp(4));
        create.setOnClickListener(v -> createFolderDialog());
        list.addView(create);
        new AlertDialog.Builder(this)
                .setTitle(R.string.folder_pick_title)
                .setView(content)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Готово", (dialog, which) -> {
                    List<String> selected = new ArrayList<>();
                    if (keepArchived) selected.add(ARCHIVE_FOLDER_ID);
                    for (Map.Entry<String, MaterialCheckBox> entry : boxes.entrySet()) {
                        if (entry.getValue().isChecked()) selected.add(entry.getKey());
                    }
                    if (store != null) store.setChatFolders(chatId, selected);
                    loadFolders();
                    rebuildFilterChips();
                    renderChatList();
                }).show();
    }

    // ------------------------------------------------------------------
    // Gestures and motion
    // ------------------------------------------------------------------

    /** Swipe right pins the conversation, swipe left toggles its unread mark. */
    private void attachSwipeActions() {
        if (chatList == null) return;
        if (swipePinIcon == null) {
            swipePinIcon = ContextCompat.getDrawable(this, R.drawable.ic_pin);
            swipeReadIcon = ContextCompat.getDrawable(this, R.drawable.ic_checks);
            if (swipePinIcon != null) swipePinIcon.mutate().setTint(getColor(R.color.tsuyu_blue));
            if (swipeReadIcon != null) swipeReadIcon.mutate().setTint(getColor(R.color.tsuyu_blue));
            swipePaint.setColor(getColor(R.color.tsuyu_input));
        }
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(androidx.recyclerview.widget.RecyclerView recyclerView,
                                            androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder,
                                            androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
                return false;
            }
            // Short, snappy gesture instead of the default half-screen drag.
            @Override public float getSwipeThreshold(androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder) {
                return 0.26f;
            }
            @Override public float getSwipeEscapeVelocity(float defaultValue) {
                return defaultValue * 0.6f;
            }
            @Override public long getAnimationDuration(androidx.recyclerview.widget.RecyclerView recyclerView,
                                                       int animationType, float animateDx, float animateDy) {
                return animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL
                        ? 140L
                        : super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy);
            }
            @Override public void onSwiped(androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getBindingAdapterPosition();
                ChatSummary summary = chatListAdapter == null ? null : chatListAdapter.summaryAt(position);
                if (summary == null || summary.chatId == null) {
                    renderChatList();
                    return;
                }
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (direction == ItemTouchHelper.RIGHT) {
                    togglePinned(summary.chatId, !summary.pinned);
                } else if (summary.unread > 0) {
                    markChatRead(summary.chatId);
                } else {
                    markChatUnread(summary.chatId);
                }
                renderChatList();
                if (chatListAdapter != null && position >= 0 && position < chatListAdapter.getItemCount()) {
                    chatListAdapter.notifyItemChanged(position);
                }
            }
            @Override public void onChildDraw(Canvas canvas, androidx.recyclerview.widget.RecyclerView recyclerView,
                                              androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder,
                                              float dX, float dY, int actionState, boolean isCurrentlyActive) {
                View item = viewHolder.itemView;
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f && item.getHeight() > 0) {
                    if (dX > 0) {
                        canvas.drawRect(item.getLeft(), item.getTop(), item.getLeft() + dX, item.getBottom(), swipePaint);
                    } else {
                        canvas.drawRect(item.getRight() + dX, item.getTop(), item.getRight(), item.getBottom(), swipePaint);
                    }
                    Drawable icon = dX > 0 ? swipePinIcon : swipeReadIcon;
                    if (icon != null) {
                        int size = dp(20);
                        int top = item.getTop() + (item.getHeight() - size) / 2;
                        int left = dX > 0 ? item.getLeft() + dp(18) : item.getRight() - dp(18) - size;
                        icon.setBounds(left, top, left + size, top + size);
                        icon.draw(canvas);
                    }
                }
                super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }
        });
        helper.attachToRecyclerView(chatList);
    }

    /** Анимаций нет: заглушки просто показываются, пока грузится первая страница. */
    private void setSkeletonShimmer(boolean running) {
        if (homeLoadingState == null) return;
        homeLoadingState.setAlpha(1f);
    }

    private void styleFilterChip(TextView chip, boolean selected) {
        if (chip == null) return;
        chip.setBackground(folderTabBackground(selected));
        chip.setTextColor(getColor(selected ? R.color.tsuyu_white : R.color.tsuyu_secondary));
    }

    /** Подпись папки с подчёркиванием снизу — вкладочная строка как в ТГ. */
    private android.graphics.drawable.Drawable folderTabBackground(boolean selected) {
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setColor(selected ? getColor(R.color.tsuyu_blue) : 0x00000000);
        line.setCornerRadius(dp(2));
        android.graphics.drawable.GradientDrawable empty = new android.graphics.drawable.GradientDrawable();
        empty.setColor(0x00000000);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{empty, line});
        layers.setLayerInset(1, dp(2), dp(41), dp(2), 0);
        return layers;
    }

    private void applyEmptyStateCopy(String mode) {
        boolean filtered = !"all".equals(mode);
        if (homeEmptyTitle != null) {
            if ("folder".equals(mode)) homeEmptyTitle.setText("В этой папке пусто");
            else if ("unread".equals(mode)) homeEmptyTitle.setText("Непрочитанных нет");
            else if ("archive".equals(mode)) homeEmptyTitle.setText("Архив пуст");
            else if ("search".equals(mode)) homeEmptyTitle.setText(getString(R.string.home_empty_search_title));
            else homeEmptyTitle.setText(getString(R.string.no_chats_title));
        }
        if (homeEmptyDescription != null) {
            if ("folder".equals(mode)) homeEmptyDescription.setText("Добавьте чаты в папку через меню чата.");
            else if ("unread".equals(mode)) homeEmptyDescription.setText("Новые сообщения появятся здесь, и чат поднимется наверх списка.");
            else if ("archive".equals(mode)) homeEmptyDescription.setText("Чаты, отправленные в архив, будут ждать здесь.");
            else if ("search".equals(mode)) homeEmptyDescription.setText(getString(R.string.home_empty_search_description));
            else homeEmptyDescription.setText(getString(R.string.no_chats_description));
        }
        if (homeFindPeople != null) homeFindPeople.setVisibility(filtered ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------
    // Web-parity behaviour: composer switch, reply, pinned message, multi-select
    // ------------------------------------------------------------------

    /** The web client swaps the media buttons for a send button as soon as text appears. */
    private void setComposerMediaVisible(boolean visible) {
        if (selecting) visible = false;
        int state = visible ? View.VISIBLE : View.GONE;
        if (chatVoiceButton != null) chatVoiceButton.setVisibility(state);
        if (chatCircleButton != null) chatCircleButton.setVisibility(state);
        if (chatStickerButton != null) chatStickerButton.setVisibility(state);
    }

    private void startReply(ChatMessage message, String payload) {
        if (message == null) return;
        String text = payload == null ? "" : textFromPayload(payload);
        if (text == null || text.trim().isEmpty()) text = "Сообщение";
        replyToId = message.id;
        replyToText = text;
        if (replyBannerText != null) replyBannerText.setText(text);
        if (replyBanner != null) replyBanner.setVisibility(View.VISIBLE);
        if (composer != null) composer.requestFocus();
    }

    private void copyMessageText(String payload) {
        String text = payload == null ? "" : textFromPayload(payload);
        if (text == null || text.trim().isEmpty()) { toast("Нечего копировать."); return; }
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Tsuyu", text));
        toast("Скопировано");
    }

    private String pinIdKey(String chatId) { return "pin_id_" + chatId; }

    private String pinTextKey(String chatId) { return "pin_text_" + chatId; }

    /** Pinned message lives on the device, exactly like the web client's localStorage pin. */
    private void restorePinnedMessage(String chatId) {
        pinnedMessageId = null;
        if (chatId == null) {
            if (chatPinnedBar != null) chatPinnedBar.setVisibility(View.GONE);
            return;
        }
        String id = com.tsuyu.messenger.util.Settings.getString(pinIdKey(chatId), null);
        String text = com.tsuyu.messenger.util.Settings.getString(pinTextKey(chatId), null);
        if (id == null || text == null) {
            if (chatPinnedBar != null) chatPinnedBar.setVisibility(View.GONE);
            return;
        }
        pinnedMessageId = id;
        if (chatPinnedText != null) chatPinnedText.setText(text);
        if (chatPinnedBar != null) {
            chatPinnedBar.setVisibility(View.VISIBLE);
            chatPinnedBar.setAlpha(0f);
            chatPinnedBar.setAlpha(1f);
        }
    }

    private void pinMessage(ChatMessage message, String payload) {
        if (activeChatId == null || message == null || message.id == null) return;
        String text = payload == null ? "" : textFromPayload(payload);
        if (text == null || text.trim().isEmpty()) text = "Сообщение";
        if (text.length() > 160) text = text.substring(0, 160) + "…";
        com.tsuyu.messenger.util.Settings.putString(pinIdKey(activeChatId), message.id);
        com.tsuyu.messenger.util.Settings.putString(pinTextKey(activeChatId), text);
        restorePinnedMessage(activeChatId);
        toast(getString(R.string.pinned_message_toast));
    }

    private void unpinMessage() {
        if (activeChatId == null) return;
        com.tsuyu.messenger.util.Settings.remove(pinIdKey(activeChatId));
        com.tsuyu.messenger.util.Settings.remove(pinTextKey(activeChatId));
        restorePinnedMessage(activeChatId);
        toast(getString(R.string.unpinned_message_toast));
    }

    private void scrollToPinnedMessage() {
        if (pinnedMessageId == null || messageList == null || messageListAdapter == null) return;
        int position = messageListAdapter.positionOf(pinnedMessageId);
        if (position < 0) { toast("Закреплённое сообщение вне загруженной истории."); return; }
        messageList.smoothScrollToPosition(position);
    }

    private boolean isChatArchived(String chatId) {
        return store != null && chatId != null && store.chatFolderIds(chatId).contains(ARCHIVE_FOLDER_ID);
    }

    /** «Архив» is a reserved folder, so it rides on the existing folder storage. */
    private void toggleChatArchive(String chatId) {
        if (store == null || chatId == null) return;
        List<String> current = new ArrayList<>(store.chatFolderIds(chatId));
        boolean archived = current.contains(ARCHIVE_FOLDER_ID);
        if (archived) current.remove(ARCHIVE_FOLDER_ID); else current.add(ARCHIVE_FOLDER_ID);
        store.setChatFolders(chatId, current);
        loadFolders();
        rebuildFilterChips();
        renderChatList();
        toast(getString(archived ? R.string.chat_unarchived_toast : R.string.chat_archived_toast));
    }

    private boolean matchesChatQuery(ChatSummary summary) {
        if (chatListQuery.isEmpty()) return true;
        String needle = chatListQuery.toLowerCase(java.util.Locale.ROOT).replaceFirst("^@", "");
        Profile peer = summary.peer == null ? new Profile() : summary.peer;
        String name = peer.displayName == null ? "" : peer.displayName.toLowerCase(java.util.Locale.ROOT);
        String username = peer.username == null ? "" : peer.username.toLowerCase(java.util.Locale.ROOT).replaceFirst("^@", "");
        String preview = previewFor(summary);
        if (preview == null) preview = "";
        return name.contains(needle) || username.contains(needle)
                || preview.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    // --- multi-select (web .multi-select-bar) ---

    private void enterSelectionMode() {
        if (activeChatId == null || messageListAdapter == null) return;
        selecting = true;
        selectedMessageIds.clear();
        messageListAdapter.setSelectionMode(true);
        if (composerPanel != null) composerPanel.setVisibility(View.GONE);
        if (multiSelectBar != null) {
            multiSelectBar.setVisibility(View.VISIBLE);
            multiSelectBar.setAlpha(0f);
            multiSelectBar.setAlpha(1f);
        }
        refreshSelectionUi();
    }

    private void exitSelectionMode() {
        selecting = false;
        selectedMessageIds.clear();
        if (messageListAdapter != null) messageListAdapter.setSelectionMode(false);
        if (multiSelectBar != null) multiSelectBar.setVisibility(View.GONE);
        if (composerPanel != null) composerPanel.setVisibility(View.VISIBLE);
        boolean hasText = composer != null && composer.getText() != null
                && composer.getText().toString().trim().length() > 0;
        setComposerMediaVisible(!hasText);
    }

    private String selectionKey(ChatMessage message) {
        if (message == null) return null;
        return message.id != null ? message.id : message.clientId;
    }

    private void toggleMessageSelection(ChatMessage message) {
        String key = selectionKey(message);
        if (key == null) return;
        if (!selectedMessageIds.remove(key)) selectedMessageIds.add(key);
        refreshSelectionUi();
    }

    private void refreshSelectionUi() {
        if (multiSelectCount != null) {
            multiSelectCount.setText(getString(R.string.multi_select_count, selectedMessageIds.size()));
        }
        if (messageListAdapter != null) {
            messageListAdapter.setSelectedKeys(new HashSet<>(selectedMessageIds));
        }
    }

    private void forwardSelectedMessages() {
        if (selectedMessageIds.isEmpty()) { toast(getString(R.string.multi_select_empty)); return; }
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : conversationModel.cachedMessages) {
            String key = selectionKey(message);
            if (key == null || !selectedMessageIds.contains(key)) continue;
            String text = message.localPayload != null ? textFromPayload(message.localPayload) : null;
            if ((text == null || text.isEmpty()) && messageCache != null && message.id != null && activeChatId != null) {
                text = textFromPayload(messageCache.get(activeChatId, message.id));
            }
            if (text != null && !text.trim().isEmpty()) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(text);
            }
        }
        int count = selectedMessageIds.size();
        exitSelectionMode();
        if (builder.length() == 0) { toast("В выбранных сообщениях нет текста."); return; }
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Tsuyu", builder.toString()));
        toast("Скопировано сообщений: " + count + ". Выберите диалог и вставьте.");
    }

    private void deleteSelectedMessages() {
        if (selectedMessageIds.isEmpty()) { toast(getString(R.string.multi_select_empty)); return; }
        if (activeChatId == null) return;
        final String chatId = activeChatId;
        final List<String> ids = new ArrayList<>();
        for (String key : selectedMessageIds) {
            if (key != null && !key.startsWith("local_")) ids.add(key);
        }
        exitSelectionMode();
        if (ids.isEmpty()) { toast("Можно удалить только отправленные сообщения."); return; }
        new AlertDialog.Builder(this)
                .setTitle("Удалить сообщений: " + ids.size() + "?")
                .setMessage("Сообщения будут удалены у обоих участников.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    for (String id : ids) {
                        chatRepository.deleteMessage(chatId, id, new ChatRepository.Result<Void>() {
                            @Override public void success(Void value) { }
                            @Override public void error(String error) { toast(error); }
                        });
                    }
                    toast("Удалено сообщений: " + ids.size());
                }).show();
    }

    // ------------------------------------------------------------------ drawer

    /** The web client opens its sidebar from the header/title area. */
    private void openDrawer() {
        if (drawerLayout == null) drawerLayout = findViewById(R.id.drawer_layout);
        if (drawerLayout == null) return;
        bindDrawerHeader();
        drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
    }

    private void closeDrawer() {
        if (drawerLayout == null) drawerLayout = findViewById(R.id.drawer_layout);
        if (drawerLayout != null && drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
        }
    }

    /** Wires every row and switch of view_drawer.xml, exactly as the drawer behaves on the site. */
    private void setupDrawer() {
        if (drawerRoot == null) return;
        View.OnClickListener profile = v -> { closeDrawer(); showProfileEditor(false); };
        click(drawerRoot, R.id.drawer_item_profile, profile);
        click(drawerRoot, R.id.drawer_item_avatar, v -> {
            closeDrawer();
            editingAvatar = true;
            avatarPicker.launch("image/*");
        });
        click(drawerRoot, R.id.drawer_avatar_frame, profile);
        click(drawerRoot, R.id.drawer_avatar_edit, v -> {
            closeDrawer();
            editingAvatar = true;
            avatarPicker.launch("image/*");
        });
        click(drawerRoot, R.id.drawer_status_row, v -> { closeDrawer(); showProfileEditor(false); });
        click(drawerRoot, R.id.drawer_item_settings, v -> { closeDrawer(); showSettings(); });
        click(drawerRoot, R.id.drawer_item_cloud, v -> { closeDrawer(); showKeySettings(); });
        click(drawerRoot, R.id.drawer_item_cache, v -> {
            clearLocalCache();
            bindDrawerHeader();
        });
        click(drawerRoot, R.id.drawer_item_logout, v -> {
            closeDrawer();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.drawer_logout)
                    .setMessage(getString(R.string.drawer_logout_confirm))
                    .setNegativeButton(R.string.circle_cancel, null)
                    .setPositiveButton(R.string.drawer_logout, (dialog, which) -> {
                        if (presenceManager != null) presenceManager.signOut();
                        if (auth != null) auth.signOut();
                    })
                    .show();
        });
        click(drawerRoot, R.id.drawer_item_pin, v -> showPinSetupDialog());
    }

    private void click(View root, int id, View.OnClickListener listener) {
        View view = root.findViewById(id);
        if (view != null) view.setOnClickListener(listener);
    }

    private void bindSwitch(int id, boolean checked, android.widget.CompoundButton.OnCheckedChangeListener listener) {
        View view = drawerRoot == null ? null : drawerRoot.findViewById(id);
        if (!(view instanceof SwitchCompat)) return;
        SwitchCompat switchView = (SwitchCompat) view;
        switchView.setOnCheckedChangeListener(null);
        switchView.setChecked(checked);
        switchView.setOnCheckedChangeListener(listener);
    }

    /** Pulls the signed-in profile into the drawer header: avatar, name, presence, status. */
    private void bindDrawerHeader() {
        if (drawerRoot == null) return;
        ShapeableImageView avatar = drawerRoot.findViewById(R.id.drawer_avatar);
        TextView initial = drawerRoot.findViewById(R.id.drawer_avatar_initial);
        TextView name = drawerRoot.findViewById(R.id.drawer_name);
        TextView status = drawerRoot.findViewById(R.id.drawer_status);
        TextView presence = drawerRoot.findViewById(R.id.drawer_presence);
        TextView cache = drawerRoot.findViewById(R.id.drawer_cache_label);
        TextView pin = drawerRoot.findViewById(R.id.drawer_pin_label);
        String title = myProfile == null ? null : myProfile.displayNameOrUsername();
        bindAvatar(avatar, initial, myProfile, title, myProfile == null ? null : myProfile.avatarBase64);
        if (name != null) name.setText(title == null ? getString(R.string.app_name) : title);
        if (status != null) {
            String about = myProfile == null || myProfile.about == null || myProfile.about.trim().isEmpty()
                    ? getString(R.string.drawer_status_hint) : myProfile.about;
            status.setText(about);
        }
        if (presence != null) presence.setText(getString(R.string.drawer_online));
        if (cache != null) cache.setText(getString(R.string.drawer_cache, cacheSizeLabel()));
        if (pin != null) {
            boolean lock = uid != null && com.tsuyu.messenger.crypto.PinLock.isEnabled(this, uid);
            pin.setText(getString(lock ? R.string.drawer_pin_on : R.string.drawer_pin));
        }
        bindSwitch(R.id.drawer_switch_stealth, presenceManager != null && presenceManager.isGhostMode(), null);
        bindSwitch(R.id.drawer_switch_sound, NotificationSounds.isEnabled(this), null);
        bindSwitch(R.id.drawer_switch_encrypt, true, null);
        setupDrawerSwitchListeners();
    }

    private void setupDrawerSwitchListeners() {
        // OnCheckedChangeListener takes (button, isChecked) — the button argument is unused here.
        bindSwitch(R.id.drawer_switch_stealth, presenceManager != null && presenceManager.isGhostMode(), (button, isChecked) -> {
            if (presenceManager != null && uid != null) presenceManager.setGhostMode(uid, isChecked);
            toast(getString(isChecked ? R.string.drawer_stealth_on : R.string.drawer_stealth_off));
        });
        bindSwitch(R.id.drawer_switch_sound, NotificationSounds.isEnabled(this), (button, isChecked) -> {
            NotificationSounds.setEnabled(this, isChecked);
            toast(getString(isChecked ? R.string.drawer_sound_on : R.string.drawer_sound_off));
        });
        bindSwitch(R.id.drawer_switch_encrypt, true, (button, isChecked) -> {
            if (!isChecked && button instanceof SwitchCompat) {
                ((SwitchCompat) button).setChecked(true);
                toast(getString(R.string.drawer_encrypt_locked));
            }
        });
    }

    private String cacheSizeLabel() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "tsuyu-media");
            long bytes = 0L;
            java.io.File[] files = dir.listFiles();
            if (files != null) for (java.io.File file : files) bytes += file.length();
            if (bytes <= 0) return "0 МБ";
            return String.format(java.util.Locale.ROOT, "%.1f МБ", bytes / 1048576.0);
        } catch (Throwable e) {
            return "0 МБ";
        }
    }

    private void clearLocalCache() {
        dataQueue.execute(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "tsuyu-media");
                java.io.File[] files = dir.listFiles();
                if (files != null) for (java.io.File file : files) file.delete();
            } catch (Throwable ignored) { }
            runOnUiThread(() -> {
                toast(getString(R.string.drawer_cache_cleared));
                TextView cache = drawerRoot == null ? null : drawerRoot.findViewById(R.id.drawer_cache_label);
                if (cache != null) cache.setText(getString(R.string.drawer_cache, cacheSizeLabel()));
            });
        });
    }

    /** Sets or removes the local PIN lock from the drawer row. */
    private void showPinSetupDialog() {
        if (uid == null) return;
        final boolean enabled = com.tsuyu.messenger.crypto.PinLock.isEnabled(this, uid);
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(R.string.pin_enter_hint);
        input.setMaxLines(1);
        new AlertDialog.Builder(this)
                .setTitle(enabled ? R.string.drawer_pin_on : R.string.drawer_pin)
                .setMessage(enabled ? R.string.pin_remove_hint : R.string.pin_set_hint)
                .setView(input)
                .setNegativeButton(R.string.circle_cancel, null)
                .setPositiveButton(R.string.pin_confirm, (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString();
                    if (enabled) {
                        com.tsuyu.messenger.crypto.PinLock.clearPin(this, uid);
                        toast(getString(R.string.pin_removed));
                    } else if (value.length() < 4) {
                        toast(getString(R.string.pin_too_short));
                    } else {
                        com.tsuyu.messenger.crypto.PinLock.setPin(this, uid, value);
                        toast(getString(R.string.pin_saved));
                    }
                    bindDrawerHeader();
                })
                .show();
    }

    // ------------------------------------------------------------------ circle video notes

    /** Records a real circular video note with the phone camera. */
    private void openCircleCamera() {
        if (activeChatId == null || activePeerUid == null) {
            toast(getString(R.string.circle_need_chat));
            return;
        }
        com.tsuyu.messenger.ui.CircleCameraDialog.show(this, this::sendCircleFile);
    }

    /** Encodes the freshly recorded MP4 and pushes it into the encrypted chat. */
    private void sendCircleFile(java.io.File file) {
        if (file == null || !file.exists() || activeChatId == null || activePeerUid == null) return;
        String chatId = activeChatId;
        String peerUid = activePeerUid;
        String senderUid = uid;
        dataQueue.execute(() -> {
            try {
                String base64 = readBase64(Uri.fromFile(file), 6_000_000);
                String payload = new JSONObject().put("version", 1).put("kind", "circle")
                        .put("mime", "video/mp4").put("data", base64).toString();
                file.delete();
                runOnUiThread(() -> enqueueOptimisticMessage(chatId, peerUid, senderUid, payload));
            } catch (Throwable error) {
                file.delete();
                runOnUiThread(() -> toast(getString(R.string.circle_failed)));
            }
        });
    }

    // ------------------------------------------------------------------
    // Опросы, таймер сгорания, отложенная отправка, контакты, обои, защита
    // ------------------------------------------------------------------

    private static final ChatRepository.Result<Void> NOOP_RESULT = new ChatRepository.Result<Void>() {
        @Override public void success(Void value) { }
        @Override public void error(String message) { }
    };

    /** Опрос: вопрос и до шести вариантов, всё уезжает обычным шифрованным сообщением. */
    private void showPollDialog() {
        if (activeChatId == null || activePeerUid == null) return;
        View body = getLayoutInflater().inflate(R.layout.dialog_poll, null, false);
        android.widget.LinearLayout container = (android.widget.LinearLayout) body;
        body.findViewById(R.id.poll_add_option).setOnClickListener(v -> {
            if (pollOptionFields(container).size() >= 6) {
                toast(getString(R.string.poll_need_options));
                return;
            }
            TextInputEditText extra = new TextInputEditText(this);
            extra.setHint(R.string.poll_option_hint);
            extra.setSingleLine(true);
            extra.setTextColor(getResources().getColor(R.color.tsuyu_white, getTheme()));
            extra.setHintTextColor(getResources().getColor(R.color.tsuyu_secondary, getTheme()));
            container.addView(extra);
        });
        new AlertDialog.Builder(this)
                .setTitle(R.string.poll_title)
                .setView(body)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Отправить", (dialog, which) -> {
                    TextInputEditText question = body.findViewById(R.id.poll_question);
                    String text = question.getText() == null ? "" : question.getText().toString().trim();
                    if (text.isEmpty()) {
                        toast(getString(R.string.poll_need_question));
                        return;
                    }
                    List<String> options = new ArrayList<>();
                    for (TextInputEditText field : pollOptionFields(container)) {
                        String value = field.getText() == null ? "" : field.getText().toString().trim();
                        if (!value.isEmpty()) options.add(value);
                    }
                    if (options.size() < 2) {
                        toast(getString(R.string.poll_need_options));
                        return;
                    }
                    sendPoll(text, options);
                }).show();
    }

    private List<TextInputEditText> pollOptionFields(android.widget.LinearLayout container) {
        List<TextInputEditText> fields = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof TextInputEditText && child.getId() != R.id.poll_question) {
                fields.add((TextInputEditText) child);
            }
        }
        return fields;
    }

    private void sendPoll(String question, List<String> options) {
        try {
            JSONArray array = new JSONArray();
            for (String option : options) array.put(option);
            JSONObject payload = new JSONObject()
                    .put("version", 1)
                    .put("kind", "poll")
                    .put("question", question)
                    .put("options", array);
            if (pendingBurnSeconds > 0) payload.put("burn", pendingBurnSeconds);
            pendingBurnSeconds = 0;
            enqueueOptimisticMessage(activeChatId, activePeerUid, uid, payload.toString());
            toast(getString(R.string.poll_created));
        } catch (Throwable ignored) { }
    }

    /** Голоса лежат в базе: chats/{chatId}/polls/{messageId}/{uid} = номер варианта. */
    private void attachPollVotesListener(String chatId) {
        if (pollVotesRef != null && pollVotesListener != null) {
            pollVotesRef.removeEventListener(pollVotesListener);
        }
        pollVotesRef = chatRepository.root().child("chats").child(chatId).child("polls");
        pollVotesListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snapshot) {
                Map<String, Map<String, Integer>> next = new ConcurrentHashMap<>();
                for (DataSnapshot poll : snapshot.getChildren()) {
                    Map<String, Integer> votes = new ConcurrentHashMap<>();
                    for (DataSnapshot vote : poll.getChildren()) {
                        Object raw = vote.getValue();
                        Integer index = raw instanceof Number ? ((Number) raw).intValue() : null;
                        if (index != null && vote.getKey() != null) votes.put(vote.getKey(), index);
                    }
                    if (poll.getKey() != null) next.put(poll.getKey(), votes);
                }
                pollVotes.clear();
                pollVotes.putAll(next);
                if (chatId.equals(activeChatId) && "chat".equals(page)) {
                    renderMessages(conversationModel.lastRemoteMessages, activePeerUid);
                }
            }
            @Override public void onCancelled(DatabaseError error) { }
        };
        pollVotesRef.addValueEventListener(pollVotesListener);
    }

    /** Тап по опросу: выбор варианта, повторный тап меняет голос. */
    private void showPollVoteDialog(ChatMessage message, String payload) {
        if (message == null || message.id == null || uid == null) return;
        try {
            JSONObject object = new JSONObject(payload);
            JSONArray options = object.optJSONArray("options");
            if (options == null || options.length() == 0) return;
            String[] items = new String[options.length()];
            for (int i = 0; i < options.length(); i++) items[i] = options.optString(i, "");
            final String chatId = activeChatId;
            final String messageId = message.id;
            new AlertDialog.Builder(this)
                    .setTitle(object.optString("question", getString(R.string.poll_title)))
                    .setItems(items, (dialog, which) -> {
                        if (chatId == null) return;
                        chatRepository.root().child("chats").child(chatId).child("polls")
                                .child(messageId).child(uid).setValue(which);
                        toast(getString(R.string.poll_voted));
                    }).show();
        } catch (Throwable ignored) { }
    }

    /** Сгорание: таймер относится к следующему отправленному сообщению. */
    private void showBurnDialog() {
        final int[] seconds = {0, 5, 30, 60, 300, 3600, 86400};
        String[] labels = {
                getString(R.string.burn_off), getString(R.string.burn_5s), getString(R.string.burn_30s),
                getString(R.string.burn_1m), getString(R.string.burn_5m), getString(R.string.burn_1h),
                getString(R.string.burn_1d)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.burn_title)
                .setItems(labels, (dialog, which) -> {
                    pendingBurnSeconds = seconds[which];
                    toast(pendingBurnSeconds == 0 ? getString(R.string.burn_off)
                            : getString(R.string.burn_armed, burnLabel(pendingBurnSeconds)));
                }).show();
    }

    private String burnLabel(int seconds) {
        if (seconds >= 86400) return getString(R.string.burn_1d);
        if (seconds >= 3600) return getString(R.string.burn_1h);
        if (seconds >= 300) return getString(R.string.burn_5m);
        if (seconds >= 60) return getString(R.string.burn_1m);
        if (seconds >= 30) return getString(R.string.burn_30s);
        return getString(R.string.burn_5s);
    }

    private boolean isBurnExpired(String payload, ChatMessage message) {
        return burnSeconds(payload) > 0 && burnRemaining(payload, message) <= 0L;
    }

    private int burnSeconds(String payload) {
        if (payload == null) return 0;
        try {
            return new JSONObject(payload).optInt("burn", 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private long burnRemaining(String payload, ChatMessage message) {
        int seconds = burnSeconds(payload);
        if (seconds <= 0 || message == null) return -1L;
        long sentAt = message.sentAt > 0 ? message.sentAt : System.currentTimeMillis();
        return sentAt + seconds * 1000L - System.currentTimeMillis();
    }

    /** Пока в переписке есть сгорающие сообщения, экран перерисовывается сам. */
    private void scheduleBurnRefresh(final String chatId) {
        if (burnRefreshChatId != null && burnRefreshChatId.equals(chatId)) return;
        burnRefreshChatId = chatId;
        ui.postDelayed(() -> {
            burnRefreshChatId = null;
            if (chatId.equals(activeChatId) && "chat".equals(page)) {
                renderMessages(conversationModel.lastRemoteMessages, activePeerUid);
            }
        }, 5000L);
    }

    /** Отложенная отправка: текст из поля уходит сам в выбранный момент. */
    private void showScheduleDialog() {
        if (activeChatId == null || activePeerUid == null) return;
        String text = composer == null || composer.getText() == null ? "" : composer.getText().toString().trim();
        if (text.isEmpty()) {
            toast(getString(R.string.schedule_empty));
            return;
        }
        final long[] delays = {5 * 60_000L, 30 * 60_000L, 60 * 60_000L, 0L};
        String[] labels = {getString(R.string.schedule_5m), getString(R.string.schedule_30m),
                getString(R.string.schedule_1h), getString(R.string.schedule_morning)};
        final String payload = text;
        final int burn = pendingBurnSeconds;
        new AlertDialog.Builder(this)
                .setTitle(R.string.schedule_title)
                .setItems(labels, (dialog, which) -> {
                    long at = delays[which] > 0 ? System.currentTimeMillis() + delays[which] : tomorrowMorning();
                    try {
                        JSONObject json = new JSONObject().put("version", 1).put("kind", "text").put("text", payload);
                        if (burn > 0) json.put("burn", burn);
                        ScheduledMessages.schedule(this, activeChatId, activePeerUid, json.toString(), at);
                        pendingBurnSeconds = 0;
                        if (composer != null) composer.setText("");
                        toast(getString(R.string.schedule_planned, scheduleTimeLabel(at)));
                    } catch (Throwable ignored) { }
                }).show();
    }

    private long tomorrowMorning() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.add(java.util.Calendar.DAY_OF_YEAR, 1);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 9);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        return calendar.getTimeInMillis();
    }

    private String scheduleTimeLabel(long at) {
        return new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(new Date(at));
    }

    /** Поделиться контактом: визитка уезжает как обычное сообщение. */
    private void showContactPicker() {
        if (uid == null) return;
        chatRepository.root().child("users").child(uid).child("contacts").get()
                .addOnSuccessListener(snapshot -> {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        if (child.getKey() != null && Boolean.TRUE.equals(child.getValue(Boolean.class))) {
                            ids.add(child.getKey());
                        }
                    }
                    if (ids.isEmpty()) {
                        toast(getString(R.string.contact_empty));
                        return;
                    }
                    List<String> names = new ArrayList<>();
                    List<Profile> profiles = new ArrayList<>();
                    for (String id : ids) {
                        Profile cached = store != null ? store.getProfile(id) : null;
                        if (cached != null) {
                            cached.uid = id;
                            profiles.add(cached);
                            names.add(cached.displayNameOrUsername());
                        } else {
                            Profile placeholder = new Profile();
                            placeholder.uid = id;
                            placeholder.displayName = id;
                            profiles.add(placeholder);
                            names.add(id);
                        }
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.contact_title)
                            .setItems(names.toArray(new String[0]),
                                    (dialog, which) -> sendContact(profiles.get(which)))
                            .show();
                });
    }

    private void sendContact(Profile contact) {
        if (contact == null || activeChatId == null || activePeerUid == null) return;
        try {
            JSONObject payload = new JSONObject()
                    .put("version", 1)
                    .put("kind", "contact")
                    .put("uid", contact.uid)
                    .put("name", contact.displayNameOrUsername() == null ? "" : contact.displayNameOrUsername())
                    .put("username", contact.username == null ? "" : contact.username);
            enqueueOptimisticMessage(activeChatId, activePeerUid, uid, payload.toString());
            toast(getString(R.string.contact_sent));
        } catch (Throwable ignored) { }
    }

    // ---- Обои ----

    private void showWallpaperDialog(final String chatId, final boolean global) {
        String[] items = {getString(R.string.wallpaper_hint), getString(R.string.wallpaper_reset)};
        new AlertDialog.Builder(this)
                .setTitle(global ? R.string.wallpaper_global_title : R.string.wallpaper_title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        pendingWallpaperKey = global ? "wallpaper_global" : "wallpaper_" + chatId;
                        pendingMediaKind = "wallpaper";
                        avatarPicker.launch("image/*");
                    } else {
                        String key = global ? "wallpaper_global" : "wallpaper_" + chatId;
                        com.tsuyu.messenger.util.Settings.remove(key);
                        if (chatId != null) applyChatWallpaper(chatId);
                        toast(getString(R.string.wallpaper_cleared));
                    }
                }).show();
    }

    private void saveWallpaper(Uri uri, String key) {
        if (key == null) return;
        dataQueue.execute(() -> {
            try {
                String encoded = imageToBase64(uri, 1080, 1_400_000);
                com.tsuyu.messenger.util.Settings.putString(key, encoded);
                runOnUiThread(() -> {
                    if (activeChatId != null) applyChatWallpaper(activeChatId);
                    toast(getString(R.string.wallpaper_set));
                });
            } catch (Throwable error) {
                runOnUiThread(() -> toast("Не удалось прочитать изображение."));
            }
        });
    }

    /** Обои чата перекрывают общие: фон ставится списку сообщений, под затемнением. */
    private void applyChatWallpaper(String chatId) {
        if (messageList == null) return;
        SharedPreferences prefs = com.tsuyu.messenger.util.Settings.raw();
        String encoded = prefs.getString("wallpaper_" + chatId, null);
        if (encoded == null || encoded.isEmpty()) encoded = prefs.getString("wallpaper_global", null);
        if (encoded == null || encoded.isEmpty()) {
            messageList.setBackground(null);
            return;
        }
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            // Обои тоже уменьшаем: иначе большой снимок съедает память при входе в чат.
            android.graphics.Bitmap bitmap = com.tsuyu.messenger.TsuyuPhoto.decodeScaled(bytes, 1440);
            if (bitmap == null) {
                messageList.setBackground(null);
                return;
            }
            android.graphics.drawable.BitmapDrawable photo =
                    new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
            photo.setAlpha(120);
            android.graphics.drawable.Drawable[] layers = {
                    new android.graphics.drawable.ColorDrawable(0xFF0A0A0C), photo};
            messageList.setBackground(new android.graphics.drawable.LayerDrawable(layers));
        } catch (Throwable ignored) {
            messageList.setBackground(null);
        }
    }

    // ---- Исключения режима защиты ----

    private void toggleGuardException(String chatId) {
        SharedPreferences prefs = com.tsuyu.messenger.util.Settings.raw();
        Set<String> set = new HashSet<>(prefs.getStringSet("screen_guard_exceptions", Collections.emptySet()));
        boolean now = !set.contains(chatId);
        if (now) set.add(chatId); else set.remove(chatId);
        prefs.edit().putStringSet("screen_guard_exceptions", set).apply();
        applyScreenGuard();
        toast(getString(now ? R.string.guard_exception_on : R.string.guard_exception_off));
    }

    // ---- Редактор стикеров ----

    private void showStickerEditor(final Uri uri) {
        if (activeChatId == null || activePeerUid == null) return;
        dataQueue.execute(() -> {
            final android.graphics.Bitmap source = decodeScaled(uri, 720);
            if (source == null) {
                runOnUiThread(() -> toast("Не удалось прочитать изображение."));
                return;
            }
            runOnUiThread(() -> {
                View body = getLayoutInflater().inflate(R.layout.dialog_sticker_editor, null, false);
                final com.tsuyu.messenger.ui.StickerCanvas canvas = body.findViewById(R.id.sticker_canvas);
                canvas.setSource(source);
                Slider brushSlider = body.findViewById(R.id.sticker_brush);
                Slider thresholdSlider = body.findViewById(R.id.sticker_threshold);
                brushSlider.addOnChangeListener((slider, value, fromUser) -> canvas.setBrushSize(value));
                thresholdSlider.addOnChangeListener((slider, value, fromUser) -> canvas.setThreshold(value));
                int[] colors = {0xFFFFFFFF, 0xFF7B61FF, 0xFFFF3B30, 0xFF00C781};
                int[] ids = {R.id.sticker_color_1, R.id.sticker_color_2, R.id.sticker_color_3, R.id.sticker_color_4};
                for (int i = 0; i < ids.length; i++) {
                    final int color = colors[i];
                    body.findViewById(ids[i]).setOnClickListener(v -> canvas.setBrushColor(color));
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.sticker_editor_title)
                        .setView(body)
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton(R.string.sticker_send, (dialog, which) -> {
                            android.graphics.Bitmap result = canvas.export();
                            if (result == null) return;
                            try {
                                JSONObject payload = new JSONObject()
                                        .put("version", 1)
                                        .put("kind", "sticker")
                                        .put("mime", "image/png")
                                        .put("data", bitmapToBase64(result));
                                enqueueOptimisticMessage(activeChatId, activePeerUid, uid, payload.toString());
                            } catch (Throwable ignored) { }
                        }).show();
            });
        });
    }

    private android.graphics.Bitmap decodeScaled(Uri uri, int max) {
        try {
            java.io.InputStream stream = getContentResolver().openInputStream(uri);
            if (stream == null) return null;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(stream, null, options);
            stream.close();
            int sample = 1;
            int longest = Math.max(options.outWidth, options.outHeight);
            while (longest / sample > max) sample *= 2;
            android.graphics.BitmapFactory.Options decode = new android.graphics.BitmapFactory.Options();
            decode.inSampleSize = sample;
            decode.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            java.io.InputStream second = getContentResolver().openInputStream(uri);
            if (second == null) return null;
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(second, null, decode);
            second.close();
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String bitmapToBase64(android.graphics.Bitmap bitmap) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------
    // Звонки: экран, ящик вызовов и запись в истории переписки
    // ------------------------------------------------------------------

    /** Открывает экран звонка; сам звонок поднимает движок уже внутри активности. */
    private void startCall(String chatId, String peerUid, boolean video) {
        if (chatId == null || peerUid == null || uid == null) return;
        if (CallEngine.busy()) {
            toast(getString(R.string.call_in_progress));
            return;
        }
        String name = peerProfile != null && peerUid.equals(peerProfile.uid)
                ? peerProfile.displayNameOrUsername() : null;
        if (name == null) name = peerNameFor(peerUid);
        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_CHAT_ID, chatId);
        intent.putExtra(CallActivity.EXTRA_PEER_UID, peerUid);
        intent.putExtra(CallActivity.EXTRA_PEER_NAME, name);
        intent.putExtra(CallActivity.EXTRA_VIDEO, video);
        intent.putExtra(CallActivity.EXTRA_INCOMING, false);
        startActivity(intent);
    }

    private String peerNameFor(String peerUid) {
        Profile cached = store != null ? store.getProfile(peerUid) : null;
        if (cached != null) {
            cached.uid = peerUid;
            return cached.displayNameOrUsername();
        }
        return null;
    }

    /** Приглашение из базы: если приложение открыто, экран звонка поднимается без пуша. */
    private void attachCallInbox() {
        if (uid == null || callInboxListener != null) return;
        callInboxListener = CallSignal.watchInbox(uid, invite -> {
            if (CallEngine.busy()) return;
            if (isDestroyed() || isFinishing()) return;
            if (invite.callId != null && invite.callId.equals(CallEngine.get().getCallId())) return;
            String name = peerNameFor(invite.from);
            startActivity(CallActivity.incomingIntent(this, invite.chatId, invite.callId,
                    invite.from, name, invite.video));
        });
    }

    private void detachCallInbox() {
        if (callInboxListener != null && uid != null) {
            com.google.firebase.database.FirebaseDatabase.getInstance().getReference("users")
                    .child(uid).child("callInbox").removeEventListener(callInboxListener);
        }
        callInboxListener = null;
    }

    /**
     * Итог разговора остаётся в переписке отдельной строкой: вызывающий записывает
     * её один раз, чтобы в чате не появлялись две записи об одном звонке.
     */
    private void attachCallLogListener() {
        if (callLogListener != null) return;
        callLogListener = (state, detail) -> {
            CallEngine engine = CallEngine.get();
            if ("ringing".equals(state) || "connecting".equals(state)) {
                if (callLogChatId == null) rememberCall(engine);
                return;
            }
            if ("active".equals(state)) {
                if (callLogChatId == null) rememberCall(engine);
                if (callLogActiveAt == 0L) callLogActiveAt = System.currentTimeMillis();
                return;
            }
            if (!"ended".equals(state) && !"declined".equals(state)
                    && !"missed".equals(state) && !"failed".equals(state)) return;
            if (callLogChatId == null) rememberCall(engine);
            long seconds = callLogActiveAt == 0L ? 0L : (System.currentTimeMillis() - callLogActiveAt) / 1000L;
            boolean missed = callLogActiveAt == 0L || "missed".equals(state) || "failed".equals(state);
            boolean declined = "declined".equals(state);
            if (!callLogIncoming && !callLogSent && callLogChatId != null && callLogPeerUid != null) {
                callLogSent = true;
                publishCallLog(callLogChatId, callLogPeerUid, callLogVideo, seconds, missed, declined);
            }
            callLogChatId = null;
            callLogPeerUid = null;
            callLogActiveAt = 0L;
            callLogSent = false;
        };
        CallEngine.get().addListener(callLogListener);
    }

    private void rememberCall(CallEngine engine) {
        callLogChatId = engine.getChatId();
        callLogPeerUid = engine.getPeerUid();
        callLogVideo = engine.isVideoCall();
        callLogIncoming = engine.wasIncoming();
    }

    private void publishCallLog(String chatId, String peerUid, boolean video, long seconds,
                                boolean missed, boolean declined) {
        SignalE2ee activeCrypto = crypto;
        if (activeCrypto == null || uid == null) return;
        try {
            JSONObject payload = new JSONObject()
                    .put("version", 1)
                    .put("kind", "call")
                    .put("video", video)
                    .put("seconds", seconds)
                    .put("missed", missed)
                    .put("declined", declined);
            chatRepository.sendPayload(chatId, uid, peerUid, activeCrypto,
                    payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), null,
                    new ChatRepository.Result<String>() {
                        @Override public void success(String value) { }
                        @Override public void error(String message) { }
                    });
        } catch (Throwable ignored) { }
    }

    private void toast(String text) { if (Looper.myLooper() == Looper.getMainLooper()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); else runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }
    private String authError(Exception e) { return "Не удалось войти. Проверьте почту и пароль или попробуйте позже."; }
    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0L; }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            closeDrawer();
            return;
        }
        if (selecting) { exitSelectionMode(); return; }
        if ("chat".equals(page)) { detachChatListeners(); showHome(); return; }
        if ("profile_edit".equals(page)) {
            if (isNewProfile) auth.signOut(); else showHome();
            return;
        }
        if ("search".equals(page) || "settings".equals(page)) { showHome(); return; }
        super.onBackPressed();
    }
}
