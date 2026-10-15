package com.tsuyu.messenger.ui.dialogs;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.tsuyu.messenger.R;

/**
 * Менеджер модальной шторки вложений (sheet_attachments).
 */
public final class AttachmentSheetManager {

    public interface AttachmentActions {
        void onPhoto();
        void onPhotos();
        void onVideo();
        void onAudio();
        void onPoll();
        void onContact();
        void onSchedule();
        void onBurn();
        void onVoice();
    }

    public static void show(Context context, AttachmentActions actions) {
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.sheet_attachments, null, false);
        sheet.setContentView(view);

        bindClick(view, R.id.attachment_photo, sheet, actions != null ? actions::onPhoto : null);
        bindClick(view, R.id.attachment_photos, sheet, actions != null ? actions::onPhotos : null);
        bindClick(view, R.id.attachment_video, sheet, actions != null ? actions::onVideo : null);
        bindClick(view, R.id.attachment_audio, sheet, actions != null ? actions::onAudio : null);
        bindClick(view, R.id.attachment_poll, sheet, actions != null ? actions::onPoll : null);
        bindClick(view, R.id.attachment_contact, sheet, actions != null ? actions::onContact : null);
        bindClick(view, R.id.attachment_schedule, sheet, actions != null ? actions::onSchedule : null);
        bindClick(view, R.id.attachment_burn, sheet, actions != null ? actions::onBurn : null);
        bindClick(view, R.id.attachment_voice, sheet, actions != null ? actions::onVoice : null);

        sheet.show();
    }

    private static void bindClick(View root, int id, BottomSheetDialog sheet, Runnable action) {
        View target = root.findViewById(id);
        if (target != null && action != null) {
            target.setOnClickListener(v -> {
                sheet.dismiss();
                action.run();
            });
        }
    }
}
