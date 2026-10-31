package cz.netkit.app.ssh

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.*

class TerminalInputPrivacyTest {
    @Test fun inputCannotAskForAutocorrectSuggestionsOrLearning() {
        assertTrue(TerminalInputPrivacy.INPUT_TYPE and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(0, TerminalInputPrivacy.INPUT_TYPE and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, TerminalInputPrivacy.INPUT_TYPE and InputType.TYPE_MASK_VARIATION)
        assertTrue(TerminalInputPrivacy.IME_OPTIONS and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertTrue(TerminalInputPrivacy.IME_OPTIONS and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
    }
}
