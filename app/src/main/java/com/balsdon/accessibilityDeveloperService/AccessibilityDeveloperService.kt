package com.balsdon.accessibilityDeveloperService

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Xml
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.*
import android.widget.*
import androidx.annotation.IdRes
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.xmlpull.v1.XmlSerializer
import java.io.*
import java.lang.Thread.sleep


/*
flagRequestAccessibilityButton: Will show the accessibility button on the bottom right hand side
 */
class AccessibilityDeveloperService : AccessibilityService() {
    enum class SelectionType {
        ELEMENT_ID, ELEMENT_TYPE, ELEMENT_TEXT, ELEMENT_HEADING
    }

    companion object {
        //TODO: BUG [03] Not a huge fan of this...
        //https://developer.android.com/reference/android/content/BroadcastReceiver#peekService(android.content.Context,%20android.content.Intent)
        var instance: AccessibilityDeveloperService? = null
        val DIRECTION_FORWARD = "DIRECTION_FORWARD"
        val DIRECTION_BACK = "DIRECTION_BACK"

        private val accessibilityButtonCallback =
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    log(
                        "AccessibilityDeveloperService",
                        "    ~~> AccessibilityButtonCallback"
                    )

                    // Add custom logic for a service to react to the
                    // accessibility button being pressed.
                }

                override fun onAvailabilityChanged(
                    controller: AccessibilityButtonController,
                    available: Boolean
                ) {
                    log(
                        "AccessibilityDeveloperService",
                        "    ~~> AccessibilityButtonCallback availability [$available]"
                    )
                }
            }
    }

    private fun Context.AccessibilityManager() =
        getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    private val accessibilityActionReceiver = AccessibilityActionReceiver()
    private val audioManager: AudioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val displayMetrics: DisplayMetrics by lazy { Resources.getSystem().displayMetrics }
    private val halfWidth: Float by lazy { (displayMetrics.widthPixels) / 2f }
    private val halfHeight: Float by lazy { (displayMetrics.heightPixels) / 2f }
    private val quarterWidth: Float by lazy { halfWidth / 2f }
    private val quarterHeight: Float by lazy { halfWidth / 2f }

    private var curtainView: FrameLayout? = null

    private fun <T : View> findElement(@IdRes resId: Int): T =
        curtainView?.findViewById<T>(resId)
            ?: throw RuntimeException("Required view not found: CurtainView")

    private val announcementTextView: TextView
        get() {
            return findElement(R.id.announcementText)
        }
    private val classNameTextView: TextView
        get() {
            return findElement(R.id.className)
        }
    private val enabledCheckBox: CheckBox
        get() {
            return findElement(R.id.enabled)
        }
    private val checkedCheckBox: CheckBox
        get() {
            return findElement(R.id.checked)
        }
    private val scrollableCheckBox: CheckBox
        get() {
            return findElement(R.id.scrollable)
        }
    private val passwordCheckBox: CheckBox
        get() {
            return findElement(R.id.password)
        }
    private val headingCheckBox: CheckBox
        get() {
            return findElement(R.id.heading)
        }
    private val editableCheckBox: CheckBox
        get() {
            return findElement(R.id.editable)
        }

    //REQUIRED overrides... not used
    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        log("AccessibilityDeveloperService", "  ~~> onAccessibilityEvent [$event]")

        if (event.eventType == TYPE_WINDOW_STATE_CHANGED) return

        if (!event.text.isNullOrEmpty() && curtainView != null) {
            log("AccessibilityDeveloperService", "  ~~> Announce [$event]")
            announcementTextView.text = event.text.toString()
                .replace('[', ' ')
                .replace(']', ' ')
                .trim()

            classNameTextView.text = event.className
            passwordCheckBox.isChecked = event.isPassword
            enabledCheckBox.isChecked = event.isEnabled
            checkedCheckBox.isChecked = event.isChecked
            scrollableCheckBox.isChecked = event.isChecked

            val currentNode = this.findFocus(FOCUS_ACCESSIBILITY)
            if (currentNode == null) {
                headingCheckBox.isChecked = false
                editableCheckBox.isChecked = false
            } else {
                headingCheckBox.isChecked = currentNode.isHeading
                editableCheckBox.isChecked = currentNode.isEditable
            }
        }
    }

    fun findFocusedViewInfo(): AccessibilityNodeInfoCompat = with(rootInActiveWindow) {
        val viewInfo = this.findFocus(FOCUS_ACCESSIBILITY)
        log(
            "AccessibilityDeveloperService",
            "  ~~> View in focus: [${viewInfo.className} : ${viewInfo.viewIdResourceName}]"
        )
        return AccessibilityNodeInfoCompat.wrap(viewInfo)
    }

    override fun onServiceConnected() {
        log(
            "AccessibilityDeveloperService",
            "onServiceConnected"
        )
        registerReceiver(accessibilityActionReceiver, IntentFilter().apply {
            addAction(ACCESSIBILITY_CONTROL_BROADCAST_ACTION)
            priority = 100
            log(
                "AccessibilityDeveloperService",
                "    ~~> Receiver is registered."
            )
        })
        instance = this

        //https://developer.android.com/guide/topics/ui/accessibility/service
        if (accessibilityButtonController.isAccessibilityButtonAvailable) {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                accessibilityButtonCallback
            )
        }
    }

    fun toggleCurtain() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (curtainView == null) {
            curtainView = FrameLayout(this)
            val lp = WindowManager.LayoutParams()
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            lp.format = PixelFormat.OPAQUE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.TOP
            val inflater = LayoutInflater.from(this)
            inflater.inflate(R.layout.accessibility_curtain, curtainView)
            wm.addView(curtainView, lp)
        } else {
            wm.removeView(curtainView)
            curtainView = null
        }
    }

    private fun dfsTree(
        currentNode: AccessibilityNodeInfo = rootInActiveWindow,
        depth: Int = 0
    ): List<Pair<AccessibilityNodeInfo, Int>> {
        val list = mutableListOf(Pair(currentNode, depth))
        if (currentNode.childCount > 0) {
            for (index in 0 until currentNode.childCount) {
                list.addAll(dfsTree(currentNode.getChild(index), depth + 1))
            }
        }
        return list
    }

    fun debugAction() {
        dfsTree().forEach {
            val compatNode = AccessibilityNodeInfoCompat.wrap(it.first)
            log("dfsTree", "${it.second}->[${compatNode}]$compatNode")
        }
    }

    fun announceText(speakText: String) =
        AccessibilityManager().apply {
            sendAccessibilityEvent(AccessibilityEvent.obtain().apply {
                eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
                text.add(speakText)
            })
        }

    private fun focusBy(next: Boolean? = null, comparison: (AccessibilityNodeInfo) -> Boolean) {
        val tree = if (next == false) dfsTree().asReversed() else dfsTree()
        val currentNode = this.findFocus(FOCUS_ACCESSIBILITY)
        if (currentNode == null) {
            val firstNode = tree.firstOrNull { comparison(it.first) }
            firstNode?.first?.performAction(ACTION_ACCESSIBILITY_FOCUS)
            return
        }

        val index = tree.indexOfFirst { it.first == currentNode }
        if (next == null) {
            for (currentIndex in tree.indices) {
                if (comparison(tree[currentIndex].first)) {
                    tree[currentIndex].first.performAction(ACTION_ACCESSIBILITY_FOCUS)
                    return
                }
            }
        } else {
            for (currentIndex in index + 1 until tree.size) {
                if (comparison(tree[currentIndex].first)) {
                    tree[currentIndex].first.performAction(ACTION_ACCESSIBILITY_FOCUS)
                    return
                }
            }
        }
    }

    //TODO: Bug [02]: Need to scroll to element if it's not in view
    fun focus(type: SelectionType, value: String, next: Boolean = true) {
        when (type) {
            SelectionType.ELEMENT_ID -> focusBy(null) {
                it.viewIdResourceName?.toLowerCase()?.contains(value.toLowerCase()) ?: false
            }
            SelectionType.ELEMENT_TEXT -> focusBy(null) {
                it.text?.toString()?.toLowerCase()?.contains(value.toLowerCase()) ?: false
            }
            SelectionType.ELEMENT_TYPE -> focusBy(next) { it.className == value }
            SelectionType.ELEMENT_HEADING -> focusBy(next) { it.isHeading }
        }
    }

    fun click(long: Boolean = false, broadcastId: String) {
        log("click", "start to click")
        val res = findFocusedViewInfo().performAction(if (long) ACTION_LONG_CLICK else ACTION_CLICK)
        log("click","clicking res fro " + broadcastId + ": " + res)
    }

    fun commonDocumentDirPath(FolderName: String): File? {
        var dir: File? = null
        dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    .toString() + "/" + FolderName
            )
        } else {
            File(Environment.getExternalStorageDirectory().toString() + "/" + FolderName)
        }

        // Make sure the path directory exists.
        if (!dir.exists()) {
            // Make it, if it doesn't exit
            val success = dir.mkdirs()
            if (!success) {
                dir = null
            }
        }
        log("dump", Environment.getExternalStorageDirectory().toString())
        return dir
    }


    fun dumpA11yTree(broadcastID: String) {
        var LOG_TAG = "DumpResultCallback"
        log(LOG_TAG, "start to dump")
        val startTime = SystemClock.uptimeMillis()
        val file = File(AccessibilityDeveloperService.instance?.baseContext?.filesDir?.path, "a11y3-$broadcastID.xml")

        val outputStream: OutputStream = FileOutputStream(file)
        val writer = OutputStreamWriter(outputStream)
        val serializer: XmlSerializer = Xml.newSerializer()
        val stringWriter = StringWriter()
        serializer.setOutput(stringWriter)
        serializer.startDocument("UTF-8", true)
        serializer.startTag("", "hierarchy")
        dumpNodeRec(rootInActiveWindow, serializer, 0)
        serializer.endTag("", "hierarchy")
        serializer.endDocument();
        writer.write(stringWriter.toString());
        writer.close();
        log(broadcastID, "DUMP 200", true)
        log(LOG_TAG, "dumped to " + file.absolutePath)
        val endTime = SystemClock.uptimeMillis();
        log(LOG_TAG, "Fetch time: " + (endTime - startTime) + "ms")
    }

    /**
     * The list of classes to exclude my not be complete. We're attempting to
     * only reduce noise from standard layout classes that may be falsely
     * configured to accept clicks and are also enabled.
     *
     * @param node
     * @return true if node is excluded.
     */
    private fun nafExcludedClass(node: AccessibilityNodeInfo): Boolean {
        val className = safeCharSeqToString(node.className)
        val NAF_EXCLUDED_CLASSES = arrayOf(
            GridView::class.java.name, GridLayout::class.java.name,
            ListView::class.java.name, TableLayout::class.java.name
        )
        for (excludedClassName in NAF_EXCLUDED_CLASSES) {
            if (className!!.endsWith(excludedClassName)) return true
        }
        return false
    }

    /**
     * We're looking for UI controls that are enabled, clickable but have no
     * text nor content-description. Such controls configuration indicate an
     * interactive control is present in the UI and is most likely not
     * accessibility friendly. We refer to such controls here as NAF controls
     * (Not Accessibility Friendly)
     *
     * @param node
     * @return false if a node fails the check, true if all is OK
     */
    private fun nafCheck(node: AccessibilityNodeInfo): Boolean {
        val isNaf = (node.isClickable && node.isEnabled
                && safeCharSeqToString(node.contentDescription)!!.isEmpty()
                && safeCharSeqToString(node.text)!!.isEmpty())
        return if (!isNaf) true else childNafCheck(node)
        // check children since sometimes the containing element is clickable
        // and NAF but a child's text or description is available. Will assume
        // such layout as fine.
    }

    /**
     * This should be used when it's already determined that the node is NAF and
     * a further check of its children is in order. A node maybe a container
     * such as LinerLayout and may be set to be clickable but have no text or
     * content description but it is counting on one of its children to fulfill
     * the requirement for being accessibility friendly by having one or more of
     * its children fill the text or content-description. Such a combination is
     * considered by this dumper as acceptable for accessibility.
     *
     * @param node
     * @return false if node fails the check.
     */
    private fun childNafCheck(node: AccessibilityNodeInfo): Boolean {
        val childCount = node.childCount
        for (x in 0 until childCount) {
            val childNode = node.getChild(x)
            if (childNode == null) {
                log(
                    "dump a11y child naf check", String.format(
                        "Null child %d/%d, parent: %s",
                        x, childCount, node.toString()
                    )
                )
                continue
            }
            if (!safeCharSeqToString(childNode.contentDescription)!!.isEmpty()
                || !safeCharSeqToString(childNode.text)!!.isEmpty()
            ) return true
            if (childNafCheck(childNode)) return true
        }
        return false
    }
    @Throws(IOException::class)
    private fun dumpNodeRec(node: AccessibilityNodeInfo, serializer: XmlSerializer, index: Int) {


        serializer.startTag("", "node")
        serializer.attribute("", "index", Integer.toString(index))
        serializer.attribute("", "resource-id", safeCharSeqToString(node.viewIdResourceName))
        serializer.attribute("", "text", safeCharSeqToString(node.text))
        serializer.attribute("", "class", safeCharSeqToString(node.className))
        serializer.attribute("", "package", safeCharSeqToString(node.packageName))
        serializer.attribute("", "content-desc", safeCharSeqToString(node.contentDescription))
        serializer.attribute("", "checkable", java.lang.Boolean.toString(node.isCheckable))
        serializer.attribute("", "checked", java.lang.Boolean.toString(node.isChecked))
        serializer.attribute("", "clickable", java.lang.Boolean.toString(node.isClickable))
        serializer.attribute("", "enabled", java.lang.Boolean.toString(node.isEnabled))
        serializer.attribute("", "focusable", java.lang.Boolean.toString(node.isFocusable))
        serializer.attribute("", "importantForAccessibility", java.lang.Boolean.toString(node.isImportantForAccessibility))
        serializer.attribute("", "focused", java.lang.Boolean.toString(node.isFocused))
        serializer.attribute("", "scrollable", java.lang.Boolean.toString(node.isScrollable))
        serializer.attribute("", "long-clickable", java.lang.Boolean.toString(node.isLongClickable))
        serializer.attribute("", "password", java.lang.Boolean.toString(node.isPassword))
        serializer.attribute("", "selected", java.lang.Boolean.toString(node.isSelected))
        serializer.attribute("", "visible", java.lang.Boolean.toString(node.isVisibleToUser))
        serializer.attribute("", "invalid", java.lang.Boolean.toString(node.isContentInvalid))
        serializer.attribute("", "drawingOrder", Integer.toString(node.drawingOrder))
        val sb = StringBuilder()
        node.actionList.forEach { sb.append(it.id).append("-")}
        val string = sb.removeSuffix("-").toString()
        serializer.attribute("", "actionList", string)
        if (!nafExcludedClass(node) && !nafCheck(node))
            serializer.attribute("", "NAF", java.lang.Boolean.toString(true))
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        serializer.attribute("", "bounds", bounds.toShortString())
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                dumpNodeRec(child, serializer, i)
                child.recycle()
            } else {
                log(
                    "dumpA11yTree", String.format(
                        "Null child %d/%d, parent: %s",
                        i, count, node.toString()
                    )
                )
            }
        }
        serializer.endTag("", "node")
    }

    private fun safeCharSeqToString(cs: CharSequence?): String? {
        return cs?.let { stripInvalidXMLChars(it) } ?: ""
    }

    private fun stripInvalidXMLChars(cs: CharSequence): String? {
        val ret = StringBuffer()
        var ch: Char
        /* http://www.w3.org/TR/xml11/#charsets
        [#x1-#x8], [#xB-#xC], [#xE-#x1F], [#x7F-#x84], [#x86-#x9F], [#xFDD0-#xFDDF],
        [#x1FFFE-#x1FFFF], [#x2FFFE-#x2FFFF], [#x3FFFE-#x3FFFF],
        [#x4FFFE-#x4FFFF], [#x5FFFE-#x5FFFF], [#x6FFFE-#x6FFFF],
        [#x7FFFE-#x7FFFF], [#x8FFFE-#x8FFFF], [#x9FFFE-#x9FFFF],
        [#xAFFFE-#xAFFFF], [#xBFFFE-#xBFFFF], [#xCFFFE-#xCFFFF],
        [#xDFFFE-#xDFFFF], [#xEFFFE-#xEFFFF], [#xFFFFE-#xFFFFF],
        [#x10FFFE-#x10FFFF].
         */for (i in 0 until cs.length) {
            ch = cs[i]
            if (ch.toInt() >= 0x1 && ch.toInt() <= 0x8 || ch.toInt() >= 0xB && ch.toInt() <= 0xC || ch.toInt() >= 0xE && ch.toInt() <= 0x1F ||
                ch.toInt() >= 0x7F && ch.toInt() <= 0x84 || ch.toInt() >= 0x86 && ch.toInt() <= 0x9f ||
                ch.toInt() >= 0xFDD0 && ch.toInt() <= 0xFDDF || ch.toInt() >= 0x1FFFE && ch.toInt() <= 0x1FFFF ||
                ch.toInt() >= 0x2FFFE && ch.toInt() <= 0x2FFFF || ch.toInt() >= 0x3FFFE && ch.toInt() <= 0x3FFFF ||
                ch.toInt() >= 0x4FFFE && ch.toInt() <= 0x4FFFF || ch.toInt() >= 0x5FFFE && ch.toInt() <= 0x5FFFF ||
                ch.toInt() >= 0x6FFFE && ch.toInt() <= 0x6FFFF || ch.toInt() >= 0x7FFFE && ch.toInt() <= 0x7FFFF ||
                ch.toInt() >= 0x8FFFE && ch.toInt() <= 0x8FFFF || ch.toInt() >= 0x9FFFE && ch.toInt() <= 0x9FFFF ||
                ch.toInt() >= 0xAFFFE && ch.toInt() <= 0xAFFFF || ch.toInt() >= 0xBFFFE && ch.toInt() <= 0xBFFFF ||
                ch.toInt() >= 0xCFFFE && ch.toInt() <= 0xCFFFF || ch.toInt() >= 0xDFFFE && ch.toInt() <= 0xDFFFF ||
                ch.toInt() >= 0xEFFFE && ch.toInt() <= 0xEFFFF || ch.toInt() >= 0xFFFFE && ch.toInt() <= 0xFFFFF ||
                ch.toInt() >= 0x10FFFE && ch.toInt() <= 0x10FFFF
            ) ret.append(".") else ret.append(ch)
        }
        return ret.toString()
    }

    private fun createVerticalSwipePath(downToUp: Boolean): Path = Path().apply {
        if (downToUp) {
            moveTo(halfWidth - quarterWidth, halfHeight - quarterHeight)
            lineTo(halfWidth - quarterWidth, halfHeight + quarterHeight)
        } else {
            moveTo(halfWidth - quarterWidth, halfHeight + quarterHeight)
            lineTo(halfWidth - quarterWidth, halfHeight - quarterHeight)
        }
    }

    private fun createHorizontalSwipePath(rightToLeft: Boolean): Path = Path().apply {
        if (rightToLeft) {
            moveTo(halfWidth + quarterWidth, halfHeight)
            lineTo(halfWidth - quarterWidth, halfHeight)
        } else {
            moveTo(halfWidth - quarterWidth, halfHeight)
            lineTo(halfWidth + quarterWidth, halfHeight)
        }
    }

    fun swipeHorizontal(rightToLeft: Boolean, broadcastId: String) =
        performGesture(
            GestureAction(createHorizontalSwipePath(rightToLeft)),
            broadcastId = broadcastId
        )

    fun swipeVertical(downToUp: Boolean = true, broadcastId: String) =
        performGesture(GestureAction(createVerticalSwipePath(downToUp)), broadcastId = broadcastId)

    fun swipeUpThenDown(broadcastId: String) =
        performGesture(
            GestureAction(createVerticalSwipePath(true)),
            GestureAction(createVerticalSwipePath(false), 500), broadcastId = broadcastId
        )


    fun threeFingerSwipeUp(broadcastId: String) {
        val stX = halfWidth - quarterWidth
        val stY = halfHeight + quarterHeight
        val enY = halfHeight - quarterHeight
        val eighth = quarterWidth / 2f

        val one = Path().apply {
            moveTo(stX - eighth, stY)
            lineTo(stX - eighth, enY)
        }
        val two = Path().apply {
            moveTo(stX, stY)
            lineTo(stX, enY)
        }
        val three = Path().apply {
            moveTo(stX + eighth, stY)
            lineTo(stX + eighth, enY)
        }

        performGesture(
            GestureAction(one),
            GestureAction(two),
            GestureAction(three),
            broadcastId = broadcastId
        )
    }

    //https://developer.android.com/guide/topics/ui/accessibility/service#continued-gestures
    fun swipeUpRight(broadcastId: String) {
        val stX = halfWidth - quarterWidth
        val enX = halfWidth + quarterWidth

        val stY = halfHeight + quarterHeight
        val enY = halfHeight - quarterHeight

        val swipeUpRight = Path().apply {
            moveTo(stX, stY)
            lineTo(stX, enY)
            lineTo(enX, enY)
        }

        log("path_v", "to x: [$stX], y: [$stY]")
        log("path_v", "mv x: [$stX], y: [$enY]")
        log("path_v", "dl x: [${stX - stX}], y: [${enY - stY}]")

        log("path_h", "dl x: [${enX - stX}], y: [${enY - enY}]")

        performGesture(
            GestureAction(swipeUpRight),
            broadcastId = broadcastId
        )
    }

    fun swipeUpLeft(broadcastId: String) {
        val enX = halfWidth - quarterWidth
        val stX = halfWidth + quarterWidth

        val stY = halfHeight + quarterHeight
        val enY = halfHeight - quarterHeight

        val swipeUpLeft = Path().apply {
            moveTo(stX, stY)
            lineTo(stX, enY)
            lineTo(enX, enY)
        }

        performGesture(
            GestureAction(swipeUpLeft),
            broadcastId = broadcastId
        )
    }


    // https://developer.android.com/guide/topics/ui/accessibility/service#continued-gestures
    // Taken from online documentation. Seems to have left out the dispatch of the gesture.
    // Also does not seem to be an accessibility gesture, but a "regular" gesture (I'm not sure)
    // Simulates an L-shaped drag path: 200 pixels right, then 200 pixels down.
    private fun doRightThenDownDrag() {
        val dragRightPath = Path().apply {
            moveTo(200f, 200f)
            lineTo(400f, 200f)
        }
        val dragRightDuration = 500L // 0.5 second

        // The starting point of the second path must match
        // the ending point of the first path.
        val dragDownPath = Path().apply {
            moveTo(400f, 200f)
            lineTo(400f, 400f)
        }
        val dragDownDuration = 500L
        val rightThenDownDrag = StrokeDescription(
            dragRightPath,
            0L,
            dragRightDuration,
            true
        ).apply {
            continueStroke(dragDownPath, dragRightDuration, dragDownDuration, false)
        }
    }

    private fun performGesture(vararg gestureActions: GestureAction, broadcastId: String) =
        dispatchGesture(
            createGestureFrom(*gestureActions),
            GestureResultCallback(baseContext, broadcastId, this.findFocus(FOCUS_ACCESSIBILITY)),
            null
        )


    class GestureResultCallback(
        private val ctx: Context,
        broadcastId: String,
        preFocus: AccessibilityNodeInfo
    ) :
        AccessibilityService.GestureResultCallback() {

        private var gBroadcastId = broadcastId
        private var gPreFocus = preFocus
        override fun onCompleted(gestureDescription: GestureDescription?) {
            //  swiped and focus changed return code=200; swiped but focus remain unchanged return code=204
            sleep(100) // WEIRDLY sometimes the focused node is not updated in this method

            var code: Int
            var node = instance?.findFocus(FOCUS_ACCESSIBILITY)

            code = if (node == null)
                206
            else if(instance == null){
                208
            }
            else {
                var focus_bounds = Rect()
                node.getBoundsInScreen(focus_bounds)
                var pre_bounds = Rect()
                gPreFocus.getBoundsInScreen(pre_bounds)
                if (focus_bounds.equals(pre_bounds))
                    204
                else
                    200
            }
            log(gBroadcastId, "SWIPE $code", true)
            super.onCompleted(gestureDescription)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            log("GestureResultCallback", "SWIPE 400")
            super.onCancelled(gestureDescription)
        }
    }

    // default to lower in case you forget
    // because everyone LOVES accessibility over VC and in the [home] office
    fun adjustVolume(raise: Boolean = false) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_ACCESSIBILITY,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    val log_tag = "AccessibilityDeveloperService"

    fun setVolume(percent: Int) {
        require(percent <= 100) { " percent must be an integer less than 100" }
        require(percent >= 0) { " percent must be an integer greater than 0" }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY)
        val volume = (max * (percent.toFloat() / 100f)).toInt()
        log(log_tag, "  ~~> Volume set to value [$volume]")
        audioManager.setStreamVolume(
            AudioManager.STREAM_ACCESSIBILITY,
            volume,
            AudioManager.FLAG_SHOW_UI
        )
    }

    override fun onDestroy() {
        log(
            log_tag,
            "  ~~> onDestroy"
        )
        // Unregister accessibilityActionReceiver when destroyed.
        // I have had bad luck with broadcast receivers in the past
        try {
            unregisterReceiver(accessibilityActionReceiver)
            accessibilityButtonController.unregisterAccessibilityButtonCallback(
                accessibilityButtonCallback
            )
            log(
                log_tag,
                "    ~~> Receiver is unregistered.\r\n    ~~> AccessibilityButtonCallback is unregistered."
            )
        } catch (e: Exception) {
            log(
                log_tag,
                "    ~~> Unregister exception: [$e]"
            )
        } finally {
            instance = null
            super.onDestroy()
        }
    }
}