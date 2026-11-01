import NetKitShared
import SwiftUI
import UIKit

/// Displays the bounded shared terminal snapshot, including SGR colors and the active cursor.
struct SshTerminalRows: View {
    let snapshot: TerminalSnapshot
    private let font = UIFont.monospacedSystemFont(ofSize: 14, weight: .regular)
    private var cellWidth: CGFloat { ("M" as NSString).size(withAttributes: [.font: font]).width }
    private var cellHeight: CGFloat { font.lineHeight }

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(Array(snapshot.history.enumerated()), id: \.offset) { _, row in
                rowView(row, cursor: nil)
            }
            ForEach(Array(snapshot.screen.enumerated()), id: \.offset) { index, row in
                rowView(row, cursor: snapshot.cursorVisible && index == Int(snapshot.cursorRow) ? Int(snapshot.cursorColumn) : nil)
            }
            Color.clear.frame(height: 1).id("ssh-terminal-bottom")
        }
        .background(Color.black)
        .accessibilityElement(children: .contain)
    }

    private func rowView(_ row: TerminalRow, cursor: Int?) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(row.runs.enumerated()), id: \.offset) { _, run in
                Text(verbatim: run.text)
                    .font(.system(size: 14, weight: run.bold ? .bold : .regular, design: .monospaced))
                    .foregroundStyle(color(run.foreground))
                    .background(color(run.background))
                    .fixedSize()
            }
        }
        .frame(height: cellHeight, alignment: .leading)
        .overlay(alignment: .topLeading) {
            if let cursor {
                Rectangle().stroke(Color.white, lineWidth: 1)
                    .frame(width: cellWidth, height: cellHeight)
                    .offset(x: CGFloat(cursor) * cellWidth)
                    .accessibilityHidden(true)
            }
        }
    }

    private func color(_ value: Int32) -> Color {
        let n = Int(value)
        if n & 0x1000000 != 0 {
            return Color(red: Double((n >> 16) & 255) / 255, green: Double((n >> 8) & 255) / 255, blue: Double(n & 255) / 255)
        }
        let basic: [(Double, Double, Double)] = [
            (0, 0, 0), (0.67, 0, 0), (0, 0.67, 0), (0.67, 0.33, 0),
            (0, 0, 0.67), (0.67, 0, 0.67), (0, 0.67, 0.67), (0.75, 0.75, 0.75),
            (0.33, 0.33, 0.33), (1, 0.33, 0.33), (0.33, 1, 0.33), (1, 1, 0.33),
            (0.33, 0.33, 1), (1, 0.33, 1), (0.33, 1, 1), (1, 1, 1)
        ]
        if (0..<16).contains(n) { let c = basic[n]; return Color(red: c.0, green: c.1, blue: c.2) }
        if (16..<232).contains(n) {
            let index = n - 16
            let levels: [Double] = [0, 95, 135, 175, 215, 255]
            return Color(red: levels[index / 36] / 255, green: levels[(index / 6) % 6] / 255, blue: levels[index % 6] / 255)
        }
        let gray = Double(8 + min(23, max(0, n - 232)) * 10) / 255
        return Color(red: gray, green: gray, blue: gray)
    }
}
