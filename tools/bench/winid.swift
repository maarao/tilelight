// usage: swift winid.swift <pid>  -- prints the id of the largest on-screen window of that process
import CoreGraphics
import Foundation
let pid = Int32(CommandLine.arguments[1])!
let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] ?? []
let best = list.filter { ($0[kCGWindowOwnerPID as String] as? Int32) == pid }
	.max { a, b in
		func area(_ w: [String: Any]) -> Double {
			let b = w[kCGWindowBounds as String] as? [String: Double] ?? [:]
			return (b["Width"] ?? 0) * (b["Height"] ?? 0)
		}
		return area(a) < area(b)
	}
if let id = best?[kCGWindowNumber as String] as? Int { print(id) } else { exit(1) }
