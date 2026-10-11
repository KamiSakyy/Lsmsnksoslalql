package com.tsuyu.messenger;

public class Profile {
    public String uid;
    public String username;
    public String displayName;
    public String about;
    public String avatarBase64;

    public Profile() {}

    public String displayNameOrUsername() {
        if (displayName != null && !displayName.trim().isEmpty()) return displayName;
        return username == null ? "Tsuyu user" : "@" + username;
    }
}
