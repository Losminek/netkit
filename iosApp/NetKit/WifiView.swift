import NetworkExtension
import SwiftUI

struct WifiView: View {
    @State private var network: NEHotspotNetwork?
    @State private var checked = false
    @State private var loading = false

    var body: some View {
        Form {
            if let network {
                LabeledContent("wifi.ssid", value: network.ssid)
                LabeledContent("wifi.bssid", value: network.bssid)
            } else if checked {
                Text("wifi.unavailable")
            }
            Button("wifi.refresh") {
                loading = true
                Task {
                    network = await NEHotspotNetwork.fetchCurrent()
                    checked = true
                    loading = false
                }
            }
            .disabled(loading)
        }
        .navigationTitle("tab.wifi")
    }
}
