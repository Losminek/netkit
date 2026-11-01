import SwiftUI
import NetworkExtension
import NetKitShared

@main
struct NetKitApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var ssh = SshViewModel.forApp()
    var body: some View {
        TabView {
            NavigationStack { OverviewView() }
                .tabItem { Label("tab.overview", systemImage: "wifi") }
            NavigationStack { LanView() }
                .tabItem { Label("tab.devices", systemImage: "network") }
            NavigationStack { SshView(model: ssh) }
                .tabItem { Label("tab.ssh", systemImage: "terminal") }
            NavigationStack { WolView() }
                .tabItem { Label("tab.wol", systemImage: "power") }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { ssh.background() }
            else if phase == .active { ssh.foreground() }
        }
        .overlay {
            if scenePhase != .active {
                Color(uiColor: .systemBackground).ignoresSafeArea()
            }
        }
    }
}

struct OverviewView: View {
    @State private var network: NEHotspotNetwork?
    @State private var checked = false
    @State private var loading = false

    var body: some View {
        Form {
            Section("tab.wifi") {
                if let network {
                    LabeledContent("wifi.ssid", value: network.ssid)
                    LabeledContent("wifi.bssid", value: network.bssid)
                } else if checked {
                    Text("wifi.unavailable")
                } else {
                    Text("wifi.not_checked")
                }
                Button("wifi.refresh") {
                    loading = true
                    Task {
                        network = await NEHotspotNetwork.fetchCurrent()
                        checked = true
                        loading = false
                    }
                }.disabled(loading)
            }
            NavigationLink("speed.title") { SpeedView() }
            NavigationLink("tab.about") { AboutView() }
        }
        .navigationTitle("tab.overview")
    }
}

struct AboutView: View {
    private let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"

    var body: some View {
        Form {
            Text("app.title").font(.largeTitle).bold()
            HStack {
                Text("app.version")
                Spacer()
                Text(verbatim: version).accessibilityIdentifier("appVersionValue")
            }
            .accessibilityElement(children: .contain)
            if let url = URL(string: IosAboutDomain().privacyUrl(language: Bundle.main.preferredLocalizations.first ?? "en")) {
                Link("about.privacy", destination: url)
            }
            NavigationLink("about.licenses") { OpenSourceLicensesView() }
        }
        .navigationTitle("tab.about")
    }
}

struct OpenSourceLicensesView: View {
    private let text: String? = Bundle.main.url(forResource: "OpenSourceLicenses", withExtension: "txt")
        .flatMap { try? String(contentsOf: $0, encoding: .utf8) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading) {
                Text("about.licenses.content").font(.headline)
                Text(verbatim: text ?? String(localized: "about.licenses.unavailable"))
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .navigationTitle("about.licenses")
    }
}

@MainActor
extension SshViewModel {
    static func forApp() -> SshViewModel {
        #if DEBUG && targetEnvironment(simulator)
        let environment = ProcessInfo.processInfo.environment
        if let token = environment["NETKIT_UI_SSH_ID"], let id = UUID(uuidString: token),
           let scenario = environment["NETKIT_UI_SSH_SCENARIO"],
           ["empty", "profiles", "preview", "preview-v1", "reconnect", "interactive", "duplicates", "large"].contains(scenario) {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("netkit-ui-ssh-" + id.uuidString, isDirectory: true)
            let chain = KeychainSecrets(service: "cz.netkit.ios.ui-tests." + id.uuidString)
            do {
                if environment["NETKIT_UI_SSH_RESET"] == "1", FileManager.default.fileExists(atPath: root.path) {
                    try FileManager.default.removeItem(at: root)
                    try chain.deleteServiceItems()
                }
                let exists = FileManager.default.fileExists(atPath: root.appendingPathComponent("ssh.json").path)
                let store = try SshMetadataStore(directory: root)
                let domain = IosSshDomain()
                if !exists && scenario != "empty" {
                    try store.save(key: SshKeyInfo(id: "other-key", name: "Other key", publicKey: "ssh-ed25519 example"))
                    let reconnectReference = UUID().uuidString
                    if scenario == "reconnect" { try chain.put(reconnectReference, secret: Data(UUID().uuidString.utf8)) }
                    let count = scenario == "large" ? 65 : scenario.hasPrefix("preview") || scenario == "interactive" || scenario == "reconnect" ? 1 : 2
                    for index in 0..<count {
                        let name = scenario == "duplicates" ? "Duplicate" : index == 0 ? "Alpha" : scenario == "large" ? "Target \(index)" : "Beta"
                        let authentication: SshAuthentication = scenario == "interactive" ? .interactive : (scenario.hasPrefix("preview") || scenario == "reconnect") ? .password : .key
                        guard let profile = domain.profile(id: index == 0 ? "alpha" : "profile-\(index)", name: name,
                            host: "192.0.2.1", port: 22, username: "tester", authentication: authentication,
                            secretRef: scenario == "reconnect" ? reconnectReference : nil, keyRef: nil, startupCommand: scenario == "large" ? String(repeating: "x", count: 4096) : "", keepaliveSeconds: 30)
                        else { throw SshMetadataStore.Failure.invalid }
                        try store.save(profile: profile)
                    }
                }
                // This opt-in simulator harness never creates a real network connection.
                let model = SshViewModel(keychain: chain, directory: root, sessionFactory: { _, _, _, _, _ in
                    throw SshTransportFailure.unavailable
                })
                if scenario.hasPrefix("preview") || scenario == "reconnect" {
                    let file = root.appendingPathComponent("profiles-fixture.json")
                    var rows: [[String: Any]] = [
                        ["alias": "Alpha", "host": "192.0.2.2", "port": 2222, "username": "operator", "authentication": "KEY", "startupCommand": "show version", "keepaliveSeconds": 60],
                        ["alias": "Beta", "host": "192.0.2.3", "port": 22, "username": "tester", "authentication": "KEY", "startupCommand": "", "keepaliveSeconds": 30]
                    ]
                    let schema = scenario == "preview-v1" ? 1 : 2
                    if schema == 1 {
                        rows = rows.map { $0.filter { !["authentication", "startupCommand", "keepaliveSeconds"].contains($0.key) } }
                    }
                    try JSONSerialization.data(withJSONObject: ["format": "netkit-ssh-profiles", "schema": schema, "profiles": rows, "skipped": []]).write(to: file)
                    if scenario == "reconnect", let profile = model.data?.profiles.first {
                        model.connect(profile) // The injected factory fails before any network activity.
                        model.previewImport(file); model.confirmImport()
                    } else { model.uiImportURL = file }
                }
                return model
            } catch {
                let model = SshViewModel(keychain: chain, directory: root, sessionFactory: { _, _, _, _, _ in
                    throw SshTransportFailure.unavailable
                })
                model.error = .storage
                return model
            }
        }
        #endif
        return SshViewModel()
    }
}
