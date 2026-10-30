package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * TikAntiRevoke — keeps deleted messages visible locally when the
 * Anti-Revoke flag is on.
 *
 * Drop-in path:
 *   TMessagesProj/src/main/java/org/telegram/messenger/TikAntiRevoke.java
 *
 * Mechanics: when a delete arrives (push path or updates stream),
 * MessagesController records the ids here and skips the storage
 * deletion, while still marking the in-memory message objects as
 * deleted so ChatMessageCell renders its own deleted-path layout.
 * ChatActivity.processDeletedMessages filters these ids out, so the
 * rows are never removed from the visible list.
 *
 * Honest limitation: the explicit "this message was deleted" text
 * label is NOT drawn — that would need custom drawing inside
 * ChatMessageCell.onDraw, judged too risky to touch blind. What IS
 * preserved: the message content stays visible, with the cell's
 * built-in deleted-path rendering (name layout and share button
 * suppressed).
 *
 * Storage: one StringSet of "dialogId:msgId" in SharedPreferences
 * "tikgram_antirevoke". The set is only consulted for deletes that
 * arrive while the flag is on; clearing the flag does not retroactively
 * delete preserved messages.
 */
public class TikAntiRevoke {

    private static final String PREFS = "tikgram_antirevoke";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    private static String key(long dialogId, int msgId) {
        return dialogId + ":" + msgId;
    }

    public static void markDeleted(long dialogId, int msgId) {
        Set<String> set = new HashSet<>(prefs().getStringSet("deleted", new HashSet<>()));
        set.add(key(dialogId, msgId));
        prefs().edit().putStringSet("deleted", set).apply();
    }

    public static void markDeleted(long dialogId, ArrayList<Integer> msgIds) {
        if (msgIds == null || msgIds.isEmpty()) {
            return;
        }
        Set<String> set = new HashSet<>(prefs().getStringSet("deleted", new HashSet<>()));
        for (Integer id : msgIds) {
            set.add(key(dialogId, id));
        }
        prefs().edit().putStringSet("deleted", set).apply();
    }

    public static boolean isMarked(long dialogId, int msgId) {
        return prefs().getStringSet("deleted", new HashSet<>()).contains(key(dialogId, msgId));
    }
}
