package cz.netkit.app.ssh

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import cz.netkit.shared.ssh.TerminalComposition
import cz.netkit.shared.ssh.TerminalEdit
import cz.netkit.shared.ssh.terminalHardwareText
import cz.netkit.shared.ssh.TerminalKey
import cz.netkit.shared.ssh.pasteNeedsConfirmation

/** No local echo or editor history. Only committed text/explicit keys go to the SSH transport. */
class DirectTerminalInput(context: Context) : TextView(context) {
    var sendText: (String, Boolean, Boolean) -> Unit = { _, _, _ -> }
    var sendKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _ -> }
    var confirmPaste: (String) -> Unit = {}
    private val composing = TerminalComposition { event ->
        when (event) {
            is TerminalEdit.Text -> committed(event.value)
            is TerminalEdit.Key -> if (isEnabled) sendKey(event.value, false, false, false)
        }
    }
    fun flushComposing() { if (isEnabled) composing.finish() else composing.clear() }
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(16, 0, 16, 0)
    }
    override fun onCheckIsTextEditor() = true
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.action == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        if (!isEnabled) return false
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        return true
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) flushComposing()
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isEditable = true
        info.isPassword = true
        // Displayed label is descriptive, never the terminal's input or remote output.
    }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = TerminalInputPrivacy.INPUT_TYPE
        outAttrs.imeOptions = (TerminalInputPrivacy.IME_OPTIONS and EditorInfo.IME_MASK_ACTION.inv()) or EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0; outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, false) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence = ""
            override fun getCursorCapsMode(reqModes: Int) = 0
            override fun setSelection(start: Int, end: Int) = true
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing.set(text?.toString().orEmpty()); return true
            }
            override fun finishComposingText(): Boolean { flushComposing(); return true }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!isEnabled) { composing.clear(); return false }
                composing.commit(text?.toString().orEmpty())
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
                composing.delete(beforeLength, afterLength, codePoints = false)
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
                composing.delete(beforeLength, afterLength, codePoints = true)
            override fun sendKeyEvent(event: KeyEvent): Boolean = if (event.action == KeyEvent.ACTION_DOWN) dispatchTerminalKey(event) else true
            override fun performEditorAction(actionCode: Int): Boolean {
                if (!isEnabled) return false
                composing.beforeAction { sendKey(TerminalKey.ENTER, false, false, false) }; return true
            }
            override fun closeConnection() { composing.clear(); super.closeConnection() }
        }
    }
    private fun committed(value: String) {
        if (!isEnabled || value.isEmpty()) return
        when {
            pasteNeedsConfirmation(value) -> confirmPaste(value)
            else -> sendText(value, false, false)
        }
    }
    private fun dispatchTerminalKey(event: KeyEvent): Boolean {
        if (!isEnabled) return false
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.ENTER
            KeyEvent.KEYCODE_DEL -> TerminalKey.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.DELETE
            KeyEvent.KEYCODE_INSERT -> TerminalKey.INSERT
            KeyEvent.KEYCODE_ESCAPE -> TerminalKey.ESCAPE
            KeyEvent.KEYCODE_TAB -> TerminalKey.TAB
            KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.RIGHT
            KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.HOME
            KeyEvent.KEYCODE_MOVE_END -> TerminalKey.END
            KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PAGE_DOWN
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> TerminalKey.entries[TerminalKey.F1.ordinal + event.keyCode - KeyEvent.KEYCODE_F1]
            else -> null
        }
        if (key != null) {
            if (key == TerminalKey.BACKSPACE && composing.text.isNotEmpty()) return composing.delete(1, 0, codePoints = true)
            composing.beforeAction { sendKey(key, event.isCtrlPressed, event.metaState and KeyEvent.META_ALT_LEFT_ON != 0, event.isShiftPressed) }
            return true
        }
        val mapped = terminalHardwareText(event.isCtrlPressed,
            event.metaState and KeyEvent.META_ALT_LEFT_ON != 0,
            event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0) { preserveLayout ->
            val meta = if (preserveLayout) event.metaState else event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
            val character = event.getUnicodeChar(meta)
            if (character in 32..0x10ffff && character !in 127..159 && character !in 0xd800..0xdfff)
                String(Character.toChars(character)) else ""
        }
        if (mapped.text.isNotEmpty()) {
            composing.beforeAction { sendText(mapped.text, mapped.control, mapped.alt) }; return true
        }
        return false
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = dispatchTerminalKey(event) || super.onKeyDown(keyCode, event)
}
