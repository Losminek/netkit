import SwiftUI
import Foundation
import NetKitShared
import UniformTypeIdentifiers

private struct ProfileEdit: Identifiable {
    let id = UUID()
    let profile: SshProfile?
}

/// The exporter owns a validated snapshot; no empty fallback document is constructed.
struct SshProfileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    let contents: Data
    init(contents: Data) { self.contents = contents }
    init(configuration: ReadConfiguration) throws {
        guard let contents = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.contents = contents
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: contents)
    }
}

/// Authentication is explicit. An imported INTERACTIVE profile starts with no supported choice.
struct SshProfileDraft {
    var name: String
    var host: String
    var port: String
    var username: String
    var authenticationChoice: String
    var keyRef: String
    init(profile: SshProfile?) {
        name = profile?.name ?? ""; host = profile?.host ?? ""; port = profile.map { String($0.port) } ?? "22"
        username = profile?.username ?? ""; keyRef = profile?.keyRef ?? ""
        authenticationChoice = profile?.authentication == .interactive ? "" : profile?.authentication == .key ? "KEY" : "PASSWORD"
    }
    var authentication: SshAuthentication? {
        switch authenticationChoice { case "PASSWORD": .password; case "KEY": .key; default: nil }
    }
    func needsKeySelection(keys: [SshKeyInfo]) -> Bool {
        authentication == .key && !keys.contains { $0.id == keyRef }
    }
}

func sshAuthenticationName(_ value: String) -> String {
    switch value {
    case "PASSWORD": String(localized: "ssh.password")
    case "KEY": String(localized: "ssh.authentication.key")
    default: String(localized: "ssh.authentication.interactive")
    }
}

func sshExportFailureMessage(_ failure: SshViewModel.ExportFailure) -> String {
    switch failure {
    case .duplicateNames(let names): String(localized: "ssh.export.duplicate.names") + "\n" + names.joined(separator: ", ")
    case .tooLarge: String(localized: "ssh.export.too.large")
    case .invalid: String(localized: "ssh.error.invalid")
    }
}

func errorKey(_ error: SshViewModel.ErrorKind) -> String {
    switch error {
    case .storage: "ssh.error.storage"
    case .invalid: "ssh.error.invalid"
    case .credentials: "ssh.error.credentials"
    case .keyInUse: "ssh.error.key.in.use"
    case .connection: "ssh.error.connection"
    case .algorithm: "ssh.error.algorithm"
    case .unsupportedCredential: "ssh.error.unsupported_credential"
    case .interactiveUnsupported: "ssh.interactive.unsupported"
    case .hostChanged: "ssh.error.host.changed"
    case .hostRejected: "ssh.error.host.rejected"
    case .timeout: "ssh.error.timeout"
    case .inputOverflow: "ssh.error.input"
    }
}

@MainActor
func sshHostPromptMessage(_ prompt: SshViewModel.HostPrompt) -> String {
    var text = "\(prompt.endpoint)\n\(prompt.algorithm)\n\(prompt.fingerprint)"
    if let previous = prompt.previousRejected {
        text += "\n\(String(localized: "ssh.host.previously.rejected"))\n\(previous)"
        if prompt.repeatedRejected { text += "\n\(String(localized: "ssh.host.rejected.again"))" }
    }
    return text
}

struct SshView: View {
    @ObservedObject var model: SshViewModel
    @State private var editing: ProfileEdit?
    @State private var showImporter = false
    @State private var showExporter = false
    @State private var exportDocument: SshProfileDocument?
    #if DEBUG && targetEnvironment(simulator)
    @State private var capturedUIExport: String?
    #endif
    @State private var reconnecting: SshProfile?
    @State private var showImportConfirm = false
    @State private var control = false
    @State private var followOutput = true
    @State private var keyboardReset = UUID()
    @State private var keyName = ""
    @State private var pendingPaste: String?
    @State private var showPasteConfirm = false
    @State private var resetEndpoint: String?
    @State private var showReset = false
    @State private var deleteProfile: SshProfile?
    @State private var deleteKey: SshKeyInfo?
    @State private var columns = 80
    @State private var rows = 24

    var body: some View {
        Form {
            Text("ssh.notice")
            if let error = model.error {
                Section {
                    Text(LocalizedStringKey(errorKey(error))).foregroundStyle(.red).accessibilityIdentifier("ssh.error")
                    Button("ssh.ok") { model.error = nil }
                }
            }
            if model.profileUnavailable {
                Section {
                    Text("ssh.profile.unavailable").foregroundStyle(.red).accessibilityIdentifier("ssh.profile.unavailable")
                    Button("ssh.ok") { model.profileUnavailable = false }
                }
            }
            if let failure = model.exportFailure {
                Section("ssh.export.failed") {
                    Text(verbatim: sshExportFailureMessage(failure)).foregroundStyle(.red).accessibilityIdentifier("ssh.export.error")
                    Button("ssh.ok") { model.exportFailure = nil }
                }
            }
            if let changed = model.changedHost {
                Section("ssh.host.changed.context") {
                    Text(verbatim: changed.endpoint)
                    LabeledContent("ssh.host.saved", value: changed.saved)
                    LabeledContent("ssh.host.offered", value: changed.offered)
                    Button("ssh.ok") { model.changedHost = nil; if model.error == .hostChanged { model.error = nil } }
                }
            }
            Section("ssh.profiles") {
                Button("ssh.add") { editing = ProfileEdit(profile: nil) }.accessibilityIdentifier("ssh.add")
                Button("ssh.import") { requestImport() }.accessibilityIdentifier("ssh.import")
                Button("ssh.export") {
                    #if DEBUG && targetEnvironment(simulator)
                    capturedUIExport = nil
                    #endif
                    if let prepared = model.prepareExport() {
                        #if DEBUG && targetEnvironment(simulator)
                        // Opt-in UI tests inspect the actual validated bytes without involving Files.
                        if ProcessInfo.processInfo.arguments.contains("-netkit-ui-capture-ssh-export") {
                            guard let json = String(data: prepared, encoding: .utf8) else { model.error = .invalid; return }
                            capturedUIExport = json; model.cancelExport(); return
                        }
                        #endif
                        exportDocument = SshProfileDocument(contents: prepared); showExporter = true
                    }
                }.accessibilityIdentifier("ssh.export")
                #if DEBUG && targetEnvironment(simulator)
                if let capturedUIExport {
                    Text(verbatim: "Captured SSH export")
                        .accessibilityValue(Text(verbatim: capturedUIExport))
                        .accessibilityIdentifier("ssh.ui.export.capture")
                }
                #endif
                if let profiles = model.data?.profiles {
                    ForEach(profiles, id: \.id) { profile in profileRow(profile) }
                    if profiles.isEmpty { Text("ssh.empty") }
                }
            }.disabled(model.busy)
            Section("ssh.keys") {
                TextField("ssh.key.name", text: $keyName)
                Button("ssh.key.generate") { model.generateKey(name: keyName); keyName = "" }
                ForEach(model.data?.keys ?? [], id: \.id) { key in
                    HStack {
                        Text(verbatim: key.name)
                        Spacer()
                        Button(role: .destructive) { deleteKey = key } label: { Image(systemName: "trash") }
                            .accessibilityLabel("ssh.key.delete")
                    }
                }
                if let publicKey = model.publicKey {
                    Text(verbatim: publicKey).textSelection(.enabled)
                    Button("ssh.key.copy") { UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: publicKey]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)]) }
                }
            }
            if let keys = model.data?.hostKeys, !keys.isEmpty {
                Section("ssh.trusted") {
                    ForEach(keys, id: \.endpoint) { key in
                        Button { resetEndpoint = key.endpoint; showReset = true } label: {
                            VStack(alignment: .leading) {
                                Text(verbatim: key.endpoint)
                                LabeledContent("ssh.host.saved", value: key.fingerprint)
                                if let rejected = model.data?.rejectedHostKeys.first(where: { $0.endpoint == key.endpoint }) {
                                    LabeledContent("ssh.host.offered", value: rejected.fingerprint)
                                }
                            }
                        }
                    }
                }
            }
            Section("ssh.terminal") {
                Toggle("ssh.follow", isOn: $followOutput)
                ScrollViewReader { proxy in
                    ScrollView([.horizontal, .vertical]) {
                        if let snapshot = model.terminalSnapshot { SshTerminalRows(snapshot: snapshot) }
                    }
                    .frame(minHeight: 160, maxHeight: 300)
                    .onChange(of: model.terminalRevision) { _, _ in
                        if followOutput { proxy.scrollTo("ssh-terminal-bottom", anchor: .bottom) }
                    }
                }
                if let profile = model.reconnectProfile, !model.connected {
                    Button("ssh.reconnect") { reconnecting = profile }.accessibilityIdentifier("ssh.reconnect")
                }
                if model.connected {
                    SshKeyboard(inputEpoch: keyboardReset, onComposition: { value, marked in
                        model.composition(value, marked: marked, control: control)
                        if !marked { control = false }
                    }, onKey: { enter in sendKey(enter ? .enter : .backspace) }, onPaste: { value in
                        if model.pasteNeedsConfirmation(value) { pendingPaste = value; showPasteConfirm = true }
                        else { model.paste(value); keyboardReset = UUID() }
                    }).frame(height: 44)
                    HStack {
                        Toggle("ssh.ctrl", isOn: $control).toggleStyle(.button)
                        Button("ssh.esc") { sendKey(.escape) }
                        Button("←") { sendKey(.left) }.accessibilityLabel("ssh.left")
                        Button("↑") { sendKey(.up) }.accessibilityLabel("ssh.up")
                        Button("↓") { sendKey(.down) }.accessibilityLabel("ssh.down")
                        Button("→") { sendKey(.right) }.accessibilityLabel("ssh.right")
                    }
                    Stepper(value: $columns, in: 10...240, step: 10) { LabeledContent("ssh.columns", value: String(columns)) }
                    Stepper(value: $rows, in: 2...100, step: 2) { LabeledContent("ssh.rows", value: String(rows)) }
                    Button("ssh.resize") { model.resize(columns: columns, rows: rows) }
                    Button("ssh.copy") { model.copyTerminal() }
                    Button("ssh.disconnect") { model.disconnect() }
                }
            }
        }
        .navigationTitle("tab.ssh")
        .sheet(item: $editing) { item in
            SshProfileEditor(model: model, profile: item.profile)
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json]) { result in
            switch result {
            case .success(let url): model.previewImport(url); showImportConfirm = model.importPlan != nil
            case .failure(let error): if !isUserCancellation(error) { model.error = .invalid }
            }
        }
        .fileExporter(isPresented: $showExporter, document: exportDocument, contentType: .json,
                      defaultFilename: "netkit-ssh-profiles.json") { result in
            if case .failure(let error) = result, !isUserCancellation(error) { model.error = .storage }
            exportDocument = nil; model.cancelExport()
        }
        .alert("ssh.key.required.title", isPresented: Binding(get: { model.keySelectionRequired != nil },
            set: { if !$0 { model.keySelectionRequired = nil } }), presenting: model.keySelectionRequired) { presented in
            Button("ssh.edit") {
                if let profile = model.profileForKeySelection(presented) { editing = ProfileEdit(profile: profile) }
            }
            Button("ssh.ok", role: .cancel) { model.keySelectionRequired = nil }
        } message: { profile in
            Text(verbatim: profile.name + "\n" + String(localized: "ssh.key.required.message"))
        }
        .alert("ssh.host.first", isPresented: Binding(get: { model.hostPrompt != nil }, set: { _ in }), presenting: model.hostPrompt) { prompt in
            Button("ssh.host.accept") { model.answerHost(id: prompt.id, accept: true) }
            Button("ssh.host.reject", role: .cancel) { model.answerHost(id: prompt.id, accept: false) }
        } message: { prompt in
            Text(verbatim: sshHostPromptMessage(prompt))
        }
        .sheet(isPresented: $showImportConfirm, onDismiss: { model.cancelImport() }) {
            SshImportPreviewView(model: model)
        }
        .confirmationDialog("ssh.reconnect.confirm", isPresented: Binding(get: { reconnecting != nil },
            set: { if !$0 { reconnecting = nil } }), titleVisibility: .visible, presenting: reconnecting) { profile in
            Button("ssh.reconnect") { model.reconnect(profile); reconnecting = nil }
            Button("network.cancel", role: .cancel) { reconnecting = nil }
        } message: { profile in
            Text(verbatim: "\(profile.username)@\(profile.endpoint)" +
                (profile.startupCommand.isEmpty ? "" : "\n" + String(localized: "ssh.startup.command") + ": " + profile.startupCommand))
        }
        .confirmationDialog("ssh.paste.warning", isPresented: $showPasteConfirm, titleVisibility: .visible) {
            Button("ssh.paste.confirm") { if let text = pendingPaste { model.paste(text); keyboardReset = UUID() }; pendingPaste = nil }
            Button("network.cancel", role: .cancel) { pendingPaste = nil }
        }
        .confirmationDialog("ssh.trust.reset.warning", isPresented: $showReset, titleVisibility: .visible) {
            Button("ssh.trust.reset", role: .destructive) { if let endpoint = resetEndpoint { model.resetTrust(endpoint: endpoint) }; resetEndpoint = nil }
            Button("network.cancel", role: .cancel) { resetEndpoint = nil }
        } message: {
            if let endpoint = resetEndpoint, let key = model.data?.hostKeys.first(where: { $0.endpoint == endpoint }) {
                Text(verbatim: trustResetMessage(key, rejected: model.data?.rejectedHostKeys.first(where: { $0.endpoint == endpoint })))
            }
        }
        .confirmationDialog("ssh.profile.delete.confirm", isPresented: Binding(get: { deleteProfile != nil }, set: { if !$0 { deleteProfile = nil } }), titleVisibility: .visible, presenting: deleteProfile) { profile in
            Button("ssh.profile.delete", role: .destructive) { model.deleteProfile(profile); deleteProfile = nil }
            Button("network.cancel", role: .cancel) { deleteProfile = nil }
        }
        .confirmationDialog("ssh.key.delete.confirm", isPresented: Binding(get: { deleteKey != nil }, set: { if !$0 { deleteKey = nil } }), titleVisibility: .visible, presenting: deleteKey) { key in
            Button("ssh.key.delete", role: .destructive) { model.deleteKey(key); deleteKey = nil }
            Button("network.cancel", role: .cancel) { deleteKey = nil }
        }
    }

    private func trustResetMessage(_ key: TrustedHostKey, rejected: TrustedHostKey?) -> String {
        var text = "\(key.endpoint)\n\(String(localized: "ssh.host.saved")): \(key.fingerprint)"
        if let rejected { text += "\n\(String(localized: "ssh.host.offered")): \(rejected.fingerprint)" }
        return text
    }

    private func sendKey(_ key: TerminalKey) {
        model.key(key, control: control)
        control = false
        keyboardReset = UUID()
    }

    private func profileRow(_ profile: SshProfile) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { model.connect(profile) } label: {
                VStack(alignment: .leading) {
                    Text(verbatim: profile.name).font(.headline)
                    Text(verbatim: "\(profile.username)@\(profile.endpoint)").font(.caption).foregroundStyle(.secondary)
                }
            }.disabled(model.connected)
                .accessibilityLabel(Text(verbatim: profile.name + ", " + profile.username + "@" + profile.endpoint))
                .accessibilityIdentifier("ssh.connect.\(profile.id)")
            if profile.authentication == .interactive {
                Text("ssh.interactive.unsupported").foregroundStyle(.orange).accessibilityIdentifier("ssh.interactive.\(profile.id)")
            }
            HStack {
                Button("ssh.edit") { editing = ProfileEdit(profile: profile) }
                    .disabled(model.connected).accessibilityIdentifier("ssh.edit.\(profile.id)")
                Spacer()
                Button { model.moveProfile(id: profile.id, delta: -1) } label: { Image(systemName: "arrow.up").frame(minWidth: 44, minHeight: 44) }
                    .disabled(!model.canMoveProfile(id: profile.id, delta: -1))
                    .accessibilityLabel(String(format: String(localized: "ssh.move.up"), profile.name))
                    .accessibilityIdentifier("ssh.move.up.\(profile.id)")
                Button { model.moveProfile(id: profile.id, delta: 1) } label: { Image(systemName: "arrow.down").frame(minWidth: 44, minHeight: 44) }
                    .disabled(!model.canMoveProfile(id: profile.id, delta: 1))
                    .accessibilityLabel(String(format: String(localized: "ssh.move.down"), profile.name))
                    .accessibilityIdentifier("ssh.move.down.\(profile.id)")
                Button(role: .destructive) { deleteProfile = profile } label: { Image(systemName: "trash").frame(minWidth: 44, minHeight: 44) }
                    .accessibilityLabel("ssh.profile.delete").accessibilityIdentifier("ssh.delete.\(profile.id)")
            }
        }.buttonStyle(.borderless)
    }

    private func requestImport() {
        #if DEBUG && targetEnvironment(simulator)
        if let fixture = model.uiImportURL {
            model.previewImport(fixture); showImportConfirm = model.importPlan != nil; return
        }
        #endif
        showImporter = true
    }

    private func isUserCancellation(_ error: Error) -> Bool {
        (error as NSError).domain == NSCocoaErrorDomain && (error as NSError).code == NSUserCancelledError
    }
}

private struct SshProfileEditor: View {
    private enum Field: Hashable { case name, host, port, username, password }
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: SshViewModel
    let profile: SshProfile?
    @State private var draft: SshProfileDraft
    @State private var password = ""
    @State private var failed = false
    @State private var showKeyCreator = false
    @FocusState private var focusedField: Field?

    init(model: SshViewModel, profile: SshProfile?) {
        self.model = model; self.profile = profile
        var initial = SshProfileDraft(profile: profile)
        if initial.needsKeySelection(keys: model.data?.keys ?? []) { initial.keyRef = "" }
        _draft = State(initialValue: initial)
    }
    var body: some View {
        NavigationStack {
            Form {
                TextField("ssh.name", text: $draft.name).focused($focusedField, equals: .name).accessibilityIdentifier("ssh.editor.name")
                TextField("ssh.host", text: $draft.host).focused($focusedField, equals: .host).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("ssh.editor.host")
                TextField("lan.port", text: $draft.port).focused($focusedField, equals: .port).keyboardType(.numberPad).accessibilityIdentifier("ssh.editor.port")
                TextField("ssh.user", text: $draft.username).focused($focusedField, equals: .username).textInputAutocapitalization(.never).autocorrectionDisabled()
                if profile?.authentication == .interactive { Text("ssh.interactive.unsupported").foregroundStyle(.orange) }
                Picker("ssh.authentication", selection: $draft.authenticationChoice) {
                    if draft.authenticationChoice.isEmpty { Text("ssh.authentication.choose").tag("") }
                    Text("ssh.password").tag("PASSWORD")
                    Text("ssh.authentication.key").tag("KEY")
                }.pickerStyle(.menu).accessibilityIdentifier("ssh.editor.authentication")
                if draft.authentication == .key {
                    Picker("ssh.authentication.key", selection: $draft.keyRef) {
                        Text("ssh.key.choose").tag("")
                        ForEach(model.data?.keys ?? [], id: \.id) { Text(verbatim: $0.name).tag($0.id) }
                    }.pickerStyle(.menu).accessibilityIdentifier("ssh.editor.key")
                    if draft.needsKeySelection(keys: model.data?.keys ?? []) {
                        Text("ssh.key.required.message").foregroundStyle(.orange).accessibilityIdentifier("ssh.editor.key.required")
                    }
                    Button("ssh.key.generate") { password = ""; showKeyCreator = true }.accessibilityIdentifier("ssh.editor.create.key")
                } else if draft.authentication == .password {
                    SecureField("ssh.password", text: $password).focused($focusedField, equals: .password).textContentType(.password)
                }
                Button("ssh.save") {
                    guard let authentication = draft.authentication else { return }
                    if model.saveProfile(id: profile?.id, name: draft.name, host: draft.host, port: draft.port, username: draft.username,
                        keyRef: authentication == .key ? draft.keyRef : nil, password: password, authentication: authentication) {
                        password = ""; dismiss()
                    } else { failed = true }
                }.disabled(model.busy || draft.authentication == nil || draft.needsKeySelection(keys: model.data?.keys ?? []))
                    .accessibilityIdentifier("ssh.editor.save")
                if failed {
                    Text(LocalizedStringKey(model.profileUnavailable ? "ssh.profile.unavailable" : errorKey(model.error ?? .invalid)))
                        .foregroundStyle(.red).accessibilityIdentifier("ssh.editor.error")
                    Button("ssh.ok") { failed = false; model.error = nil }
                }
            }
            .accessibilityIdentifier("ssh.editor.form")
            .onAppear { model.error = nil; failed = false }
            .navigationTitle("ssh.profile")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("network.cancel") { password = ""; dismiss() } }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("ssh.keyboard.done") { focusedField = nil }.accessibilityIdentifier("ssh.editor.keyboard.done")
                }
            }
            .onChange(of: draft.authenticationChoice) { _, _ in password = "" }
            .onDisappear { password = "" }
            .sheet(isPresented: $showKeyCreator) {
                SshKeyCreator(model: model) { id in draft.keyRef = id }
            }
        }
    }
}

private struct SshKeyCreator: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: SshViewModel
    let created: (String) -> Void
    @State private var name = ""
    var body: some View {
        NavigationStack {
            Form {
                TextField("ssh.key.name", text: $name).accessibilityIdentifier("ssh.creator.name")
                Button("ssh.key.generate") {
                    let previous = Set(model.data?.keys.map(\.id) ?? [])
                    model.generateKey(name: name)
                    if let key = model.data?.keys.first(where: { !previous.contains($0.id) }) { created(key.id); dismiss() }
                }.disabled(model.busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("ssh.creator.generate")
                if let error = model.error {
                    Text(LocalizedStringKey(errorKey(error))).foregroundStyle(.red).accessibilityIdentifier("ssh.creator.error")
                    Button("ssh.ok") { model.error = nil }
                }
            }.navigationTitle("ssh.key.generate")
                .onAppear { model.error = nil }
                .toolbar { ToolbarItem(placement: .cancellationAction) {
                    Button("network.cancel") { dismiss() }.accessibilityIdentifier("ssh.creator.cancel")
                } }
        }
    }
}

private struct SshImportPreviewView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: SshViewModel
    var body: some View {
        NavigationStack {
            if let plan = model.importPlan {
                List {
                    Section {
                        LabeledContent("ssh.import.new", value: String(model.importAddedCount(plan)))
                        LabeledContent("ssh.import.updated", value: String(plan.updatedProfiles.count))
                        LabeledContent("ssh.import.unchanged", value: String(plan.unchangedProfiles.count))
                        LabeledContent("ssh.import.skipped", value: String(plan.skipped.count))
                        Picker("ssh.import.key", selection: Binding(get: { model.importKeyRef ?? "" },
                            set: { model.selectImportKey($0.isEmpty ? nil : $0) })) {
                            Text("ssh.import.no.key").tag("")
                            ForEach(model.data?.keys ?? [], id: \.id) { Text(verbatim: $0.name).tag($0.id) }
                        }.pickerStyle(.menu).accessibilityIdentifier("ssh.import.key")
                    }
                    ForEach(model.importPreviews(plan), id: \.after.id) { preview in
                        Section {
                            if preview.before == nil {
                                LabeledContent("ssh.host", value: preview.after.host)
                                LabeledContent("lan.port", value: String(preview.after.port))
                                LabeledContent("ssh.user", value: preview.after.username)
                                LabeledContent("ssh.authentication", value: sshAuthenticationName(preview.after.authentication.name))
                                    .accessibilityIdentifier("ssh.import.authentication.\(preview.after.name)")
                                if !preview.after.startupCommand.isEmpty { LabeledContent("ssh.startup.command", value: preview.after.startupCommand) }
                                LabeledContent("ssh.keepalive", value: String(preview.after.keepaliveSeconds))
                            }
                            ForEach(preview.changes, id: \.field) { change in
                                LabeledContent(fieldName(change.field), value: display(change.before, field: change.field) + " → " + display(change.after, field: change.field))
                                    .accessibilityIdentifier("ssh.import.change.\(change.field.name)")
                            }
                            if preview.startupWarning { Text("ssh.import.startup.warning").foregroundStyle(.orange).accessibilityIdentifier("ssh.import.startup.warning") }
                            if preview.needsKeySelection { Text("ssh.key.required.message").foregroundStyle(.orange).accessibilityIdentifier("ssh.import.key.required") }
                            if preview.after.authentication == .interactive { Text("ssh.interactive.unsupported").foregroundStyle(.orange) }
                            if preview.before?.secretRef != nil && preview.after.secretRef == nil { Text("ssh.import.password.detached").foregroundStyle(.orange) }
                        } header: { Text(verbatim: preview.after.name) }
                    }
                    ForEach(Array(plan.skipped.enumerated()), id: \.offset) { _, entry in
                        Text(verbatim: entry.alias + " — " + skipReason(entry.reason.name))
                    }
                }
                .navigationTitle("ssh.import.preview")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("network.cancel") { model.cancelImport(); dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("ssh.import.apply") { model.confirmImport(); dismiss() }
                            .disabled(model.busy || (model.importAddedCount(plan) == 0 && plan.updatedProfiles.isEmpty))
                            .accessibilityIdentifier("ssh.import.apply")
                    }
                }
            }
        }
    }
    private func display(_ value: String, field: IosSshImportField) -> String {
        if field == .authentication { return sshAuthenticationName(value) }
        return value.isEmpty ? String(localized: "ssh.value.empty") : value
    }
    private func fieldName(_ field: IosSshImportField) -> LocalizedStringKey {
        switch field {
        case .host: "ssh.host"
        case .port: "lan.port"
        case .username: "ssh.user"
        case .authentication: "ssh.authentication"
        case .startupCommand: "ssh.startup.command"
        default: "ssh.keepalive"
        }
    }
    private func skipReason(_ reason: String) -> String {
        switch reason {
        case "PROXY_JUMP": String(localized: "ssh.skip.proxyjump")
        case "PROXY_COMMAND": String(localized: "ssh.skip.proxycommand")
        case "MATCH": String(localized: "ssh.skip.match")
        case "WILDCARD": String(localized: "ssh.skip.wildcard")
        case "MISSING_HOST": String(localized: "ssh.skip.missinghost")
        default: String(localized: "ssh.skip.unsupported")
        }
    }
}
