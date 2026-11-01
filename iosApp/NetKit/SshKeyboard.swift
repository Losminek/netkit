import SwiftUI
import UIKit
import NetKitShared

/// Commits marked text before sanitized paste so terminal input keeps its order.
enum SshTerminalInput {
    static func preparePaste(terminal: IosTerminal, value: String) -> (String, String)? {
        terminal.composeFinish()
        guard let pending = terminal.drainInput() else { return nil }
        return (pending, terminal.safePaste(text: value))
    }
}

/// Keeps marked text local until IME composition is committed.
struct SshKeyboard: UIViewRepresentable {
    let inputEpoch: UUID
    let onComposition: (String, Bool) -> Void
    let onKey: (Bool) -> Void // true = enter, false = backspace
    let onPaste: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIView(context: Context) -> TerminalInputView {
        let view = TerminalInputView()
        view.delegate = context.coordinator
        view.onEmptyBackspace = { onKey(false) }
        view.onPaste = onPaste
        view.autocorrectionType = .no
        view.spellCheckingType = .no
        view.smartQuotesType = .no
        view.smartDashesType = .no
        view.autocapitalizationType = .none
        view.font = .monospacedSystemFont(ofSize: 16, weight: .regular)
        view.accessibilityIdentifier = "sshKeyboard"
        return view
    }
    func updateUIView(_ view: TerminalInputView, context: Context) {
        if context.coordinator.inputEpoch != inputEpoch {
            context.coordinator.suppressChanges = true
            view.unmarkText(); view.text = ""
            context.coordinator.suppressChanges = false
            context.coordinator.inputEpoch = inputEpoch
        }
        context.coordinator.parent = self
        view.onEmptyBackspace = { onKey(false) }
        view.onPaste = onPaste
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: SshKeyboard
        var inputEpoch: UUID
        var suppressChanges = false
        init(parent: SshKeyboard) { self.parent = parent; self.inputEpoch = parent.inputEpoch }
        func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
            if text == "\n", textView.markedTextRange == nil { parent.onKey(true); return false }
            return true
        }
        func textViewDidChange(_ textView: UITextView) {
            guard !suppressChanges else { return }
            let text = textView.text ?? ""
            if textView.markedTextRange != nil { parent.onComposition(text, true) }
            else {
                parent.onComposition(text, false)
                suppressChanges = true
                textView.text = ""
                suppressChanges = false
            }
        }
    }
}

final class TerminalInputView: UITextView {
    var onEmptyBackspace: (() -> Void)?
    var onPaste: ((String) -> Void)?
    override func deleteBackward() {
        if text.isEmpty && markedTextRange == nil { onEmptyBackspace?() }
        else { super.deleteBackward() }
    }
    override func paste(_ sender: Any?) {
        if let text = UIPasteboard.general.string { onPaste?(text) }
    }
}
