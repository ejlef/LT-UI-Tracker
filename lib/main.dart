import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MainApp());
}

// ============================================================
// LAZY ANDROID APP ICON
// ============================================================

class AppIconView extends StatefulWidget {
  final String packageName;
  final double size;

  const AppIconView({super.key, required this.packageName, this.size = 48});

  @override
  State<AppIconView> createState() => _AppIconViewState();
}

class _AppIconViewState extends State<AppIconView> {
  static const MethodChannel _channel = MethodChannel('live_tracker/methods');

  static final Map<String, Uint8List> _cache = {};
  static final Map<String, Future<Uint8List?>> _pending = {};

  Future<Uint8List?>? _iconFuture;

  @override
  void initState() {
    super.initState();
    _iconFuture = _loadIcon();
  }

  @override
  void didUpdateWidget(covariant AppIconView oldWidget) {
    super.didUpdateWidget(oldWidget);

    if (oldWidget.packageName != widget.packageName) {
      _iconFuture = _loadIcon();
    }
  }

  Future<Uint8List?> _loadIcon() {
    final package = widget.packageName;

    if (package.isEmpty) {
      return Future.value(null);
    }

    final cached = _cache[package];

    if (cached != null) {
      return Future.value(cached);
    }

    return _pending.putIfAbsent(package, () async {
      try {
        final encoded = await _channel.invokeMethod<String>('getAppIcon', {
          'packageName': package,
        });

        if (encoded == null || encoded.isEmpty) {
          return null;
        }

        final bytes = base64Decode(encoded);

        _cache[package] = bytes;

        if (_cache.length > 150) {
          _cache.remove(_cache.keys.first);
        }

        return bytes;
      } catch (e) {
        debugPrint('Icon loading error for $package: $e');
        return null;
      } finally {
        _pending.remove(package);
      }
    });
  }

  Widget _fallback() {
    return Container(
      width: widget.size,
      height: widget.size,
      decoration: BoxDecoration(
        color: const Color(0xFF253B5C),
        borderRadius: BorderRadius.circular(12),
      ),
      child: const Icon(Icons.android, color: Colors.lightBlueAccent),
    );
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Uint8List?>(
      future: _iconFuture,
      builder: (context, snapshot) {
        final bytes = snapshot.data;

        if (bytes == null) {
          return _fallback();
        }

        return ClipRRect(
          borderRadius: BorderRadius.circular(12),
          child: Image.memory(
            bytes,
            width: widget.size,
            height: widget.size,
            fit: BoxFit.contain,
            gaplessPlayback: true,
            errorBuilder: (context, error, stackTrace) {
              return _fallback();
            },
          ),
        );
      },
    );
  }
}

// ============================================================
// MAIN APP
// ============================================================

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

// ============================================================
// DASHBOARD STATE
// ============================================================

class TrackerDashboard extends StatefulWidget {
  const TrackerDashboard({super.key});

  @override
  State<TrackerDashboard> createState() => _TrackerDashboardState();
}

class _TrackerDashboardState extends State<TrackerDashboard>
    with WidgetsBindingObserver {
  static const MethodChannel _methods = MethodChannel('live_tracker/methods');

  Timer? _refreshTimer;
  bool _statusCheckInProgress = false;
  int selectedIndex = 0;

  Map<dynamic, dynamic>? currentApp;

  bool usageAccess = false;
  bool overlayAccess = false;
  bool tracking = false;
  bool loading = true;

  String errorMessage = '';

  List<Map<String, dynamic>> installedApps = [];

  bool appsLoading = false;
  bool rootAvailable = false;

  bool _appsLoaded = false;
  bool _refreshAppsOnResume = false;

  String searchQuery = '';
  String appFilter = 'All';

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addObserver(this);

    checkPermissions();
    checkRootAccess();
    loadInstalledApps();

    // Read the actual Android service state.
    syncTrackerStatus();
  }

  // ============================================================
  // TRACKER STATUS AND TIMER
  // ============================================================

  void _startRefreshTimer() {
    if (_refreshTimer?.isActive == true) return;

    _refreshTimer = Timer.periodic(const Duration(seconds: 2), (_) async {
      // Check the real Android foreground service status.
      await syncTrackerStatus(refreshApp: false);

      // Refresh detected application only while tracking.
      if (tracking && usageAccess) {
        await refreshCurrentApp();
      }
    });
  }

  void _stopRefreshTimer() {
    _refreshTimer?.cancel();
    _refreshTimer = null;
  }

  Future<void> syncTrackerStatus({bool refreshApp = true}) async {
    if (_statusCheckInProgress) return;

    _statusCheckInProgress = true;

    try {
      final running =
          await _methods.invokeMethod<bool>('isTrackerRunning') ?? false;

      if (!mounted) return;

      if (tracking != running) {
        setState(() {
          tracking = running;
        });
      }

      if (running) {
        _startRefreshTimer();

        if (refreshApp && usageAccess) {
          await refreshCurrentApp();
        }
      } else {
        _stopRefreshTimer();
      }
    } catch (e) {
      debugPrint('Tracker status error: $e');
    } finally {
      _statusCheckInProgress = false;
    }
  }

  // ============================================================
  // DASHBOARD AND PERMISSIONS
  // ============================================================

  Future<void> checkPermissions() async {
    try {
      final usage = await _methods.invokeMethod<bool>('hasUsageAccess');

      final overlay = await _methods.invokeMethod<bool>('hasOverlayPermission');

      if (!mounted) return;

      setState(() {
        usageAccess = usage ?? false;
        overlayAccess = overlay ?? false;
        loading = false;
      });

      if (usageAccess && tracking) {
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
      final result = await _methods.invokeMethod('getCurrentApp');

      if (!mounted || result == null) return;

      final nextApp = Map<dynamic, dynamic>.from(result);

      final samePackage = currentApp?['packageName'] == nextApp['packageName'];

      final sameDetectionTime =
          currentApp?['detectedAt'] == nextApp['detectedAt'];

      if (samePackage && sameDetectionTime) {
        return;
      }

      setState(() {
        currentApp = nextApp;
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

      _startRefreshTimer();

      await refreshCurrentApp();

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

      _stopRefreshTimer();

      showMessage('Floating tracker stopped.');
    } catch (e) {
      showMessage('Unable to stop tracker: $e');
    }
  }

  Future<void> openDetectedAppInfo() async {
    final packageName = currentApp?['packageName']?.toString() ?? '';

    if (packageName.isEmpty) {
      showMessage('No application detected yet.');
      return;
    }

    await openAppInfo(packageName);
  }

  // ============================================================
  // ROOT DETECTION
  // ============================================================

  Future<void> checkRootAccess() async {
    try {
      final result = await _methods.invokeMethod<bool>('hasRootAccess');

      if (!mounted) return;

      setState(() {
        rootAvailable = result ?? false;
      });
    } catch (e) {
      if (!mounted) return;

      setState(() {
        rootAvailable = false;
      });
    }
  }

  // ============================================================
  // APPS MANAGER - CACHED LOADING
  // ============================================================

  Future<void> loadInstalledApps({bool force = false}) async {
    if (appsLoading) return;

    if (_appsLoaded && !force) return;

    if (mounted) {
      setState(() {
        appsLoading = true;
      });
    }

    try {
      final result = await _methods.invokeMethod<List<dynamic>>(
        'getInstalledApps',
      );

      if (!mounted) return;

      final loaded = (result ?? [])
          .map((item) => Map<String, dynamic>.from(item as Map))
          .toList();

      setState(() {
        installedApps = loaded;
        _appsLoaded = true;
      });
    } catch (e) {
      showMessage('Unable to load applications: $e');
    } finally {
      if (mounted) {
        setState(() {
          appsLoading = false;
        });
      }
    }
  }

  List<Map<String, dynamic>> get filteredApps {
    final query = searchQuery.trim().toLowerCase();

    return installedApps.where((app) {
      final name = (app['appName'] ?? '').toString().toLowerCase();

      final package = (app['packageName'] ?? '').toString().toLowerCase();

      final matchesSearch = name.contains(query) || package.contains(query);

      final isSystem = app['isSystemApp'] == true;

      bool matchesFilter = true;

      if (appFilter == 'User Apps') {
        matchesFilter = !isSystem;
      } else if (appFilter == 'System Apps') {
        matchesFilter = isSystem;
      }

      return matchesSearch && matchesFilter;
    }).toList();
  }

  Future<void> openAppInfo(String packageName) async {
    try {
      await _methods.invokeMethod('openAppInfo', {'packageName': packageName});
    } catch (e) {
      showMessage('Unable to open App Info: $e');
    }
  }

  // ============================================================
  // UNINSTALL
  // ============================================================

  Future<void> confirmUninstall(Map<String, dynamic> app) async {
    final appName = app['appName']?.toString() ?? 'Unknown App';

    final packageName = app['packageName']?.toString() ?? '';

    if (packageName.isEmpty || packageName == 'com.example.adware_removal') {
      showMessage('This application cannot uninstall itself.');
      return;
    }

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('Uninstall Application?'),
          content: Text(
            'Are you sure you want to remove $appName?\n\n'
            'Package: $packageName\n\n'
            '${rootAvailable ? 'Root access is available. Live UI Tracker will attempt automatic force uninstall.' : 'Root is unavailable. Android will open its normal uninstall confirmation.'}',
          ),
          actions: [
            TextButton(
              onPressed: () {
                Navigator.pop(dialogContext, false);
              },
              child: const Text('Cancel'),
            ),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.redAccent),
              onPressed: () {
                Navigator.pop(dialogContext, true);
              },
              child: const Text('Continue'),
            ),
          ],
        );
      },
    );

    if (confirmed != true) return;

    if (rootAvailable) {
      await forceUninstall(packageName);
    } else {
      await normalUninstall(packageName);
    }
  }

  Future<void> forceUninstall(String packageName) async {
    showMessage('Requesting root uninstall...');

    try {
      final result = await _methods.invokeMethod<Map<dynamic, dynamic>>(
        'forceUninstallApp',
        {'packageName': packageName},
      );

      if (!mounted) return;

      final success = result?['success'] == true;
      final output = result?['output']?.toString() ?? '';

      if (success) {
        showMessage('Application successfully removed.');

        await loadInstalledApps(force: true);
      } else {
        showMessage('Root uninstall failed. Opening normal Android uninstall.');

        debugPrint('Root uninstall output: $output');

        await normalUninstall(packageName);
      }
    } on PlatformException catch (e) {
      showMessage('Root uninstall error: ${e.message}');
    } catch (e) {
      showMessage('Uninstall error: $e');
    }
  }

  Future<void> normalUninstall(String packageName) async {
    debugPrint('LIVE_TRACKER: normalUninstall called: $packageName');

    _refreshAppsOnResume = true;

    try {
      final result = await _methods.invokeMethod<String>('uninstallApp', {
        'packageName': packageName,
      });

      debugPrint('LIVE_TRACKER: Native result: $result');

      if (!mounted) return;
    } on PlatformException catch (e) {
      _refreshAppsOnResume = false;

      debugPrint('LIVE_TRACKER ERROR: ${e.code} - ${e.message}');

      if (!mounted) return;

      showMessage('Uninstall failed: ${e.message}');
    } catch (e) {
      _refreshAppsOnResume = false;

      debugPrint('LIVE_TRACKER ERROR: $e');

      if (!mounted) return;

      showMessage('Uninstall error: $e');
    }
  }

  // ============================================================
  // HELPERS
  // ============================================================

  void showMessage(String message) {
    if (!mounted) return;

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
    );
  }

  String formatDate(dynamic timestamp) {
    if (timestamp == null) return 'Unknown';

    final milliseconds = int.tryParse(timestamp.toString());

    if (milliseconds == null || milliseconds <= 0) {
      return 'Unknown';
    }

    final date = DateTime.fromMillisecondsSinceEpoch(milliseconds);

    return '${date.day.toString().padLeft(2, '0')}/'
        '${date.month.toString().padLeft(2, '0')}/'
        '${date.year}';
  }

  Widget appIcon(Map<dynamic, dynamic> app, {double size = 48}) {
    final encoded = app['iconBase64']?.toString() ?? '';

    if (encoded.isEmpty) {
      return _fallbackAppIcon(size);
    }

    try {
      final bytes = base64Decode(encoded);

      return ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Image.memory(
          bytes,
          width: size,
          height: size,
          fit: BoxFit.contain,
          gaplessPlayback: true,
          errorBuilder: (context, error, stackTrace) {
            return _fallbackAppIcon(size);
          },
        ),
      );
    } catch (e) {
      return _fallbackAppIcon(size);
    }
  }

  Widget _fallbackAppIcon(double size) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: const Color(0xFF253B5C),
        borderRadius: BorderRadius.circular(12),
      ),
      child: const Icon(Icons.android, color: Colors.lightBlueAccent),
    );
  }

  // ============================================================
  // APP LIFECYCLE
  // ============================================================

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      checkPermissions();
      checkRootAccess();

      // Read the real native service status whenever the dashboard
      // returns to the foreground.
      syncTrackerStatus();

      if (_refreshAppsOnResume) {
        _refreshAppsOnResume = false;

        Future.delayed(const Duration(milliseconds: 500), () {
          if (mounted) {
            loadInstalledApps(force: true);
          }
        });
      }
    } else if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive) {
      // Do not keep the Flutter dashboard's timer running in background.
      // The Android foreground service handles monitoring independently.
      _stopRefreshTimer();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _stopRefreshTimer();
    super.dispose();
  }

  // ============================================================
  // MAIN INTERFACE
  // ============================================================

  @override
  Widget build(BuildContext context) {
    final pages = [buildDashboard(), buildAppsManager()];

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Live UI Tracker',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        actions: [
          IconButton(
            onPressed: () {
              checkPermissions();
              checkRootAccess();
              syncTrackerStatus();

              if (selectedIndex == 1) {
                loadInstalledApps(force: true);
              }
            },
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: loading
          ? const Center(child: CircularProgressIndicator())
          : IndexedStack(index: selectedIndex, children: pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: selectedIndex,
        onDestinationSelected: (index) {
          setState(() {
            selectedIndex = index;
          });

          if (index == 1) {
            loadInstalledApps();
          }
        },
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.dashboard_outlined),
            selectedIcon: Icon(Icons.dashboard),
            label: 'Dashboard',
          ),
          NavigationDestination(icon: Icon(Icons.apps), label: 'Apps Manager'),
        ],
      ),
    );
  }

  // ============================================================
  // DASHBOARD SCREEN
  // ============================================================

  Widget buildDashboard() {
    final appName =
        currentApp?['appName']?.toString() ?? 'No application detected';

    final packageName = currentApp?['packageName']?.toString() ?? '';

    return RefreshIndicator(
      onRefresh: checkPermissions,
      child: ListView(
        padding: const EdgeInsets.all(18),
        children: [
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
                    crossAxisAlignment: CrossAxisAlignment.start,
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
                  tracking ? Icons.check_circle : Icons.pause_circle,
                  color: tracking ? Colors.greenAccent : Colors.orangeAccent,
                ),
              ],
            ),
          ),

          const SizedBox(height: 20),

          // ROOT STATUS AND WARNING
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFF1C2635),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(
                color: rootAvailable
                    ? Colors.greenAccent.withValues(alpha: 0.4)
                    : Colors.orangeAccent.withValues(alpha: 0.4),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.security,
                      color: rootAvailable
                          ? Colors.greenAccent
                          : Colors.orangeAccent,
                      size: 30,
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Root Access',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              fontSize: 16,
                            ),
                          ),
                          Text(
                            rootAvailable
                                ? 'Root access detected'
                                : 'Root access not detected',
                            style: TextStyle(
                              color: rootAvailable
                                  ? Colors.greenAccent
                                  : Colors.orangeAccent,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      onPressed: checkRootAccess,
                      icon: const Icon(Icons.refresh),
                    ),
                  ],
                ),

                const SizedBox(height: 12),
                const Divider(color: Colors.white12),
                const SizedBox(height: 8),

                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(
                      Icons.warning_amber_rounded,
                      color: Colors.orangeAccent,
                      size: 20,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'ROOT USERS ONLY\n'
                        'Force Uninstall requires a rooted Android device '
                        'and authorized root access. This feature is intended '
                        'for advanced users. Removing system applications '
                        'may affect device functionality or stability.',
                        style: TextStyle(
                          color: Colors.orangeAccent.shade100,
                          fontSize: 12,
                          height: 1.5,
                        ),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 10),

                Text(
                  rootAvailable
                      ? 'You can use root-based force uninstall.'
                      : 'Non-rooted devices can still use normal Android uninstall.',
                  style: const TextStyle(color: Colors.white60, fontSize: 11),
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),

          const Text(
            'Required Permissions',
            style: TextStyle(fontSize: 19, fontWeight: FontWeight.bold),
          ),

          const SizedBox(height: 12),

          permissionTile(
            title: 'Usage Access',
            description: 'Allow Live UI Tracker to detect active apps.',
            granted: usageAccess,
            onTap: openUsageSettings,
          ),

          permissionTile(
            title: 'Display Over Other Apps',
            description: 'Allow the floating controller to appear.',
            granted: overlayAccess,
            onTap: openOverlaySettings,
          ),

          const SizedBox(height: 18),

          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: tracking ? null : startTracking,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Start Tracker'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: tracking ? stopTracking : null,
                  icon: const Icon(Icons.stop),
                  label: const Text('Stop Tracker'),
                ),
              ),
            ],
          ),

          const SizedBox(height: 26),

          const Text(
            'Currently Detected Application',
            style: TextStyle(fontSize: 19, fontWeight: FontWeight.bold),
          ),

          const SizedBox(height: 12),

          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(
              color: const Color(0xFF1C2635),
              borderRadius: BorderRadius.circular(18),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    appIcon(currentApp ?? {}, size: 48),
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
                  style: TextStyle(color: Colors.white54, fontSize: 11),
                ),

                const SizedBox(height: 4),

                SelectableText(
                  packageName.isEmpty ? 'Waiting for detection' : packageName,
                  style: const TextStyle(color: Colors.lightBlueAccent),
                ),

                const SizedBox(height: 16),

                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: packageName.isEmpty ? null : openDetectedAppInfo,
                    icon: const Icon(Icons.settings),
                    label: const Text('Open Android App Info'),
                  ),
                ),
              ],
            ),
          ),

          if (errorMessage.isNotEmpty) ...[
            const SizedBox(height: 15),
            Text(errorMessage, style: const TextStyle(color: Colors.redAccent)),
          ],
        ],
      ),
    );
  }

  // ============================================================
  // APPS MANAGER SCREEN
  // ============================================================

  Widget buildAppsManager() {
    final apps = filteredApps;

    final userCount = installedApps
        .where((app) => app['isSystemApp'] != true)
        .length;

    final systemCount = installedApps
        .where((app) => app['isSystemApp'] == true)
        .length;

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: Column(
            children: [
              Row(
                children: [
                  Expanded(
                    child: statCard(
                      'Total Apps',
                      installedApps.length.toString(),
                      Icons.apps,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: statCard(
                      'User Apps',
                      userCount.toString(),
                      Icons.person,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: statCard(
                      'System Apps',
                      systemCount.toString(),
                      Icons.settings,
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 14),

              TextField(
                onChanged: (value) {
                  setState(() {
                    searchQuery = value;
                  });
                },
                decoration: InputDecoration(
                  hintText: 'Search apps or package name...',
                  prefixIcon: const Icon(Icons.search),
                  filled: true,
                  fillColor: const Color(0xFF1C2635),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),

              const SizedBox(height: 12),

              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: [
                    filterChip('All'),
                    filterChip('User Apps'),
                    filterChip('System Apps'),
                  ],
                ),
              ),
            ],
          ),
        ),

        Expanded(
          child: appsLoading && installedApps.isEmpty
              ? const Center(child: CircularProgressIndicator())
              : apps.isEmpty
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(
                        Icons.search_off,
                        size: 50,
                        color: Colors.white38,
                      ),
                      const SizedBox(height: 10),
                      const Text('No applications found.'),
                      TextButton(
                        onPressed: () {
                          loadInstalledApps(force: true);
                        },
                        child: const Text('Refresh'),
                      ),
                    ],
                  ),
                )
              : RefreshIndicator(
                  onRefresh: () {
                    return loadInstalledApps(force: true);
                  },
                  child: ListView.builder(
                    padding: const EdgeInsets.fromLTRB(12, 4, 12, 16),
                    itemCount: apps.length,
                    cacheExtent: 500,
                    itemBuilder: (context, index) {
                      final app = apps[index];
                      return appTile(app);
                    },
                  ),
                ),
        ),
      ],
    );
  }

  Widget filterChip(String value) {
    final selected = appFilter == value;

    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        label: Text(value),
        selected: selected,
        onSelected: (_) {
          setState(() {
            appFilter = value;
          });
        },
      ),
    );
  }

  Widget statCard(String title, String value, IconData icon) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF1C2635),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        children: [
          Icon(icon, color: Colors.blueAccent),
          const SizedBox(height: 7),
          Text(
            value,
            style: const TextStyle(fontSize: 19, fontWeight: FontWeight.bold),
          ),
          Text(
            title,
            style: const TextStyle(fontSize: 10, color: Colors.white60),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }

  Widget appTile(Map<String, dynamic> app) {
    final name = app['appName']?.toString() ?? 'Unknown App';

    final package = app['packageName']?.toString() ?? '';

    return Card(
      color: const Color(0xFF1C2635),
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        onTap: () => showAppDetails(app),
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 5),
        leading: AppIconView(packageName: package, size: 48),
        title: Text(
          name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        subtitle: Text(
          package,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 11, color: Colors.white54),
        ),
        trailing: const Icon(Icons.chevron_right, color: Colors.white54),
      ),
    );
  }

  // ============================================================
  // APP DETAILS
  // ============================================================

  void showAppDetails(Map<String, dynamic> app) {
    final name = app['appName']?.toString() ?? 'Unknown App';

    final package = app['packageName']?.toString() ?? '';

    final version = app['versionName']?.toString() ?? 'Unknown';

    final firstInstall = formatDate(app['firstInstallTime']);
    final lastUpdate = formatDate(app['lastUpdateTime']);

    final isSystem = app['isSystemApp'] == true;
    final canUninstall = app['canUninstall'] == true;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: const Color(0xFF151B26),
      builder: (sheetContext) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(22),
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Center(
                    child: Container(
                      width: 42,
                      height: 5,
                      decoration: BoxDecoration(
                        color: Colors.white24,
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                  ),

                  const SizedBox(height: 22),

                  Row(
                    children: [
                      AppIconView(packageName: package, size: 58),
                      const SizedBox(width: 14),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              name,
                              style: const TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            Text(
                              isSystem
                                  ? 'System Application'
                                  : 'User Application',
                              style: const TextStyle(color: Colors.white54),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 22),

                  detailRow('Package', package),
                  detailRow('Version', version),
                  detailRow('Installed', firstInstall),
                  detailRow('Last Updated', lastUpdate),
                  detailRow(
                    'Application Type',
                    isSystem ? 'System App' : 'User App',
                  ),
                  detailRow(
                    'Root Status',
                    rootAvailable ? 'Available' : 'Unavailable',
                  ),

                  const SizedBox(height: 22),

                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: () {
                        Navigator.pop(sheetContext);
                        openAppInfo(package);
                      },
                      icon: const Icon(Icons.settings),
                      label: const Text('Open Android App Info'),
                    ),
                  ),

                  if (canUninstall) ...[
                    const SizedBox(height: 10),

                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        style: FilledButton.styleFrom(
                          backgroundColor: Colors.redAccent,
                        ),
                        onPressed: () {
                          Navigator.pop(sheetContext);
                          confirmUninstall(app);
                        },
                        icon: Icon(
                          rootAvailable
                              ? Icons.delete_forever
                              : Icons.delete_outline,
                        ),
                        label: Text(
                          rootAvailable
                              ? 'Force Uninstall (Root)'
                              : 'Uninstall Application',
                        ),
                      ),
                    ),
                  ] else if (isSystem) ...[
                    const SizedBox(height: 10),
                    const Text(
                      'System app: removal is restricted. Check Android App Info for available actions.',
                      style: TextStyle(
                        color: Colors.orangeAccent,
                        fontSize: 12,
                      ),
                    ),
                  ],

                  const SizedBox(height: 10),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget detailRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label.toUpperCase(),
            style: const TextStyle(
              fontSize: 10,
              color: Colors.white54,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 4),
          SelectableText(value, style: const TextStyle(fontSize: 13)),
        ],
      ),
    );
  }

  // ============================================================
  // PERMISSION TILE
  // ============================================================

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
          granted ? Icons.check_circle : Icons.warning_amber,
          color: granted ? Colors.greenAccent : Colors.orangeAccent,
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
