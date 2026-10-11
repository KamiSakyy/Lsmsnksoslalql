package com.tsuyu.messenger;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.util.Base64;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
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
import com.tsuyu.messenger.crypto.LocalMessageCache;
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
import com.tsuyu.messenger.ui.ZoomImageView;

import org.json.JSONArray;
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
    private boolean chatIndexLoaded;
    private String replyToId;
    private String replyToText;
    private long lastTypingWrite;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService cryptoQueue = Executors.newSingleThreadExecutor();
    private final ExecutorService dataQueue = Executors.newFixedThreadPool(3);
    private final Set<String> decrypting = ConcurrentHashMap.newKeySet();
    private final Set<String> decryptFailures = ConcurrentHashMap.newKeySet();
    private final Set<String> loadingSummaryIds = ConcurrentHashMap.newKeySet();
    private final Map<String, ChatSummary> summaries = new ConcurrentHashMap<>();
    private final Map<String, ChatMessage> optimisticMessages = new ConcurrentHashMap<>();
    private final Map<String, DatabaseReference> summaryPresenceRefs = new ConcurrentHashMap<>();
    private final Map<String, ValueEventListener> summaryPresenceListeners = new ConcurrentHashMap<>();
    private final Map<String, DatabaseReference> summaryTypingRefs = new ConcurrentHashMap<>();
    private final Map<String, ValueEventListener> summaryTypingListeners = new ConcurrentHashMap<>();
    private final Map<String, DatabaseReference> summaryMetaRefs = new ConcurrentHashMap<>();
    private final Map<String, ValueEventListener> summaryMetaListeners = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> messageReactions = new ConcurrentHashMap<>();

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
    private List<ChatMessage> lastRemoteMessages = new ArrayList<>();
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        screenHost = findViewById(R.id.screen_host);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(screenHost, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
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
                ui.post(() -> toast("Не удалось подключиться. Проверьте интернет и попробуйте снова."));
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
        MaterialToolbar toolbar = root.findViewById(R.id.home_toolbar);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_search) { showSearchScreen(); return true; }
            if (item.getItemId() == R.id.action_profile) { showProfileEditor(false); return true; }
            return false;
        });
        android.view.MenuItem profileItem = toolbar.getMenu().findItem(R.id.action_profile);
        if (profileItem != null && profileItem.getActionView() != null) {
            View profileAction = profileItem.getActionView();
            ShapeableImageView avatar = profileAction.findViewById(R.id.action_profile_avatar);
            TextView initial = profileAction.findViewById(R.id.action_profile_initial);
            bindAvatar(avatar, initial, myProfile, myProfile == null ? "T" : myProfile.displayNameOrUsername(),
                    myProfile == null ? null : myProfile.avatarBase64);
            profileAction.setOnClickListener(v -> showProfileEditor(false));
        }
        chatList = root.findViewById(R.id.chats_list);
        chatList.setLayoutManager(new LinearLayoutManager(this));
        chatList.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        chatListAdapter = new ChatListAdapter(new ChatListAdapter.Listener() {
            @Override public void onChatSelected(ChatSummary summary) { openChat(summary); }
            @Override public String previewFor(ChatSummary summary) { return MainActivity.this.previewFor(summary); }
        });
        chatList.setAdapter(chatListAdapter);
        homeEmptyState = root.findViewById(R.id.home_empty_state);
        homeLoadingState = root.findViewById(R.id.home_loading_state);
        homeNewChat = root.findViewById(R.id.home_new_chat);
        homeNewChat.setOnClickListener(v -> showSearchScreen());
        root.findViewById(R.id.home_find_people).setOnClickListener(v -> showSearchScreen());
        setupBottomNavigation(root, R.id.nav_chats);
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
            if (!summaries.containsKey(chatId) && loadingSummaryIds.add(chatId)) {
                dataQueue.execute(() -> loadOneSummary(chatId));
            }
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
            try {
                DataSnapshot onlineSnapshot = Tasks.await(chatRepository.presence(peerUid).child("online").get(), 10, TimeUnit.SECONDS);
                online = Boolean.TRUE.equals(onlineSnapshot.getValue(Boolean.class));
            } catch (Exception ignored) { }
            boolean typingNow = false;
            try {
                DataSnapshot typing = Tasks.await(chatRepository.typing(chatId).child(peerUid).get(), 10, TimeUnit.SECONDS);
                long typingAt = number(typing.child("at").getValue());
                typingNow = Boolean.TRUE.equals(typing.child("typing").getValue(Boolean.class))
                        && System.currentTimeMillis() - typingAt <= 5500;
            } catch (Exception ignored) { }
            if (!expectedUid.equals(uid)) return;
            if (last != null) {
                last.id = meta == null ? null : meta.child("lastMessageId").getValue(String.class);
                reconcileOptimisticMessage(chatId, last);
            }
            ChatSummary summary = new ChatSummary(chatId, peerUid, profile, at, last == null ? null : last.cipher);
            summary.online = online;
            summary.typing = typingNow;
            summary.lastMessage = last;
            ChatSummary optimistic = summaries.get(chatId);
            if (optimistic != null && optimistic.lastMessage != null
                    && (last == null || optimistic.lastAt > at)) {
                summary.lastAt = optimistic.lastAt;
                summary.lastMessage = optimistic.lastMessage;
                summary.encryptedPreview = null;
            }
            summaries.put(chatId, summary);
            watchSummaryRealtime(chatId, peerUid);
            if (summary.lastMessage != null) maybeDecryptPreview(summary, summary.lastMessage);
        } catch (Exception error) {
            android.util.Log.w("Tsuyu", "Could not load chat summary " + chatId, error);
        } finally {
            loadingSummaryIds.remove(chatId);
            runOnUiThread(this::renderChatList);
        }
    }

    private DataSnapshot readOptionalSnapshot(DatabaseReference ref) {
        try { return Tasks.await(ref.get(), 10, TimeUnit.SECONDS); }
        catch (Exception ignored) { return null; }
    }

    private String readOptionalProfileField(DatabaseReference profileRef, String field) {
        try { return Tasks.await(profileRef.child(field).get(), 10, TimeUnit.SECONDS).getValue(String.class); }
        catch (Exception ignored) { return null; }
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
                    renderChatList();
                }
                @Override public void onCancelled(DatabaseError error) { }
            };
            summaryTypingRefs.put(chatId, ref);
            summaryTypingListeners.put(chatId, listener);
            ref.addValueEventListener(listener);
        }
        if (!summaryMetaListeners.containsKey(chatId)) {
            DatabaseReference ref = chatRepository.chatMeta(chatId);
            ValueEventListener listener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    ChatSummary summary = summaries.get(chatId);
                    if (summary == null) return;
                    ChatMessage last = snapshot.child("lastMessage").getValue(ChatMessage.class);
                    if (last != null) last.id = snapshot.child("lastMessageId").getValue(String.class);
                    long remoteAt = number(snapshot.child("lastAt").getValue());
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
                    if (summary.lastMessage != null && !preserveLocalPreview) maybeDecryptPreview(summary, summary.lastMessage);
                    renderChatList();
                }
                @Override public void onCancelled(DatabaseError error) { }
            };
            summaryMetaRefs.put(chatId, ref);
            summaryMetaListeners.put(chatId, listener);
            ref.addValueEventListener(listener);
        }
    }

    private void removeSummaryRealtime(String chatId) {
        DatabaseReference presenceRef = summaryPresenceRefs.remove(chatId);
        ValueEventListener presenceListener = summaryPresenceListeners.remove(chatId);
        if (presenceRef != null && presenceListener != null) presenceRef.removeEventListener(presenceListener);
        DatabaseReference typingRef = summaryTypingRefs.remove(chatId);
        ValueEventListener typingListener = summaryTypingListeners.remove(chatId);
        if (typingRef != null && typingListener != null) typingRef.removeEventListener(typingListener);
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
                runOnUiThread(this::renderChatList);
            } catch (Exception ignored) { }
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
    }

    private void renderChatList() {
        if (!"home".equals(page) || chatList == null || chatListAdapter == null) return;
        List<ChatSummary> items = new ArrayList<>(summaries.values());
        items.sort((a, b) -> Long.compare(b.lastAt, a.lastAt));
        chatListAdapter.submit(items);
        if (items.isEmpty()) {
            chatList.setVisibility(View.GONE);
            homeLoadingState.setVisibility(chatIndexLoaded ? View.GONE : View.VISIBLE);
            homeEmptyState.setVisibility(chatIndexLoaded ? View.VISIBLE : View.GONE);
            if (homeNewChat != null) homeNewChat.setVisibility(View.GONE);
        } else {
            chatList.setVisibility(View.VISIBLE);
            homeLoadingState.setVisibility(View.GONE);
            homeEmptyState.setVisibility(View.GONE);
            if (homeNewChat != null) homeNewChat.setVisibility(View.VISIBLE);
        }
    }

    private String previewFor(ChatSummary summary) {
        ChatMessage message = summary.lastMessage;
        if (message == null) return "Начните переписку";
        if (message.localPayload != null) return previewFromJson(message.localPayload);
        String cached = message.id == null || messageCache == null ? null : messageCache.get(summary.chatId, message.id);
        if (cached != null) return previewFromJson(cached);
        if (message.clientId != null) {
            ChatMessage local = optimisticMessages.get(message.clientId);
            if (local != null && local.localPayload != null) return previewFromJson(local.localPayload);
        }
        if (message.failed) return "Не отправлено · нажмите, чтобы повторить";
        return "";
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
        setupBottomNavigation(root, R.id.nav_search);
        searchInput.addTextChangedListener(new TextWatcher() {
            private final Handler debounce = new Handler(Looper.getMainLooper());
            private Runnable pending;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pending != null) debounce.removeCallbacks(pending);
                String query = s == null ? "" : s.toString();
                pending = () -> runSearch(query);
                debounce.postDelayed(pending, 260);
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
        if (uid == null || chatId == null || peerUid == null) return;
        detachChatListeners();
        if (peerProfile == null || !peerUid.equals(peerProfile.uid)) peerProfile = null;
        activeChatId = chatId;
        activePeerUid = peerUid;
        replyToId = null;
        replyToText = null;
        lastRemoteMessages = new ArrayList<>();
        lastRemoteChatId = chatId;
        getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().putString("active_chat", chatId).apply();
        page = "chat";
        View root = showScreen(R.layout.screen_chat);
        root.findViewById(R.id.chat_back).setOnClickListener(v -> {
            detachChatListeners();
            showHome();
        });
        root.findViewById(R.id.chat_more).setOnClickListener(v -> showChatMenu());
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

        messageList = root.findViewById(R.id.message_list);
        LinearLayoutManager messageLayout = new LinearLayoutManager(this);
        messageLayout.setStackFromEnd(true);
        messageList.setLayoutManager(messageLayout);
        messageList.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        messageListAdapter = new MessageListAdapter(uid, textSize(), isTextBold(),
                getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("italic", false),
                new MessageListAdapter.Listener() {
                    @Override public void onOpenPayload(String payload) { openPayload(payload); }
                    @Override public void onMessageMenu(View anchor, ChatMessage message, String payload) { showMessageMenu(message, payload); }
                    @Override public void onRetry(ChatMessage message) { retryMessage(message); }
                    @Override public void onReact(ChatMessage message) { toggleHeart(message); }
                    @Override public void onGalleryImage(String encodedImage) { showImageDialog(encodedImage); }
                });
        messageList.setAdapter(messageListAdapter);
        chatEmptyState = root.findViewById(R.id.chat_empty_state);
        composer = root.findViewById(R.id.chat_composer);
        replyBanner = root.findViewById(R.id.reply_banner);
        replyBannerText = root.findViewById(R.id.reply_banner_text);
        root.findViewById(R.id.reply_cancel).setOnClickListener(v -> {
            replyToId = null;
            replyToText = null;
            replyBanner.setVisibility(View.GONE);
        });
        root.findViewById(R.id.chat_attach).setOnClickListener(v -> showAttachmentMenu());
        root.findViewById(R.id.chat_send).setOnClickListener(v -> sendCurrentText());
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
                List<ChatMessage> messages = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    ChatMessage message = child.getValue(ChatMessage.class);
                    if (message != null) { message.id = child.getKey(); messages.add(message); }
                }
                renderMessages(messages, peerUid);
                if (!presenceManager.isGhostMode()) chatRepository.markRead(chatId, uid);
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
            chatStatus.setTextColor(ContextCompat.getColor(this, R.color.tsuyu_green));
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
                    for (DataSnapshot reaction : message.getChildren()) {
                        if ("heart".equals(reaction.getValue(String.class))) users.add(reaction.getKey());
                    }
                    if (!users.isEmpty()) messageReactions.put(message.getKey(), users);
                }
                if (chatId.equals(activeChatId)) renderMessages(lastRemoteMessages, activePeerUid);
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
        lastRemoteMessages = new ArrayList<>(messages);

        Set<String> serverClientIds = new HashSet<>();
        for (ChatMessage message : lastRemoteMessages) {
            if (message.clientId != null) {
                serverClientIds.add(message.clientId);
                reconcileOptimisticMessage(renderChatId, message);
            }
        }
        List<ChatMessage> visible = new ArrayList<>(lastRemoteMessages);
        for (ChatMessage local : optimisticMessages.values()) {
            if (!renderChatId.equals(local.localChatId)) continue;
            if (local.clientId != null && serverClientIds.contains(local.clientId)) continue;
            visible.add(local);
        }
        visible.sort((first, second) -> {
            int time = Long.compare(first.sentAt, second.sentAt);
            if (time != 0) return time;
            return String.valueOf(first.id).compareTo(String.valueOf(second.id));
        });

        List<MessageListAdapter.Row> rows = new ArrayList<>();
        for (ChatMessage message : visible) {
            String payload = message.localPayload;
            if (payload == null && activeCache != null && message.id != null) payload = activeCache.get(renderChatId, message.id);
            String key = renderChatId + ":" + message.id;
            if (payload == null && decryptFailures.contains(key)) payload = "{\"kind\":\"decrypt_error\"}";
            if (payload == null && message.senderUid != null && !uid.equals(message.senderUid)
                    && message.cipher != null && decrypting.add(key)) {
                final String messageId = message.id;
                cryptoQueue.execute(() -> {
                    try {
                        byte[] plain = activeCrypto.decrypt(peerUid, message.signalType, message.cipher);
                        if (activeCache != null) activeCache.put(renderChatId, messageId, new String(plain, StandardCharsets.UTF_8));
                        runOnUiThread(() -> {
                            if (renderChatId.equals(activeChatId) && lastRemoteChatId.equals(renderChatId)) {
                                renderMessages(lastRemoteMessages, peerUid);
                            }
                        });
                    } catch (Exception error) {
                        decryptFailures.add(key);
                        runOnUiThread(() -> {
                            if (renderChatId.equals(activeChatId) && lastRemoteChatId.equals(renderChatId)) {
                                renderMessages(lastRemoteMessages, peerUid);
                            }
                        });
                    } finally { decrypting.remove(key); }
                });
            }
            Set<String> reactions = messageReactions.get(message.id);
            int reactionCount = reactions == null ? 0 : reactions.size();
            boolean read = message.sentAt > 0 && peerReadAt >= message.sentAt;
            rows.add(new MessageListAdapter.Row(message, payload, reactionCount, read));
        }

        messageListAdapter.setTextStyle(textSize(), isTextBold(),
                getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("italic", false));
        messageListAdapter.submit(rows);
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
            else if ("video".equals(kind)) showVideoDialog(object.optString("data", ""), object.optString("mime", "video/mp4"));
            else if ("audio".equals(kind) || "voice".equals(kind)) playAudio(object.optString("data", ""), object.optString("mime", "audio/mp4"));
            else if ("collage".equals(kind)) {
                JSONArray items = object.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    JSONObject first = items.optJSONObject(0);
                    if (first != null) showImageDialog(first.optString("data", ""));
                }
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
                    View content = getLayoutInflater().inflate(R.layout.dialog_video_viewer, null, false);
                    android.widget.VideoView video = content.findViewById(R.id.viewer_video);
                    MaterialButton toggle = content.findViewById(R.id.viewer_video_toggle);
                    android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                    dialog.setContentView(content);
                    dialog.setOnDismissListener(ignored -> { video.stopPlayback(); file.delete(); });
                    dialog.show();
                    if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
                    video.setVideoURI(Uri.fromFile(file));
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
            } catch (Exception error) { runOnUiThread(() -> toast("Не удалось открыть видео.")); }
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

    private void showMessageMenu(ChatMessage message, String payload) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_message_actions, null, false);
        boolean persisted = message.id != null && !message.id.startsWith("local_") && !message.pending;
        boolean ownMessage = uid != null && uid.equals(message.senderUid);

        content.findViewById(R.id.message_react).setVisibility(persisted ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.message_reply).setVisibility(persisted ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.message_edit).setVisibility(persisted && ownMessage ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.message_delete).setVisibility(persisted ? View.VISIBLE : View.GONE);
        content.findViewById(R.id.message_actions_divider).setVisibility(persisted ? View.VISIBLE : View.GONE);

        content.findViewById(R.id.message_react).setOnClickListener(v -> {
            sheet.dismiss();
            toggleHeart(message);
        });
        content.findViewById(R.id.message_reply).setOnClickListener(v -> {
            sheet.dismiss();
            replyToId = message.id;
            replyToText = payload == null ? "Сообщение" : textFromPayload(payload);
            if (replyBannerText != null) replyBannerText.setText(replyToText);
            if (replyBanner != null) replyBanner.setVisibility(View.VISIBLE);
            if (composer != null) composer.requestFocus();
        });
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
        try { old = new JSONObject(payload).optString("text", ""); } catch (Exception e) { toast("Можно редактировать только текст."); return; }
        View editor = getLayoutInflater().inflate(R.layout.dialog_edit_message, null, false);
        TextInputEditText edit = editor.findViewById(R.id.edit_message_input);
        edit.setText(old);
        new AlertDialog.Builder(this).setTitle("Редактировать сообщение").setView(editor)
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
            String payloadJson = payload.toString();
            composer.setText("");
            replyToId = null;
            replyToText = null;
            if (replyBanner != null) replyBanner.setVisibility(View.GONE);
            chatRepository.setTyping(chatId, senderUid, false);
            enqueueOptimisticMessage(chatId, peerUid, senderUid, payloadJson);
        } catch (Exception error) {
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
        if (messageCache != null) messageCache.put(chatId, local.id, payloadJson);

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
        watchSummaryRealtime(chatId, peerUid);
        renderChatList();
        if (chatId.equals(activeChatId) && "chat".equals(page)) renderMessages(lastRemoteMessages, peerUid);
        dispatchOptimisticMessage(local, peerUid);
        return local;
    }

    private void dispatchOptimisticMessage(ChatMessage local, String peerUid) {
        String chatId = local.localChatId;
        String senderUid = local.senderUid;
        if (chatId == null || peerUid == null || senderUid == null || crypto == null || local.localPayload == null) {
            local.pending = false;
            local.failed = true;
            if (chatId != null && chatId.equals(activeChatId)) renderMessages(lastRemoteMessages, activePeerUid);
            return;
        }
        SignalE2ee activeCrypto = crypto;
        LocalMessageCache activeCache = messageCache;
        try {
            chatRepository.sendPayload(chatId, senderUid, peerUid, activeCrypto,
                    local.localPayload.getBytes(StandardCharsets.UTF_8), local.clientId,
                    new ChatRepository.Result<String>() {
                        @Override public void success(String messageId) {
                            if (activeCache != null) activeCache.put(chatId, messageId, local.localPayload);
                            ChatMessage current = optimisticMessages.get(local.clientId);
                            if (current != null) {
                                current.id = messageId;
                                current.pending = false;
                                current.failed = false;
                            }
                            if (chatId.equals(activeChatId)) renderMessages(lastRemoteMessages, peerUid);
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
                            if (chatId.equals(activeChatId)) renderMessages(lastRemoteMessages, peerUid);
                            renderChatList();
                            toast(error);
                        }
                    });
        } catch (Exception error) {
            local.pending = false;
            local.failed = true;
            if (chatId.equals(activeChatId)) renderMessages(lastRemoteMessages, peerUid);
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
        ChatSummary summary = summaries.get(retry.localChatId);
        if (summary != null) { summary.lastAt = retry.sentAt; summary.lastMessage = retry; renderChatList(); }
        renderMessages(lastRemoteMessages, activePeerUid);
        dispatchOptimisticMessage(retry, activePeerUid);
    }


    private void showChatMenu() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.sheet_chat_actions, null, false);
        content.findViewById(R.id.chat_menu_profile).setOnClickListener(v -> {
            sheet.dismiss();
            showPeerProfile();
        });
        content.findViewById(R.id.chat_menu_export).setOnClickListener(v -> {
            sheet.dismiss();
            exportCurrentChat();
        });
        content.findViewById(R.id.chat_menu_keys).setOnClickListener(v -> {
            sheet.dismiss();
            showKeySettings();
        });
        sheet.setContentView(content);
        sheet.show();
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
            } catch (Exception error) { fingerprint.setText("Не удалось загрузить код безопасности."); }
        });
        trustKey.setOnClickListener(v -> {
            if (peerBundle[0] == null || accountCrypto == null) { toast("Код безопасности пока недоступен."); return; }
            String shownFingerprint;
            try { shownFingerprint = SignalE2ee.identityFingerprint(peerBundle[0]); }
            catch (Exception error) { toast("Не удалось проверить код безопасности."); return; }
            new AlertDialog.Builder(this).setTitle("Проверка кода безопасности")
                    .setMessage("Сравните этот код с собеседником лично или по телефону:\n\n" + shownFingerprint + "\n\nПодтвердите только в том случае, если коды совпадают.")
                    .setNegativeButton("Отмена", null).setPositiveButton("Совпадает", (dialog, which) -> cryptoQueue.execute(() -> {
                        try {
                            accountCrypto.trustRemoteIdentity(profilePeerUid, peerBundle[0]);
                            runOnUiThread(() -> toast("Код безопасности подтверждён."));
                        } catch (Exception error) { runOnUiThread(() -> toast("Не удалось подтвердить код безопасности.")); }
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
        } catch (Exception e) {
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
            } catch (Exception error) {
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
                String payload = new JSONObject().put("version", 1).put("kind", kind)
                        .put("mime", mime == null ? "application/octet-stream" : mime)
                        .put("data", base64).toString();
                runOnUiThread(() -> enqueueOptimisticMessage(chatId, peerUid, senderUid, payload));
            } catch (Exception error) {
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
            } catch (Exception error) {
                runOnUiThread(() -> toast("Не удалось подготовить коллаж."));
            }
        });
    }

    private void showImageDialog(String imageBase64) {
        try {
            byte[] bytes = Base64.decode(imageBase64, Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
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
        View root = showScreen(R.layout.screen_settings);
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
        syncSwitch.setChecked(getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("background_sync", true));
        syncSwitch.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit().putBoolean("background_sync", checked).apply();
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
        setupBottomNavigation(root, R.id.nav_settings);
    }

    private void setupBottomNavigation(View root, int selectedItem) {
        BottomNavigationView navigation = root.findViewById(R.id.main_navigation);
        if (navigation == null) return;
        navigation.setSelectedItemId(selectedItem);
        navigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_chats) showHome();
            else if (id == R.id.nav_search) showSearchScreen();
            else if (id == R.id.nav_settings) showSettings();
            else return false;
            return true;
        });
    }

    private void showPrivacySettings() {
        String[] keys = {"messages", "lastSeen", "photo", "about", "displayName"};
        Map<String, String> initial = new HashMap<>();
        for (String key : keys) initial.put(key, getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getString("privacy_" + key, "Все"));
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
                        getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit()
                                .putString("privacy_" + entry.getKey(), String.valueOf(entry.getValue())).apply();
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
        int current = getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getInt("text_size", 14);
        size.setValue(Math.max(12, Math.min(22, current)));
        MaterialCheckBox bold = body.findViewById(R.id.customization_bold);
        MaterialCheckBox italic = body.findViewById(R.id.customization_italic);
        bold.setChecked(isTextBold());
        italic.setChecked(getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("italic", false));
        new AlertDialog.Builder(this).setTitle("Кастомизация").setView(body)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    getSharedPreferences("tsuyu_settings", MODE_PRIVATE).edit()
                            .putInt("text_size", Math.round(size.getValue()))
                            .putBoolean("bold", bold.isChecked())
                            .putBoolean("italic", italic.isChecked()).apply();
                    if (messageListAdapter != null && messageList != null && "chat".equals(page)) {
                        renderMessages(lastRemoteMessages, activePeerUid);
                    }
                }).show();
    }

    private void showNotificationSettings() {
        View body = getLayoutInflater().inflate(R.layout.dialog_notifications, null, false);
        MaterialSwitch sound = body.findViewById(R.id.notifications_sound_switch);
        MaterialButton choose = body.findViewById(R.id.notifications_choose_sound);
        sound.setChecked(NotificationSounds.isEnabled(this));
        sound.setOnCheckedChangeListener((button, checked) -> NotificationSounds.setEnabled(this, checked));
        choose.setOnClickListener(v -> {
            try { pendingMediaKind = "sound"; documentPicker.launch(new String[]{"audio/mpeg", "audio/*"}); }
            catch (Exception error) { toast("Не удалось открыть выбор файла."); }
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
        } catch (Exception error) {
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
            } catch (Exception error) { runOnUiThread(() -> toast("Не удалось обновить код безопасности.")); }
        });
    }

    private View showScreen(int layoutResource) {
        if (screenHost == null) {
            setContentView(R.layout.activity_main);
            screenHost = findViewById(R.id.screen_host);
        }
        View next = getLayoutInflater().inflate(layoutResource, screenHost, false);
        if (screenHost.getChildCount() > 0) {
            MaterialFadeThrough transition = new MaterialFadeThrough();
            transition.setDuration(220L);
            TransitionManager.beginDelayedTransition(screenHost, transition);
            screenHost.removeAllViews();
        }
        screenHost.addView(next);
        next.startAnimation(AnimationUtils.loadAnimation(this, R.anim.screen_enter));
        currentScreen = next;
        return next;
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
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new IllegalArgumentException("Invalid avatar data");
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
        } catch (Exception error) {
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
            return "Сообщение";
        } catch (Exception e) { return "Сообщение"; }
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
        messageList = null; messageListAdapter = null; chatEmptyState = null;
        composer = null; replyBanner = null; replyBannerText = null; chatStatus = null; chatAvatarDot = null;
        ui.removeCallbacks(clearTyping);
        messageReactions.clear();
    }

    private int textSize() { return getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getInt("text_size", 14); }
    private boolean isTextBold() { return getSharedPreferences("tsuyu_settings", MODE_PRIVATE).getBoolean("bold", false); }
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
    private String authError(Exception e) { return "Не удалось войти. Проверьте почту и пароль или попробуйте позже."; }
    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0L; }

    @Override
    public void onBackPressed() {
        if ("chat".equals(page)) { detachChatListeners(); showHome(); return; }
        if ("profile_edit".equals(page)) {
            if (isNewProfile) auth.signOut(); else showHome();
            return;
        }
        if ("search".equals(page) || "settings".equals(page)) { showHome(); return; }
        super.onBackPressed();
    }
}
