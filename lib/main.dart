
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MainApp());
}

class MainApp extends StatelessWidget {
  const MainApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Live UI Tracker',
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF10131A),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF2878F0),
          brightness: Brightness.dark,
        ),
      ),
      home: const TrackerDashboard(),
    );
  }
}

class TrackerDashboard extends StatefulWidget {
  const TrackerDashboard({super.key});

  @override
  State<TrackerDashboard> createState() => _TrackerDashboardState();
}

class _TrackerDashboardState extends State<TrackerDashboard>
    with WidgetsBindingObserver {
  static const MethodChannel _methods =
      MethodChannel('live_tracker/methods');

  Timer? _refreshTimer;

  Map<dynamic, dynamic>? currentApp;

  bool usageAccess = false;
  bool overlayAccess = false;
  bool tracking = false;
  bool loading = true;

  String errorMessage = '';

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addObserver(this);

    checkPermissions();

    _refreshTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => refreshCurrentApp(),
    );
  }

  Future<void> checkPermissions() async {
    try {
      final usage =
          await _methods.invokeMethod<bool>('hasUsageAccess');

      final overlay =
          await _methods.invokeMethod<bool>('hasOverlayPermission');

      if (!mounted) return;

      setState(() {
        usageAccess = usage ?? false;
        overlayAccess = overlay ?? false;
        loading = false;
      });

      if (usageAccess) {
        await refreshCurrentApp();
      }
    } catch (e) {
      if (!mounted) return;

      setState(() {
        errorMessage = e.toString();
        loading = false;
      });
    }
  }

  Future<void> refreshCurrentApp() async {
    if (!usageAccess) return;

    try {
      final result =
          await _methods.invokeMethod('getCurrentApp');

      if (!mounted || result == null) return;

      setState(() {
        currentApp = Map<dynamic, dynamic>.from(result);
      });
    } catch (e) {
      debugPrint('App detection error: $e');
    }
  }

  Future<void> openUsageSettings() async {
    await _methods.invokeMethod('openUsageSettings');
  }

  Future<void> openOverlaySettings() async {
    await _methods.invokeMethod('openOverlaySettings');
  }

  Future<void> startTracking() async {
    await checkPermissions();

    if (!usageAccess || !overlayAccess) {
      showMessage(
        'Please grant Usage Access and Display Over Other Apps first.',
      );
      return;
    }

    try {
      await _methods.invokeMethod('startTracker');

      if (!mounted) return;

      setState(() {
        tracking = true;
      });

      showMessage('Floating tracker started.');
    } catch (e) {
      showMessage('Unable to start tracker: $e');
    }
  }

  Future<void> stopTracking() async {
    try {
      await _methods.invokeMethod('stopTracker');

      if (!mounted) return;

      setState(() {
        tracking = false;
      });

      showMessage('Floating tracker stopped.');
    } catch (e) {
      showMessage('Unable to stop tracker: $e');
    }
  }

  Future<void> openDetectedAppInfo() async {
    final packageName =
        currentApp?['packageName']?.toString() ?? '';

    if (packageName.isEmpty) {
      showMessage('No application detected yet.');
      return;
    }

    try {
      await _methods.invokeMethod(
        'openAppInfo',
        {'packageName': packageName},
      );
    } catch (e) {
      showMessage('Unable to open App Info: $e');
    }
  }

  void showMessage(String message) {
    if (!mounted) return;

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      checkPermissions();
      refreshCurrentApp();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _refreshTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final appName =
        currentApp?['appName']?.toString() ??
            'No application detected';

    final packageName =
        currentApp?['packageName']?.toString() ?? '';

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Live UI Tracker',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            onPressed: checkPermissions,
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
          ),
        ],
      ),
      body: loading
          ? const Center(
              child: CircularProgressIndicator(),
            )
          : RefreshIndicator(
              onRefresh: checkPermissions,
              child: ListView(
                padding: const EdgeInsets.all(18),
                children: [
                  // TRACKING STATUS
                  Container(
                    padding: const EdgeInsets.all(18),
                    decoration: BoxDecoration(
                      color: const Color(0xFF1C2635),
                      borderRadius: BorderRadius.circular(18),
                    ),
                    child: Row(
                      children: [
                        const Icon(
                          Icons.track_changes,
                          size: 36,
                          color: Colors.blueAccent,
                        ),
                        const SizedBox(width: 14),
                        Expanded(
                          child: Column(
                            crossAxisAlignment:
                                CrossAxisAlignment.start,
                            children: [
                              const Text(
                                'Application Monitoring',
                                style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  fontSize: 17,
                                ),
                              ),
                              const SizedBox(height: 5),
                              Text(
                                tracking
                                    ? 'Floating tracker running'
                                    : 'Floating tracker stopped',
                                style: TextStyle(
                                  color: tracking
                                      ? Colors.greenAccent
                                      : Colors.orangeAccent,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Icon(
                          tracking
                              ? Icons.check_circle
                              : Icons.pause_circle,
                          color: tracking
                              ? Colors.greenAccent
                              : Colors.orangeAccent,
                        ),
                      ],
                    ),
                  ),

                  const SizedBox(height: 22),

                  // PERMISSIONS
                  const Text(
                    'Required Permissions',
                    style: TextStyle(
                      fontSize: 19,
                      fontWeight: FontWeight.bold,
                    ),
                  ),

                  const SizedBox(height: 12),

                  permissionTile(
                    title: 'Usage Access',
                    description:
                        'Allow Live UI Tracker to detect active apps.',
                    granted: usageAccess,
                    onTap: openUsageSettings,
                  ),

                  permissionTile(
                    title: 'Display Over Other Apps',
                    description:
                        'Allow the floating controller to appear.',
                    granted: overlayAccess,
                    onTap: openOverlaySettings,
                  ),

                  const SizedBox(height: 18),

                  // START / STOP
                  Row(
                    children: [
                      Expanded(
                        child: FilledButton.icon(
                          onPressed:
                              tracking ? null : startTracking,
                          icon: const Icon(Icons.play_arrow),
                          label: const Text('Start Tracker'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed:
                              tracking ? stopTracking : null,
                          icon: const Icon(Icons.stop),
                          label: const Text('Stop Tracker'),
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 26),

                  // DETECTED APPLICATION
                  const Text(
                    'Currently Detected Application',
                    style: TextStyle(
                      fontSize: 19,
                      fontWeight: FontWeight.bold,
                    ),
                  ),

                  const SizedBox(height: 12),

                  Container(
                    padding: const EdgeInsets.all(18),
                    decoration: BoxDecoration(
                      color: const Color(0xFF1C2635),
                      borderRadius: BorderRadius.circular(18),
                    ),
                    child: Column(
                      crossAxisAlignment:
                          CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const CircleAvatar(
                              backgroundColor:
                                  Color(0xFF2878F0),
                              child: Icon(
                                Icons.apps,
                                color: Colors.white,
                              ),
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Text(
                                appName,
                                style: const TextStyle(
                                  fontSize: 20,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                            IconButton(
                              onPressed: refreshCurrentApp,
                              icon: const Icon(Icons.refresh),
                            ),
                          ],
                        ),

                        const SizedBox(height: 10),

                        const Text(
                          'PACKAGE NAME',
                          style: TextStyle(
                            color: Colors.white54,
                            fontSize: 11,
                          ),
                        ),

                        const SizedBox(height: 4),

                        SelectableText(
                          packageName.isEmpty
                              ? 'Waiting for detection'
                              : packageName,
                          style: const TextStyle(
                            color: Colors.lightBlueAccent,
                          ),
                        ),

                        const SizedBox(height: 16),

                        SizedBox(
                          width: double.infinity,
                          child: FilledButton.icon(
                            onPressed: packageName.isEmpty
                                ? null
                                : openDetectedAppInfo,
                            icon: const Icon(Icons.settings),
                            label: const Text(
                              'Open App Info in Android Settings',
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),

                  const SizedBox(height: 20),

                  if (errorMessage.isNotEmpty)
                    Text(
                      errorMessage,
                      style: const TextStyle(
                        color: Colors.redAccent,
                      ),
                    ),
                ],
              ),
            ),
    );
  }

  Widget permissionTile({
    required String title,
    required String description,
    required bool granted,
    required VoidCallback onTap,
  }) {
    return Card(
      color: const Color(0xFF1C2635),
      margin: const EdgeInsets.only(bottom: 10),
      child: ListTile(
        onTap: onTap,
        leading: Icon(
          granted
              ? Icons.check_circle
              : Icons.warning_amber,
          color: granted
              ? Colors.greenAccent
              : Colors.orangeAccent,
        ),
        title: Text(title),
        subtitle: Text(description),
        trailing: Icon(
          granted ? Icons.check : Icons.arrow_forward_ios,
          size: 17,
        ),
      ),
    );
  }
}
