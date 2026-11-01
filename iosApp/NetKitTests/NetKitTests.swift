@testable import NetKit
import XCTest
import NetKitShared

final class NetKitTests: XCTestCase {
    func testPrivacyUrlsAndOfflineLicenses() throws {
        let domain = IosAboutDomain()
        XCTAssertEqual(domain.privacyUrl(language: "cs"), "https://www.daniel.losak.cz/netkit/privacy")
        XCTAssertEqual(domain.privacyUrl(language: "en"), "https://www.daniel.losak.cz/en/netkit/privacy")
        XCTAssertEqual(domain.privacyUrl(language: "de"), "https://www.daniel.losak.cz/en/netkit/privacy")
        let url = try XCTUnwrap(Bundle.main.url(forResource: "OpenSourceLicenses", withExtension: "txt"))
        let licenses = try String(contentsOf: url, encoding: .utf8)
        for component in ["swift-nio-ssh 0.15.1", "swift-nio 2.84.0", "swift-crypto 3.12.3",
                          "swift-atomics 1.3.0", "swift-collections 1.2.0", "swift-system 1.5.0",
                          "swift-asn1 1.4.0", "Kotlin 2.2.20", "kotlinx-coroutines 1.10.2",
                          "kotlinx-serialization 1.9.0", "kotlinx-atomicfu 0.26.1"] {
            XCTAssertTrue(licenses.contains(component), component)
        }
        XCTAssertTrue(licenses.contains("Apache License"))
    }

    func testSharedIpv4Parser() {
        XCTAssertEqual(WolDomainKt.parseIpv4(input: "192.0.2.1")?.description, "192.0.2.1")
        XCTAssertNil(WolDomainKt.parseIpv4(input: "192.0.2.256"))
    }
}
