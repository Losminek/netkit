import XCTest
import Foundation

final class NetKitUITests: XCTestCase {
    func testEnglishLaunch() throws {
        try assertLaunch(language: "en", locale: "en_US", overview: "Overview", about: "About", versionLabel: "Version", privacy: "Privacy policy", licenses: "Open source licenses", content: "License texts")
    }

    func testCzechLaunch() throws {
        try assertLaunch(language: "cs", locale: "cs_CZ", overview: "Přehled", about: "O aplikaci", versionLabel: "Verze", privacy: "Zásady ochrany soukromí", licenses: "Open source licence", content: "Texty licencí")
    }

    func testToolNavigation() {
        assertToolNavigation(language: "en", locale: "en_US", tabs: ["Overview", "Devices", "SSH", "WoL"],
                             tcp: "Probe TCP ports", devicesEmpty: "No results yet.", wifiCheck: "Check current Wi-Fi",
                             wifiEmpty: "Current Wi-Fi has not been checked yet.",
                             wifiUnavailable: "Current Wi-Fi details are unavailable. The system requires the appropriate entitlement and access conditions.",
                             speed: "Connection speed", speedStart: "Start measurement", wolSave: "Save profile", wolEmpty: "No devices yet.")
    }

    func testCzechToolNavigation() {
        assertToolNavigation(language: "cs", locale: "cs_CZ", tabs: ["Přehled", "Zařízení", "SSH", "WoL"],
                             tcp: "Prověřit TCP porty", devicesEmpty: "Zatím žádné výsledky.", wifiCheck: "Zjistit aktuální Wi-Fi",
                             wifiEmpty: "Aktuální Wi-Fi zatím nebyla zjištěna.",
                             wifiUnavailable: "Údaje o aktuální Wi-Fi nejsou dostupné. Systém vyžaduje příslušné oprávnění a splnění podmínek přístupu.",
                             speed: "Rychlost připojení", speedStart: "Spustit měření", wolSave: "Uložit profil", wolEmpty: "Zatím žádná zařízení.")
    }

    func testSshEmptyState() {
        assertSshEmptyState(language: "en", locale: "en_US", addProfile: "Add profile", empty: "No SSH profiles yet.")
    }

    func testCzechSshEmptyState() {
        assertSshEmptyState(language: "cs", locale: "cs_CZ", addProfile: "Přidat profil", empty: "Zatím žádné SSH profily.")
    }

    func testEnglishLanConsentAndWolWarning() {
        assertLanConsentAndWolWarning(language: "en", locale: "en_US", devices: "Devices",
            host: "Target or name", ports: "Ports", tcp: "Probe TCP ports", cancel: "Cancel",
            reason: "The range includes an address outside private, shared or link-local blocks.",
            warning: "Use only on networks you own or are authorized to use. NetKit for iOS supports only unicast on the current Wi-Fi network. If a sleeping device is absent from the ARP cache, the packet may not arrive even when sending is reported as successful.")
    }

    func testCzechLanConsentAndWolWarning() {
        assertLanConsentAndWolWarning(language: "cs", locale: "cs_CZ", devices: "Zařízení",
            host: "Cíl nebo název", ports: "Porty", tcp: "Prověřit TCP porty", cancel: "Zrušit",
            reason: "Rozsah zahrnuje adresu mimo privátní, sdílené nebo link-local bloky.",
            warning: "Používejte jen ve vlastní nebo povolené síti. iOS verze NetKitu podporuje pouze unicast v aktuální Wi-Fi síti. Pokud spící zařízení vypadne z ARP cache, paket nemusí dorazit ani po hlášení o odeslání.")
    }

    private func assertLanConsentAndWolWarning(language: String, locale: String, devices: String,
                                              host: String, ports: String, tcp: String, cancel: String,
                                              reason: String, warning: String) {
        let app = launch(language: language, locale: locale)
        let tabs = app.tabBars.firstMatch.buttons
        XCTAssertTrue(tabs[devices].waitForExistence(timeout: 10))
        tabs[devices].tap()
        let hostField = app.textFields[host]
        XCTAssertTrue(hostField.waitForExistence(timeout: 10))
        hostField.tap()
        hostField.typeText("192.0.2.1")
        let portField = app.textFields[ports]
        portField.tap()
        portField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 2) + "22")
        app.buttons[tcp].tap()
        let reasonText = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", reason)).firstMatch
        XCTAssertTrue(reasonText.waitForExistence(timeout: 10))
        XCTAssertTrue(reasonText.isHittable)
        app.buttons[cancel].tap()
        let navigationReady = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.keyboards.count == 0 && app.tabBars.firstMatch.isHittable && tabs["WoL"].isHittable
        }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [navigationReady], timeout: 10), .completed)
        XCTAssertEqual(app.keyboards.count, 0)
        XCTAssertTrue(app.tabBars.firstMatch.isHittable)
        XCTAssertTrue(tabs["WoL"].isHittable)
        tabs["WoL"].tap()
        let warningText = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", warning)).firstMatch
        XCTAssertTrue(warningText.waitForExistence(timeout: 10))
        XCTAssertTrue(warningText.isHittable)
        XCTAssertFalse(app.switches["Broadcast"].exists)
    }

    private func assertToolNavigation(language: String, locale: String, tabs: [String], tcp: String,
                                      devicesEmpty: String, wifiCheck: String, wifiEmpty: String, wifiUnavailable: String,
                                      speed: String, speedStart: String, wolSave: String, wolEmpty: String) {
        let app = launch(language: language, locale: locale)
        let buttons = app.tabBars.firstMatch.buttons
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        XCTAssertEqual(buttons.count, 4)
        for (index, label) in tabs.enumerated() { XCTAssertEqual(buttons.element(boundBy: index).label, label) }
        XCTAssertTrue(app.staticTexts[wifiEmpty].waitForExistence(timeout: 10))
        let check = app.buttons[wifiCheck]
        XCTAssertTrue(check.waitForExistence(timeout: 10))
        check.tap()
        XCTAssertTrue(app.staticTexts[wifiUnavailable].waitForExistence(timeout: 10))
        app.buttons[speed].tap()
        XCTAssertTrue(app.buttons[speedStart].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        buttons[tabs[1]].tap()
        XCTAssertTrue(app.buttons[tcp].waitForExistence(timeout: 10))
        app.swipeUp()
        XCTAssertTrue(app.staticTexts[devicesEmpty].waitForExistence(timeout: 10))
        buttons[tabs[3]].tap()
        XCTAssertTrue(app.buttons[wolSave].waitForExistence(timeout: 10))
        app.swipeUp()
        XCTAssertTrue(app.staticTexts[wolEmpty].waitForExistence(timeout: 10))
    }

    private func assertSshEmptyState(language: String, locale: String, addProfile: String, empty: String) {
        let app = launch(language: language, locale: locale)
        app.tabBars.buttons["SSH"].tap()
        XCTAssertTrue(app.buttons[addProfile].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts[empty].exists)
    }

    private func launch(language: String, locale: String, scenario: String? = nil, id: String = UUID().uuidString, reset: Bool = true, captureSshExport: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(\(language))", "-AppleLocale", locale]
        if let scenario {
            if captureSshExport { app.launchArguments.append("-netkit-ui-capture-ssh-export") }
            app.launchEnvironment["NETKIT_UI_SSH_ID"] = id
            app.launchEnvironment["NETKIT_UI_SSH_SCENARIO"] = scenario
            app.launchEnvironment["NETKIT_UI_SSH_RESET"] = reset ? "1" : "0"
        }
        app.launch()
        return app
    }

    private func assertLaunch(language: String, locale: String, overview: String, about: String, versionLabel: String, privacy: String, licenses: String, content: String) throws {
        let expectedVersion = try XCTUnwrap(Bundle(for: NetKitUITests.self).infoDictionary?["CFBundleShortVersionString"] as? String)
        XCTAssertFalse(expectedVersion.isEmpty)
        let app = launch(language: language, locale: locale)
        XCTAssertTrue(app.tabBars.buttons[overview].waitForExistence(timeout: 10))
        app.buttons[about].tap()
        XCTAssertTrue(app.staticTexts[versionLabel].waitForExistence(timeout: 10))
        let version = app.staticTexts["appVersionValue"]
        XCTAssertTrue(version.waitForExistence(timeout: 10))
        XCTAssertEqual(version.label, expectedVersion)
        XCTAssertTrue(app.links[privacy].exists || app.buttons[privacy].exists)
        app.buttons[licenses].tap()
        XCTAssertTrue(app.staticTexts[content].waitForExistence(timeout: 10))
    }
}


extension NetKitUITests {
    func testEnglishSshOrderAndExport() throws { try assertSshOrderAndExport(language: "en", locale: "en_US", up: "Move profile Alpha up", down: "Move profile Alpha down") }
    func testCzechSshOrderAndExport() throws { try assertSshOrderAndExport(language: "cs", locale: "cs_CZ", up: "Posunout profil Alpha nahoru", down: "Posunout profil Alpha dolů") }
    func testEnglishSshImportPreview() { assertSshImportPreview(language: "en", locale: "en_US", key: "Key", password: "Password", empty: "Empty") }
    func testCzechSshImportPreview() { assertSshImportPreview(language: "cs", locale: "cs_CZ", key: "Klíč", password: "Heslo", empty: "Prázdné") }
    func testEnglishSshMissingKeyEditor() { assertSshMissingKeyEditor(language: "en", locale: "en_US", edit: "Edit", title: "Select a key") }
    func testCzechSshMissingKeyEditor() { assertSshMissingKeyEditor(language: "cs", locale: "cs_CZ", edit: "Upravit", title: "Vyberte klíč") }
    func testEnglishSshInteractiveProfile() { assertSshInteractiveProfile(language: "en", locale: "en_US", password: "Password", key: "Key", message: "The iOS version does not support keyboard-interactive. Edit the profile to use a password or key.") }
    func testCzechSshInteractiveProfile() { assertSshInteractiveProfile(language: "cs", locale: "cs_CZ", password: "Heslo", key: "Klíč", message: "iOS verze nepodporuje keyboard-interactive, upravte profil na heslo nebo klíč.") }
    func testEnglishSshExportValidation() { assertSshExportValidation(language: "en", locale: "en_US", duplicate: "Profiles have duplicate names.", large: "The export exceeds 256 KiB.") }
    func testCzechSshExportValidation() { assertSshExportValidation(language: "cs", locale: "cs_CZ", duplicate: "Profily mají duplicitní názvy.", large: "Export překračuje limit 256 KiB.") }

    func testEnglishSshEditorAndKeyErrors() {
        assertSshEditorAndKeyErrors(language: "en", locale: "en_US", password: "Password", key: "Key", invalid: "Check the entered values or file.")
    }
    func testCzechSshEditorAndKeyErrors() {
        assertSshEditorAndKeyErrors(language: "cs", locale: "cs_CZ", password: "Heslo", key: "Klíč", invalid: "Zkontrolujte zadané údaje nebo soubor.")
    }

    private func assertSshEditorAndKeyErrors(language: String, locale: String, password: String, key: String, invalid: String) {
        let app = sshApp(language: language, locale: locale, scenario: "interactive")
        reveal(app.buttons["ssh.connect.alpha"], in: app)
        app.buttons["ssh.connect.alpha"].tap() // Leave the unsupported-auth error pending when opening the editor.
        XCTAssertTrue(app.staticTexts["ssh.error"].waitForExistence(timeout: 10))
        reveal(app.buttons["ssh.edit.alpha"], in: app)
        app.buttons["ssh.edit.alpha"].tap()
        XCTAssertTrue(app.buttons["ssh.editor.save"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["ssh.editor.error"].exists)
        let form = app.collectionViews["ssh.editor.form"]
        let authentication = app.buttons["ssh.editor.authentication"]
        reveal(authentication, in: app, scrollView: form)
        authentication.tap()
        app.buttons[password].tap()
        let port = app.textFields["ssh.editor.port"]
        reveal(port, in: app, upwards: false, scrollView: form)
        port.tap(); port.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 2) + "0")
        app.buttons["ssh.editor.keyboard.done"].tap()
        reveal(app.buttons["ssh.editor.save"], in: app, scrollView: form)
        app.buttons["ssh.editor.save"].tap()
        let editorError = app.staticTexts["ssh.editor.error"]
        reveal(editorError, in: app, scrollView: form)
        XCTAssertEqual(editorError.label, invalid)
        reveal(authentication, in: app, upwards: false, scrollView: form)
        authentication.tap()
        app.buttons[key].tap()
        let create = app.buttons["ssh.editor.create.key"]
        reveal(create, in: app, scrollView: form)
        create.tap() // The prior invalid-input error must not appear as a stale key-creation failure.
        let name = app.textFields["ssh.creator.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["ssh.creator.error"].exists)
        name.tap(); name.typeText(String(repeating: "x", count: 101))
        app.buttons["ssh.creator.generate"].tap()
        let keyError = app.staticTexts["ssh.creator.error"]
        XCTAssertTrue(keyError.waitForExistence(timeout: 10))
        XCTAssertEqual(keyError.label, invalid)
        app.buttons["ssh.creator.cancel"].tap()
        XCTAssertTrue(create.waitForExistence(timeout: 10))
        reveal(create, in: app, scrollView: form)
        create.tap()
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        XCTAssertFalse(keyError.exists, "Opening key creation must clear the previous failure")
        app.buttons["ssh.creator.cancel"].tap()
    }

    private func sshApp(language: String, locale: String, scenario: String, id: String = UUID().uuidString, reset: Bool = true, captureSshExport: Bool = false) -> XCUIApplication {
        let app = launch(language: language, locale: locale, scenario: scenario, id: id, reset: reset, captureSshExport: captureSshExport)
        XCTAssertTrue(app.tabBars.buttons["SSH"].waitForExistence(timeout: 10))
        app.tabBars.buttons["SSH"].tap()
        XCTAssertTrue(app.buttons["ssh.export"].waitForExistence(timeout: 10))
        return app
    }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication, upwards: Bool = true, scrollView: XCUIElement? = nil, file: StaticString = #filePath, line: UInt = #line) {
        for _ in 0..<8 {
            if element.exists && element.isHittable { return }
            let surface = scrollView ?? app
            if upwards { surface.swipeUp() } else { surface.swipeDown() }
        }
        XCTAssertTrue(element.exists && element.isHittable, "Element not visible: \(element)", file: file, line: line)
    }

    private func assertSshOrderAndExport(language: String, locale: String, up: String, down: String) throws {
        let id = UUID().uuidString
        var app = sshApp(language: language, locale: locale, scenario: "profiles", id: id)
        let upAlpha = app.buttons["ssh.move.up.alpha"]
        let downAlpha = app.buttons["ssh.move.down.alpha"]
        reveal(downAlpha, in: app)
        XCTAssertEqual(upAlpha.label, up)
        XCTAssertEqual(downAlpha.label, down)
        XCTAssertFalse(upAlpha.isEnabled)
        XCTAssertTrue(downAlpha.isEnabled)
        downAlpha.tap()
        XCTAssertTrue(upAlpha.isEnabled)
        XCTAssertFalse(downAlpha.isEnabled)
        app.terminate()
        app = sshApp(language: language, locale: locale, scenario: "profiles", id: id, reset: false, captureSshExport: true)
        reveal(app.buttons["ssh.move.up.alpha"], in: app)
        XCTAssertTrue(app.buttons["ssh.move.up.alpha"].isEnabled)
        XCTAssertFalse(app.buttons["ssh.move.down.alpha"].isEnabled)
        reveal(app.buttons["ssh.export"], in: app, upwards: false)
        app.buttons["ssh.export"].tap()
        let captured = app.staticTexts["ssh.ui.export.capture"]
        XCTAssertTrue(captured.waitForExistence(timeout: 10), "Export must capture the actual prepared JSON")
        let json = try XCTUnwrap(captured.value as? String)
        let bytes = try XCTUnwrap(json.data(using: .utf8))
        XCTAssertLessThanOrEqual(bytes.count, 256 * 1024)
        let document = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        XCTAssertEqual(document["format"] as? String, "netkit-ssh-profiles")
        XCTAssertEqual(document["schema"] as? Int, 2)
        XCTAssertEqual(Set(document.keys), Set(["format", "schema", "source", "profiles", "skipped"]))
        XCTAssertEqual((document["skipped"] as? [Any])?.count, 0)
        let profiles = try XCTUnwrap(document["profiles"] as? [[String: Any]])
        XCTAssertEqual(profiles.count, 2)
        XCTAssertEqual(profiles.compactMap { $0["alias"] as? String }, ["Beta", "Alpha"], "Export must retain the moved order after restart")
        let portableFields = Set(["alias", "host", "port", "username", "authentication", "startupCommand", "keepaliveSeconds"])
        for profile in profiles {
            XCTAssertEqual(Set(profile.keys), portableFields, "Only portable profile fields may leave the app")
            for field in ["id", "secretRef", "keyRef", "keys", "hostKeys", "rejectedHostKeys"] { XCTAssertNil(profile[field], field) }
            XCTAssertEqual(profile["host"] as? String, "192.0.2.1")
            XCTAssertEqual(profile["authentication"] as? String, "KEY")
        }
        XCTAssertTrue(app.buttons["ssh.export"].isHittable)
        XCTAssertFalse(app.staticTexts["ssh.export.error"].exists)
    }

    private func assertSshImportPreview(language: String, locale: String, key: String, password: String, empty: String) {
        let app = sshApp(language: language, locale: locale, scenario: "preview")
        app.buttons["ssh.import"].tap()
        XCTAssertTrue(app.buttons["ssh.import.apply"].waitForExistence(timeout: 10))
        let expected = ["HOST": "192.0.2.1 → 192.0.2.2", "PORT": "22 → 2222", "USERNAME": "tester → operator",
                        "AUTHENTICATION": password + " → " + key, "STARTUP_COMMAND": empty + " → show version", "KEEPALIVE_SECONDS": "30 → 60"]
        for field in ["HOST", "PORT", "USERNAME", "AUTHENTICATION", "STARTUP_COMMAND", "KEEPALIVE_SECONDS"] {
            let row = app.descendants(matching: .any).matching(identifier: "ssh.import.change." + field).firstMatch
            reveal(row, in: app)
            // LabeledContent exposes its title and value either together or as child text elements.
            let values = row.staticTexts.allElementsBoundByIndex.map(\.label) + [row.label]
            XCTAssertTrue(values.contains { $0.contains(expected[field]!) }, "Missing before/after for " + field)
        }
        let startup = app.staticTexts.matching(identifier: "ssh.import.startup.warning").firstMatch
        reveal(startup, in: app)
        let missing = app.staticTexts.matching(identifier: "ssh.import.key.required").firstMatch
        reveal(missing, in: app)
        app.buttons["ssh.import.apply"].tap()
        let alpha = app.buttons["ssh.connect.alpha"]
        reveal(alpha, in: app)
        XCTAssertTrue(alpha.label.contains("192.0.2.2"))
        XCTAssertFalse(app.buttons["ssh.import.apply"].exists)
        app.terminate()
        let v1 = sshApp(language: language, locale: locale, scenario: "preview-v1")
        v1.buttons["ssh.import"].tap()
        XCTAssertTrue(v1.buttons["ssh.import.apply"].waitForExistence(timeout: 10))
        let authentication = v1.descendants(matching: .any).matching(identifier: "ssh.import.authentication.Beta").firstMatch
        reveal(authentication, in: v1)
        XCTAssertTrue((authentication.staticTexts.allElementsBoundByIndex.map(\.label) + [authentication.label]).contains { $0.contains(password) })
        reveal(v1.buttons["ssh.import.key"], in: v1, upwards: false)
        v1.buttons["ssh.import.key"].tap()
        v1.buttons["Other key"].tap()
        reveal(authentication, in: v1)
        XCTAssertTrue((authentication.staticTexts.allElementsBoundByIndex.map(\.label) + [authentication.label]).contains { $0.contains(key) })
    }

    private func assertSshMissingKeyEditor(language: String, locale: String, edit: String, title: String) {
        let app = sshApp(language: language, locale: locale, scenario: "profiles")
        let connect = app.buttons["ssh.connect.alpha"]
        reveal(connect, in: app)
        connect.tap()
        XCTAssertTrue(app.alerts[title].waitForExistence(timeout: 10))
        app.alerts.buttons[edit].tap()
        XCTAssertTrue(app.buttons["ssh.editor.save"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["ssh.editor.save"].isEnabled)
        XCTAssertTrue(app.staticTexts["ssh.editor.key.required"].exists)
        let name = app.textFields["ssh.editor.name"]
        name.tap(); name.typeText(" draft")
        let done = app.buttons["ssh.editor.keyboard.done"]
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        done.tap()
        let keyboardDismissed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in app.keyboards.count == 0 }, object: app)
        XCTAssertEqual(XCTWaiter.wait(for: [keyboardDismissed], timeout: 10), .completed)
        let editorForm = app.collectionViews["ssh.editor.form"]
        XCTAssertTrue(editorForm.exists)
        let create = app.buttons["ssh.editor.create.key"]
        reveal(create, in: app, scrollView: editorForm)
        create.tap()
        let keyName = app.textFields["ssh.creator.name"]
        XCTAssertTrue(keyName.waitForExistence(timeout: 10))
        keyName.tap(); keyName.typeText("Replacement")
        app.buttons["ssh.creator.generate"].tap()
        XCTAssertTrue(app.buttons["ssh.editor.save"].waitForExistence(timeout: 10))
        reveal(app.buttons["ssh.editor.save"], in: app, scrollView: editorForm)
        XCTAssertTrue(app.buttons["ssh.editor.save"].isEnabled)
        XCTAssertEqual(app.textFields["ssh.editor.name"].value as? String, "Alpha draft")
        XCTAssertFalse(app.staticTexts["ssh.editor.key.required"].exists)
        app.buttons["ssh.editor.save"].tap()
        XCTAssertTrue(app.buttons["ssh.connect.alpha"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["ssh.connect.alpha"].label.contains("Alpha draft"))
        app.terminate()
        let retry = sshApp(language: language, locale: locale, scenario: "reconnect")
        let reconnect = retry.buttons["ssh.reconnect"]
        reveal(reconnect, in: retry)
        reconnect.tap()
        let reconnectLabel = language == "cs" ? "Znovu připojit" : "Reconnect"
        let confirm = retry.sheets.buttons[reconnectLabel]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.tap()
        XCTAssertTrue(retry.alerts[title].waitForExistence(timeout: 10))
        retry.alerts.buttons["OK"].tap()
        XCTAssertFalse(retry.alerts[title].exists)
    }

    private func assertSshInteractiveProfile(language: String, locale: String, password: String, key: String, message: String) {
        for choice in [password, key] {
            let app = sshApp(language: language, locale: locale, scenario: "interactive")
            let warning = app.staticTexts["ssh.interactive.alpha"]
            reveal(warning, in: app)
            XCTAssertEqual(warning.label, message)
            app.buttons["ssh.connect.alpha"].tap()
            let error = app.staticTexts["ssh.error"]
            reveal(error, in: app, upwards: false)
            XCTAssertEqual(error.label, message)
            app.buttons.matching(identifier: "OK").firstMatch.tap()
            reveal(app.buttons["ssh.edit.alpha"], in: app)
            app.buttons["ssh.edit.alpha"].tap()
            XCTAssertTrue(app.buttons["ssh.editor.save"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.buttons["ssh.editor.save"].isEnabled)
            app.buttons["ssh.editor.authentication"].tap()
            app.buttons[choice].tap()
            if choice == key {
                let editorForm = app.collectionViews["ssh.editor.form"]
                XCTAssertTrue(editorForm.exists)
                let picker = app.buttons["ssh.editor.key"]
                reveal(picker, in: app, scrollView: editorForm)
                picker.tap()
                let option = app.buttons.matching(NSPredicate(format: "label == %@", "Other key")).firstMatch
                XCTAssertTrue(option.waitForExistence(timeout: 10), "The visible key picker must open its menu")
                XCTAssertTrue(option.isHittable)
                option.tap()
                XCTAssertTrue(picker.label.contains("Other key"), "The chosen key must be reflected in the picker")
            }
            XCTAssertTrue(app.buttons["ssh.editor.save"].isEnabled)
            app.buttons["ssh.editor.save"].tap()
            XCTAssertTrue(app.buttons["ssh.connect.alpha"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.staticTexts["ssh.interactive.alpha"].exists)
            app.terminate()
        }
    }

    private func assertSshExportValidation(language: String, locale: String, duplicate: String, large: String) {
        for scenario in ["duplicates", "large"] {
            let app = sshApp(language: language, locale: locale, scenario: scenario)
            app.buttons["ssh.export"].tap()
            let error = app.staticTexts["ssh.export.error"]
            XCTAssertTrue(error.waitForExistence(timeout: 10))
            XCTAssertTrue(error.label.contains(scenario == "duplicates" ? duplicate : large))
            if scenario == "duplicates" { XCTAssertTrue(error.label.contains("Duplicate")) }
            XCTAssertFalse(app.textFields.matching(NSPredicate(format: "value CONTAINS[c] %@", "netkit-ssh-profiles")).firstMatch.exists)
            app.buttons.matching(identifier: "OK").firstMatch.tap()
            XCTAssertFalse(error.exists)
            app.terminate()
        }
    }
}
