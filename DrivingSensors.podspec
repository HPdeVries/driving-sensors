require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'DrivingSensors'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = package['repository']['url']
  s.author = package['author']
  s.source = { :git => package['repository']['url'], :tag => s.version.to_s }
  s.source_files = 'ios/Sources/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target = '15.0'
  s.resource_bundles = { 'DrivingSensorsPlugin' => ['ios/Sources/DrivingSensorsPlugin/PrivacyInfo.xcprivacy'] }
  s.frameworks = 'CoreLocation', 'CoreMotion'
  s.libraries = 'sqlite3', 'z'
  s.dependency 'Capacitor'
  s.swift_version = '5.1'
end
