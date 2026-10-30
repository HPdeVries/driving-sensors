import XCTest
@testable import DrivingSensorsPlugin

final class VersionTests: XCTestCase {
    func testCurrentVersionIsNonEmptySemver() {
        let v = DrivingSensorsVersion.current
        XCTAssertFalse(v.isEmpty)
        XCTAssertNotNil(v.range(of: #"^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$"#, options: .regularExpression))
    }
}
