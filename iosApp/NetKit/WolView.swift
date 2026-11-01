import Foundation
import NetKitShared
import SwiftUI

@MainActor
final class WolViewModel: ObservableObject {
    private let platform: WolPlatform
    private let storage: WolPersistence
    private let bridge: IosWolBridge
    private var observation: IosCancellation?
    @Published var state: WolUiState
    @Published var invalid = false

    init() {
        let platform = WolPlatform()
        let storage = WolPersistence()
        self.platform = platform
        self.storage = storage
        let bridge = IosWolBridge(platform: platform, persistence: storage,
                                  nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
                                  newId: { UUID().uuidString })
        self.bridge = bridge
        self.state = bridge.currentState()
        observation = bridge.observe { [weak self] state in
            self?.state = state
        }
    }

    func add(name: String, mac: String, host: String, port: String, broadcast: Bool) {
        guard let number = Int32(port), (1...65535).contains(number) else { invalid = true; return }
        let draft = bridge.draft(id: nil, name: name, mac: mac,
                                 targetMode: broadcast ? .limitedBroadcast : .custom,
                                 customTarget: host, port: number)
        invalid = !bridge.saveDraft(draft: draft)
    }
    func wake(_ id: String) { bridge.controller.wake(id: id) }
    func cancel() { bridge.controller.cancelWake() }
    func delete(_ id: String) { bridge.controller.delete(id: id) }
    deinit { observation?.cancel(); bridge.close() }
}

struct WolView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = WolViewModel()
    @State private var name = ""
    @State private var mac = ""
    @State private var host = ""
    @State private var port = "9"
    @State private var selectedID: String?
    @State private var confirm = false
    @State private var deleteID: String?
    @State private var confirmDelete = false

    var body: some View {
        Form {
            Text("wol.warning")
            if model.state.loadFailed { Text("wol.load.error").foregroundStyle(.red) }
            Section("wol.add") {
                TextField("wol.name", text: $name)
                TextField("wol.mac", text: $mac).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("wol.host", text: $host).keyboardType(.numbersAndPunctuation)
                TextField("lan.port", text: $port).keyboardType(.numberPad)
                Button("wol.save") { model.add(name: name, mac: mac, host: host, port: port, broadcast: false) }
                    .disabled(model.state.loadFailed || model.state.loading || model.state.saving)
                if model.invalid { Text("network.invalid").foregroundStyle(.red) }
            }
            Section("wol.devices") {
                if model.state.devices.isEmpty && !model.state.loading && !model.state.loadFailed { Text("wol.empty") }
                ForEach(model.state.devices, id: \.id) { device in
                    HStack {
                        Button(device.name) { selectedID = device.id; confirm = true }
                            .disabled(model.state.sendingDeviceId != nil)
                        Spacer()
                        Button(role: .destructive) { deleteID = device.id; confirmDelete = true } label: { Image(systemName: "trash") }
                            .accessibilityLabel("wol.delete")
                            .disabled(model.state.sendingDeviceId != nil)
                    }
                }
            }
            if model.state.sendingDeviceId != nil { Button("network.cancel") { model.cancel() } }
            if let message = model.state.message {
                switch message.name {
                case "SENT": Text("wol.sent")
                case "NO_WIFI": Text("wol.no.wifi")
                case "NO_BROADCAST": Text("wol.broadcast.unavailable")
                case "SAVE_FAILED": Text("wol.save.error")
                default: Text("network.failed")
                }
            }
        }
        .navigationTitle("tab.wol")
        .confirmationDialog("lan.consent.title", isPresented: $confirm, titleVisibility: .visible) {
            Button("lan.consent.confirm") { if let id = selectedID { model.wake(id) }; selectedID = nil }
            Button("network.cancel", role: .cancel) { selectedID = nil }
        } message: { Text("wol.warning") }
        .confirmationDialog("wol.delete.confirm", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("wol.delete", role: .destructive) { if let id = deleteID { model.delete(id) }; deleteID = nil }
            Button("network.cancel", role: .cancel) { deleteID = nil }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.cancel() }
        }
        .onDisappear { model.cancel() }
    }
}
