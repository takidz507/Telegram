package org.telegram.messenger;

import android.content.Intent;
import android.net.Uri;

import org.telegram.messenger.voip.VoIPService;

/**
 * TikIncallShareController — pure decision logic for Tikgram Step 5
 * (transparent in-call sharing, Phase 1, screencast path).
 *
 * This class owns NO views: VoIPFragment owns the preview container and
 * calls into here for the picker intent, the badge text, mime detection
 * and the ringing-queue vs established-start state decisions.
 *
 * TRANSPARENCY HARD RULES (never change these without a new user decision):
 * - BADGE_TEXT is shown whenever sharing is active. It is NOT gated by
 *   any TikConfig flag, NOT hideable and NOT toggleable. The only flag
 *   that gates anything here is incall_share_loop, and it gates only
 *   whether a shared VIDEO loops — never the badge.
 * - No message is ever sent on the user's behalf by this feature.
 * - Video-file audio is NOT injected in Phase 1 (native work); the
 *   microphone stays live. Photo sharing has no audio question at all.
 * - Nothing streams before the call connects: a pick made while the
 *   call is not ESTABLISHED is only queued, and the queue is dropped
 *   when the call ends.
 */
public class TikIncallShareController {

    /** Request code for the in-call share picker (LaunchActivity dispatches it). */
    public static final int PICK_REQUEST_CODE = 9021;

    /**
     * Badge text, same bilingual style as TikBadge ("وسائط / Shared Media").
     * Rendered as a persistent top-center view inside the screencast
     * capture, so the remote side sees it from the very first frame.
     * A true render-pass burn into the video stream needs native phase 2;
     * Phase 1 honestly uses the on-screen badge inside the screencast.
     */
    public static final String BADGE_TEXT = "وسائط / Shared Video";

    /** SAF picker: photo OR video, user-picked only, never auto-sent. */
    public static Intent createPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    /** True when the picked Uri is a photo (shown as a still frame). */
    public static boolean isImageUri(android.content.ContentResolver resolver, Uri uri) {
        String mime = resolver != null ? resolver.getType(uri) : null;
        if (mime != null) {
            return mime.startsWith("image/");
        }
        // Fallback for providers that report no mime type: file extension.
        String path = uri.getLastPathSegment();
        if (path != null) {
            String lower = path.toLowerCase(java.util.Locale.US);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".png") || lower.endsWith(".webp")
                    || lower.endsWith(".gif") || lower.endsWith(".bmp");
        }
        return false;
    }

    /** True when the call has not connected yet (ringing / pre-answer states). */
    public static boolean isPreAnswerState(int callState) {
        return callState == VoIPService.STATE_WAITING
                || callState == VoIPService.STATE_REQUESTING
                || callState == VoIPService.STATE_WAITING_INCOMING
                || callState == VoIPService.STATE_RINGING
                || callState == VoIPService.STATE_WAIT_INIT
                || callState == VoIPService.STATE_WAIT_INIT_ACK
                || callState == VoIPService.STATE_EXCHANGING_KEYS;
    }

    /** Sharing starts immediately only in this state. */
    public static boolean shouldStartImmediately(int callState) {
        return callState == VoIPService.STATE_ESTABLISHED;
    }

    /** The call is over (or failed): drop any queued pick, stop any live share. */
    public static boolean isCallOverState(int callState) {
        return callState == VoIPService.STATE_ENDED
                || callState == VoIPService.STATE_FAILED
                || callState == VoIPService.STATE_BUSY
                || callState == VoIPService.STATE_HANGING_UP;
    }
}
