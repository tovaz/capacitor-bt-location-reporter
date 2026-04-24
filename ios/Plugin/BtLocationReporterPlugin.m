// ObjC bridge — required so Capacitor can discover the Swift plugin class at runtime.
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(BtLocationReporterPlugin, "BtLocationReporter",
    CAP_PLUGIN_METHOD(start,                     CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(stop,                      CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(isRunning,                 CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(addDevices,                CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(removeDevices,             CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getLogPath,                CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getLogs,                   CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(requestLocationPermission, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(hasLocationPermission,     CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(writeWithoutResponse,      CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(startLiveTracking,         CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(stopLiveTracking,          CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getLiveTrackingDevices,    CAPPluginReturnPromise);
)
