@testable import NetKit
import NetKitShared
import XCTest
import UIKit

final class SshTerminalTests: XCTestCase {
    func testStyledOutputCursorAndInputSequences() {
        let terminal = IosTerminal(columns: 80, rows: 24)
        _ = terminal.feed(text: "\u{1b}[31;1mA\u{1b}[0mB")
        let snapshot = terminal.snapshot()
        let runs = snapshot.screen[0].runs
        XCTAssertEqual(runs.first?.text, "A")
        XCTAssertEqual(runs.first?.foreground, 1)
        XCTAssertEqual(runs.first?.bold, true)
        XCTAssertEqual(snapshot.cursorRow, 0)
        XCTAssertEqual(snapshot.cursorColumn, 2)
        XCTAssertTrue(snapshot.cursorVisible)
        XCTAssertEqual(terminal.keySequence(key: .up, control: false, alt: false, shift: false), "\u{1b}[A")
        XCTAssertEqual(terminal.textSequence(text: "c", control: true, alt: false), "\u{03}")
        XCTAssertEqual(terminal.safePaste(text: "one\ntwo"), "one two")
        XCTAssertTrue(terminal.pasteNeedsConfirmation(text: "one\ntwo"))
    }
    func testEmptyUnmarkedChangeClearsPendingComposition() {
        let terminal = IosTerminal(columns: 80, rows: 24)
        terminal.composeSet(value: "abc")
        let keyboard = SshKeyboard(inputEpoch: UUID(), onComposition: { text, marked in
            if marked { terminal.composeSet(value: text) } else { terminal.composeCommit(value: text) }
        }, onKey: { _ in }, onPaste: { _ in })
        let coordinator = keyboard.makeCoordinator()
        let input = UITextView()
        input.text = ""
        coordinator.textViewDidChange(input)
        terminal.composeFinish()
        XCTAssertEqual(terminal.drainInput(), "")
    }

    func testPasteFlushesCompositionBeforeSanitizedInput() throws {
        let terminal = IosTerminal(columns: 80, rows: 24)
        terminal.composeSet(value: "a")
        let (pending, paste) = try XCTUnwrap(SshTerminalInput.preparePaste(terminal: terminal, value: "b\nc"))
        XCTAssertEqual(pending, "a")
        XCTAssertEqual(paste, "b c")
        terminal.composeFinish()
        XCTAssertEqual(terminal.drainInput(), "")
    }

}
