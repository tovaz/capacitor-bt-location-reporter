Pod::Spec.new do |s|
  s.name            = 'BtLocationReporter'
  s.version         = '0.1.0'
  s.summary         = 'Capacitor plugin for background BLE auto-connect and GPS location reporting'
  s.license         = 'MIT'
  s.homepage        = 'https://github.com/tovaz/capacitor-bt-location-reporter'
  s.author          = { 'William Valdez' => 'williamvaldez@outlook.com' }
  s.source          = { :git => 'https://github.com/tovaz/capacitor-bt-location-reporter.git', :tag => s.version.to_s }
  s.source_files    = 'ios/Plugin/**/*.{swift,h,m,c,cc,mm,cpp}'
  s.ios.deployment_target = '14.0'
  s.dependency 'Capacitor'
  s.swift_version   = '5.9'
end
