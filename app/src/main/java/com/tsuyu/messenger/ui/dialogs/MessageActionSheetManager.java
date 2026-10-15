package com.tsuyu.messenger.ui.dialogs;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.tsuyu.Lsmsnksoslalql.R;

/**
 * Менеджер контекстной шторки действий над сообщением (sheet_message_actions).
 */
public final class MessageActionSheetManager {

    public interface MessageActions {
        void onReaction(String emoji);
        void onReply();
        void onPin();
        void onForward();
        void onCopy();
        void onEdit();
        void onDelete();
    }

    public static void show(Context context, boolean isPinned, boolean canEdit, MessageActions actions) {
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.sheet_message_actions, null, false);
        sheet.setContentView(view);

        TextView pinLabel = view.findViewById(R.id.message_pin_label);
        if (pinLabel != null) {
            pinLabel.setText(isPinned ? R.string.msg_unpin : R.string.msg_pin);
        }

        View editRow = view.findViewById(R.id.message_edit);
        if (editRow != null) {
            editRow.setVisibility(canEdit ? View.VISIBLE : View.GONE);
        }

        View reactBar = view.findViewById(R.id.message_react);
        if (reactBar instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) reactBar;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    TextView tv = (TextView) child;
                    tv.setOnClickListener(v -> {
                        sheet.dismiss();
                        if (actions != null) actions.onReaction(tv.getText().toString());
                    });
                }
            }
        }

        bindAction(view, R.id.message_reply, sheet, actions != null ? actions::onReply : null);
        bindAction(view, R.id.message_pin, sheet, actions != null ? actions::onPin : null);
        bindAction(view, R.id.message_forward, sheet, actions != null ? actions::onForward : null);
        bindAction(view, R.id.message_copy, sheet, actions != null ? actions::onCopy : null);
        bindAction(view, R.id.message_edit, sheet, actions != null ? actions::onEdit : null);
        bindAction(view, R.id.message_delete, sheet, actions != null ? actions::onDelete : null);

        View cancel = view.findViewById(R.id.message_cancel);
        if (cancel != null) {
            cancel.setOnClickListener(v -> sheet.dismiss());
        }

        sheet.show();
    }

    private static void bindAction(View root, int id, BottomSheetDialog sheet, Runnable action) {
        View target = root.findViewById(id);
        if (target != null && action != null) {
            target.setOnClickListener(v -> {
                sheet.dismiss();
                action.run();
            });
        }
    }
}
