package cz.netkit.app.ssh

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText

/** Shared by fallback and direct terminal input; never enable autocorrect or editor learning. */
object TerminalInputPrivacy {
    const val INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    const val IME_OPTIONS = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_SEND
}

/** Fallback input deliberately masks staged text, including sudo/enable credentials. */
class PrivateTerminalEditText(context: Context) : EditText(context) {
    init {
        setSingleLine(true)
        inputType = TerminalInputPrivacy.INPUT_TYPE
        imeOptions = TerminalInputPrivacy.IME_OPTIONS
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val result = super.onCreateInputConnection(outAttrs)
        outAttrs.inputType = TerminalInputPrivacy.INPUT_TYPE
        outAttrs.imeOptions = TerminalInputPrivacy.IME_OPTIONS
        return result
    }
}
