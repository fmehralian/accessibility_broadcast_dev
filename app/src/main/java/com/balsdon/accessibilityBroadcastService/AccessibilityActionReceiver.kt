package com.balsdon.accessibilityBroadcastService

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.balsdon.accessibilityDeveloperService.AccessibilityDeveloperService
import com.balsdon.accessibilityDeveloperService.AccessibilityDeveloperService.Companion.DIRECTION_BACK
import com.balsdon.accessibilityDeveloperService.AccessibilityDeveloperService.Companion.DIRECTION_FORWARD

class AccessibilityActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACCESSIBILITY_ACTION = "ACTION"
        const val BROADCAST_ID = "BROADCAST_ID"

        const val ACTION_SWIPE_LEFT = "ACTION_SWIPE_LEFT"
        const val ACTION_SWIPE_RIGHT = "ACTION_SWIPE_RIGHT"
        const val ACTION_SWIPE_UP = "ACTION_SWIPE_UP"
        const val ACTION_SWIPE_DOWN = "ACTION_SWIPE_DOWN"
        const val ACTION_SWIPE_UP_RIGHT = "ACTION_SWIPE_UP_RIGHT"
        const val ACTION_SWIPE_RIGHT_WAIT_CAPTURE = "ACTION_SWIPE_RIGHT_WAIT_CAPTURE"
        const val ACTION_IDLE_CAPTURE = "ACTION_IDLE_CAPTURE"

        const val ACTION_CLICK = "ACTION_CLICK"
        const val ACTION_CLICK_WAIT_CAPTURE = "ACTION_CWC"
        const val ACTION_LONG_CLICK = "ACTION_LONG_CLICK"
        const val ACTION_CURTAIN = "ACTION_CURTAIN"

        const val ACTION_FOCUS_ELEMENT = "ACTION_FOCUS_ELEMENT"

        const val ACTION_VOLUME_UP = "ACTION_VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "ACTION_VOLUME_DOWN"
        const val ACTION_VOLUME_SET = "ACTION_VOLUME_SET"
        const val ACTION_VOLUME_MUTE = "ACTION_VOLUME_MUTE"

        const val ACTION_WHICH = "ACTION_WHICH"

        const val ACTION_SAY = "ACTION_SAY"

        const val PARAMETER_VOLUME = "PARAMETER_VOLUME"
        const val PARAMETER_ID = "PARAMETER_ID"
        const val PARAMETER_TEXT = "PARAMETER_TEXT"
        const val PARAMETER_HEADING = "PARAMETER_HEADING"
        const val PARAMETER_DIRECTION = "PARAMETER_DIRECTION"
        const val PARAMETER_TYPE = "PARAMETER_TYPE"

        const val ACTION_DUMP_A11Y_TREE = "ACTION_DUMP_A11Y_TREE"
        const val ACTION_LOG_TB_TREE = "ACTION_LOG_TB_TREE"

        const val ACTION_MENU = "ACTION_MENU"
    }

    private fun showError(context: Context, message: String) {
        log("AccessibilityDeveloperService", message)
        context.showToast(message)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onReceive(context: Context?, intent: Intent?) {
        require(context != null) { "Context is required" }
        require(intent != null) { "Intent is required" }
        val broadcastID = intent.getStringExtra(BROADCAST_ID)
        log("AccessibilityActionReceiver", " ~~> broadcastID: [$broadcastID]")
        require(broadcastID != null) { "Broadcast ID is required"}

        if (TALKBACK_PACKAGE_NAMES.intersect(
                Settings
                    .Secure
                    .getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ).split(":").toSet()
            ).isEmpty()
        ) {
            log("AccessibilityActionReceiver", "TalkBack needs to be running.")
            return
        }
        val accessibilityDeveloperServiceReference = AccessibilityDeveloperService.instance.get()
        require(accessibilityDeveloperServiceReference != null) { "Service is required" }
        val serviceReference: AccessibilityDeveloperService = accessibilityDeveloperServiceReference

        intent.getStringExtra(ACCESSIBILITY_ACTION)?.let {
            log("AccessibilityActionReceiver", "  ~~> ACTION: [$it]")
            serviceReference.apply {
                when (it) {
                    ACTION_MENU -> swipeUpRight(broadcastID)
                    ACTION_LOG_TB_TREE -> swipeUpLeft(broadcastID)
                    ACTION_DUMP_A11Y_TREE -> dumpA11yTree(broadcastID)
                    ACTION_SWIPE_LEFT -> swipeHorizontal(true, broadcastID)
                    ACTION_SWIPE_RIGHT -> swipeHorizontal(false, broadcastID)
                    ACTION_SWIPE_RIGHT_WAIT_CAPTURE -> swipeHorizontalWaitCapture(false, broadcastID)
                    ACTION_SWIPE_UP -> swipeVertical(true, broadcastID)
                    ACTION_SWIPE_DOWN -> swipeVertical(false, broadcastID)
                    ACTION_CLICK -> click(broadcastId = broadcastID)
                    ACTION_CLICK_WAIT_CAPTURE -> clickWaitCapture(broadcastId = broadcastID)
                    ACTION_LONG_CLICK -> click(true, broadcastId = broadcastID)
                    ACTION_IDLE_CAPTURE -> captureWhenIdle(broadcastID)
                    ACTION_CURTAIN -> toggleCurtain()
                    ACTION_SAY -> {
                        if (intent.hasExtra(PARAMETER_TEXT)) {
                            val value = intent.getStringExtra(PARAMETER_TEXT)
                            log(
                                "AccessibilityActionReceiver [$ACTION_SAY]",
                                "    ~~> TYPE: [$PARAMETER_TEXT]: $value"
                            )
                            if (value == null) {
                                showError(context, "Required value: $PARAMETER_TEXT")
                                return
                            } else {
                                announceText(value)
                            }
                        }
                    }
                    ACTION_FOCUS_ELEMENT -> {
                        if (intent.hasExtra(PARAMETER_ID)) {
                            val value = intent.getStringExtra(PARAMETER_ID)
                            log(
                                "AccessibilityActionReceiver",
                                "    ~~> TYPE: [$PARAMETER_ID]: $value"
                            )
                            if (value == null) {
                                showError(context, "Required value: $PARAMETER_ID")
                                return
                            }
                            focus(AccessibilityDeveloperService.SelectionType.ELEMENT_ID, value)
                        } else if (intent.hasExtra(PARAMETER_TEXT)) {
                            val value = intent.getStringExtra(PARAMETER_TEXT)
                            log(
                                "AccessibilityActionReceiver",
                                "    ~~> TYPE: [$PARAMETER_TEXT]: $value"
                            )
                            if (value == null) {
                                showError(context, "Required value: $PARAMETER_TEXT")
                                return
                            }
                            focus(AccessibilityDeveloperService.SelectionType.ELEMENT_TEXT, value)
                        } else if (intent.hasExtra(PARAMETER_TYPE)) {
                            if (intent.hasExtra(PARAMETER_DIRECTION)) {
                                val dir = (intent.getStringExtra(PARAMETER_DIRECTION)
                                    ?: DIRECTION_FORWARD).uppercase() == DIRECTION_FORWARD
                                log(
                                    "AccessibilityActionReceiver",
                                    "    ~~> TYPE: [$PARAMETER_TYPE]: $dir"
                                )
                                val value = intent.getStringExtra(PARAMETER_TYPE)
                                if (value == null) {
                                    showError(context, "Required value: $PARAMETER_TYPE")
                                    return
                                }
                                focus(
                                    AccessibilityDeveloperService.SelectionType.ELEMENT_TYPE,
                                    value,
                                    dir
                                )
                            } else {
                                showError(
                                    context,
                                    "ERROR: PARAMETER_DIRECTION REQUIRED, EITHER [$DIRECTION_FORWARD] OR [$DIRECTION_BACK]"
                                )
                            }
                        } else if (intent.hasExtra(PARAMETER_HEADING)) {
                            val dir = (intent.getStringExtra(PARAMETER_HEADING)
                                ?: DIRECTION_FORWARD).uppercase() == DIRECTION_FORWARD
                            log(
                                "AccessibilityActionReceiver",
                                "    ~~> TYPE: [$PARAMETER_HEADING]: DIRECTION: $dir"
                            )
                            focus(
                                AccessibilityDeveloperService.SelectionType.ELEMENT_HEADING,
                                "",
                                dir
                            )
                        } else {
                            showError(
                                context,
                                "$ACTION_FOCUS_ELEMENT requires a parameter of $PARAMETER_ID, $PARAMETER_TEXT, $PARAMETER_TYPE or $PARAMETER_HEADING"
                            )
                        }
                    }
                    ACTION_VOLUME_UP -> adjustVolume(true)
                    ACTION_VOLUME_DOWN -> adjustVolume(false)

                    ACTION_VOLUME_SET -> setVolume(intent.getIntExtra(PARAMETER_VOLUME, 10))
                    ACTION_VOLUME_MUTE -> setVolume(0)
                    ACTION_WHICH -> findFocusedViewInfo()

                    else -> showError(context, it)
                }
            }
        } ?: serviceReference.swipeHorizontal(true, broadcastID)
    }
}