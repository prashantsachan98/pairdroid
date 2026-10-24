import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'native_api.dart';

void main() => runApp(const PairDroidApp());
const api = NativeApi();
const pine = Color(0xff175c50);

class PairDroidApp extends StatelessWidget {
  const PairDroidApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'PairDroid',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(seedColor: pine),
      scaffoldBackgroundColor: const Color(0xfff5f7f3),
      inputDecorationTheme: const InputDecorationTheme(
        border: OutlineInputBorder(),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: Colors.white,
        margin: const EdgeInsets.only(bottom: 14),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      ),
    ),
    home: const HomePage(),
  );
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  Json? state;
  String? failure;
  Timer? timer;
  bool polling = false, busy = false;
  bool get child => state?['role'] == 'child';
  @override
  void initState() {
    super.initState();
    refresh();
    timer = Timer.periodic(const Duration(seconds: 2), (_) => refresh());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> refresh() async {
    if (polling) return;
    polling = true;
    try {
      final value = await api.call('state');
      if (mounted) {
        setState(() {
          state = value;
          failure = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => failure = errorText(e));
    } finally {
      polling = false;
    }
  }

  Future<void> action(String method, [Json args = const {}]) async {
    if (busy) return;
    setState(() => busy = true);
    try {
      await api.call(method, args);
      await refresh();
    } catch (e) {
      if (mounted) notice(context, errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(
        'PairDroid ${child
            ? 'Child'
            : state == null
            ? ''
            : 'Parent'}',
      ),
      actions: [
        IconButton(
          onPressed: refresh,
          icon: const Icon(Icons.refresh),
          tooltip: 'Refresh status',
        ),
      ],
    ),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 840),
        child: RefreshIndicator(
          onRefresh: refresh,
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              if (failure != null)
                Section(
                  title: 'Could not read device state',
                  children: [
                    Text(failure!),
                    TextButton(onPressed: refresh, child: const Text('Retry')),
                  ],
                ),
              if (state == null && failure == null)
                const Center(child: CircularProgressIndicator()),
              if (state != null) ...[
                BannerCard(
                  icon: child ? Icons.shield_outlined : Icons.devices,
                  title: child
                      ? 'Your device. Clear controls.'
                      : 'A little more peace of mind.',
                  text: child
                      ? 'Management stays visible. Data is shared only with your approved parent over the local network.'
                      : 'Connect to a child device on the same Wi-Fi. Your family’s data stays on your devices.',
                ),
                if (state!['error'] != '')
                  Section(
                    title: 'Connection notice',
                    children: [Text(state!['error'] as String)],
                  ),
                Section(
                  title: 'This device',
                  children: [
                    Text(
                      state!['name'] as String,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    Text(
                      'Android ${state!['android']} • ${state!['wifi'] == true ? 'Wi-Fi connected' : 'Wi-Fi not detected; check hotspot settings'}',
                    ),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        OutlinedButton.icon(
                          onPressed: busy ? null : () => action('permissions'),
                          icon: const Icon(Icons.wifi),
                          label: const Text('Allow network & notifications'),
                        ),
                        TextButton(
                          onPressed: () => action('appSettings'),
                          child: const Text('App permissions'),
                        ),
                      ],
                    ),
                  ],
                ),
                if (child) ...childViews() else ...parentViews(),
                Section(
                  title: 'Device identity',
                  children: [
                    const Text(
                      'Compare fingerprints on both devices during pairing. Discovery is only a hint.',
                    ),
                    fingerprint(state!['fingerprint'] as String),
                    TextButton(
                      onPressed: busy ? null : () => action('rotate'),
                      child: const Text(
                        'Rotate identity (requires no pairings)',
                      ),
                    ),
                  ],
                ),
                if (child)
                  Section(
                    title: 'Local activity log',
                    children: [
                      for (final e in state!['audit'] as List)
                        ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          title: Text(e['event'] as String),
                          subtitle: Text(timeText(e['at'])),
                        ),
                    ],
                  ),
              ],
            ],
          ),
        ),
      ),
    ),
  );
  List<Widget> childViews() {
    final s = state!;
    final pending = s['pending'] as Json?;
    return [
      Section(
        title: 'Visible management',
        children: [
          StatusLine(
            ok: s['running'] == true,
            text: s['running'] == true
                ? 'Local service is active'
                : 'Local service is stopped',
          ),
          Text(
            (s['parent'] as String).isEmpty
                ? 'No parent is authorized.'
                : 'One parent is authorized. You can revoke access here.',
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            children: [
              FilledButton(
                onPressed: busy
                    ? null
                    : () => action(s['running'] == true ? 'stop' : 'start'),
                child: Text(
                  s['running'] == true ? 'Stop service' : 'Start local service',
                ),
              ),
              if ((s['parent'] as String).isNotEmpty)
                OutlinedButton(
                  onPressed: busy ? null : () => action('revoke'),
                  child: const Text('Revoke parent & clear policy'),
                ),
            ],
          ),
          const SizedBox(height: 8),
          const Text(
            'The persistent notification shows when management or sharing is active. Restart the service here after a reboot or OS shutdown.',
          ),
        ],
      ),
      Section(
        title: 'Pair with a parent',
        children: [
          const Text(
            'Open a two-minute window, then enter this IP, port, fingerprint and code on the Parent app.',
          ),
          const SizedBox(height: 12),
          for (final address in s['addresses'] as List)
            SelectableText(
              '$address : ${s['port']}',
              style: Theme.of(context).textTheme.titleLarge,
            ),
          if ((s['addresses'] as List).isEmpty)
            const Text(
              'No supported local IPv4 interface found. Connect to Wi-Fi or enable a supported hotspot.',
            ),
          if (s['code'] != '') ...[
            const SizedBox(height: 12),
            SelectableText(
              'Code: ${s['code']}',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const Text(
              'Expires after two minutes; at most five code attempts.',
            ),
          ],
          const SizedBox(height: 12),
          FilledButton.tonal(
            onPressed:
                busy ||
                    s['running'] != true ||
                    (s['parent'] as String).isNotEmpty
                ? null
                : () => action('openPairing'),
            child: const Text('Open pairing window'),
          ),
          if (pending != null) ...[
            const Divider(height: 32),
            Text(
              '${pending['name']} wants to pair',
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const Text(
              'Compare this parent fingerprint with the one displayed on the Parent app. Approve only if it matches.',
            ),
            fingerprint(pending['pin'] as String),
            Wrap(
              spacing: 8,
              children: [
                FilledButton(
                  onPressed: () => action('approve', {
                    'pin': pending['pin'],
                    'approve': true,
                  }),
                  child: const Text('Approve this parent'),
                ),
                OutlinedButton(
                  onPressed: () => action('approve', {
                    'pin': pending['pin'],
                    'approve': false,
                  }),
                  child: const Text('Deny'),
                ),
              ],
            ),
          ],
        ],
      ),
      Section(
        title: 'Usage & restrictions',
        children: [
          StatusLine(
            ok: s['usageAccess'] == true,
            text: s['usageAccess'] == true
                ? 'Usage access granted'
                : 'Usage access not granted',
          ),
          Text(
            s['owner'] == true
                ? 'Managed-device mode: eligible apps can be suspended by policy.'
                : 'Ordinary installation: limits are advisory. Android does not allow this app to block other apps.',
          ),
          TextButton(
            onPressed: () => action('usageSettings'),
            child: const Text('Open Android Usage access'),
          ),
          TextButton(
            onPressed: busy ? null : () => action('clearPolicy'),
            child: const Text('Clear local policy & release restrictions'),
          ),
          Text(
            'Current policy: ${((s['policy'] as Json)['limits'] as Json?)?.length ?? 0} per-app limits. Always-allowed apps are exempt.',
          ),
        ],
      ),
      Section(
        title: 'Screen sharing',
        children: [
          StatusLine(
            ok: s['sharing'] == true,
            text: s['sharing'] == true
                ? 'Screen sharing is active'
                : 'Your screen is not being shared',
          ),
          if (s['shareRequested'] == true) ...[
            const Text(
              'Your approved parent requested a live view. Choose what to share in the Android consent dialog.',
            ),
            FilledButton(
              onPressed: busy ? null : () => action('capture'),
              child: const Text('Review & allow screen sharing'),
            ),
          ],
          if (s['shareRequested'] == true || s['sharing'] == true)
            OutlinedButton(
              onPressed: () => action('stopSharing'),
              child: const Text('Stop / decline sharing'),
            ),
          const Text(
            'No audio. No recording. Sharing ends after the viewer stops polling, pairing is revoked, or you stop it.',
          ),
        ],
      ),
    ];
  }

  List<Widget> parentViews() {
    final s = state!;
    final peers = s['peers'] as List;
    void open(Json peer) {
      final target = Map<String, dynamic>.from(peer);
      for (final found in s['discovered'] as List) {
        if (found['hint'] == peer['pin']) {
          target['host'] = found['host'];
          target['port'] = found['port'];
        }
      }
      Navigator.of(context)
          .push(
            MaterialPageRoute<void>(builder: (_) => DevicePage(target: target)),
          )
          .then((_) => refresh());
    }

    return [
      Section(
        title: 'Your child devices',
        children: [
          if (peers.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 12),
              child: Text(
                'No devices paired yet. Start the service on the Child app to connect.',
              ),
            ),
          for (final p in peers)
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const CircleAvatar(child: Icon(Icons.phone_android)),
              title: Text(p['name'] as String),
              subtitle: Text(
                '${p['host']}:${p['port']} • Tap to check connection',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => open(p as Json),
            ),
          FilledButton.icon(
            onPressed: () => Navigator.of(context)
                .push(
                  MaterialPageRoute<void>(
                    builder: (_) =>
                        PairPage(identity: s['fingerprint'] as String),
                  ),
                )
                .then((_) => refresh()),
            icon: const Icon(Icons.add),
            label: const Text('Pair a device manually'),
          ),
        ],
      ),
      Section(
        title: 'Nearby on your network',
        children: [
          const Text(
            'Discovery can be blocked by router isolation or multicast filtering. Use manual IP pairing if no device appears.',
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: [
              OutlinedButton.icon(
                onPressed: () => action('discover'),
                icon: const Icon(Icons.radar),
                label: const Text('Find child devices'),
              ),
              TextButton(
                onPressed: () => action('stopDiscovery'),
                child: const Text('Stop discovery'),
              ),
            ],
          ),
          for (final p in s['discovered'] as List)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(p['name'] as String),
              subtitle: Text('${p['host']}:${p['port']}'),
              trailing: const Icon(Icons.add_link),
              onTap: () => Navigator.of(context)
                  .push(
                    MaterialPageRoute<void>(
                      builder: (_) => PairPage(
                        identity: s['fingerprint'] as String,
                        discovered: p as Json,
                      ),
                    ),
                  )
                  .then((_) => refresh()),
            ),
        ],
      ),
    ];
  }
}

class PairPage extends StatefulWidget {
  const PairPage({super.key, required this.identity, this.discovered});
  final String identity;
  final Json? discovered;
  @override
  State<PairPage> createState() => _PairPageState();
}

class _PairPageState extends State<PairPage> {
  final host = TextEditingController(),
      port = TextEditingController(),
      pin = TextEditingController(),
      code = TextEditingController();
  bool busy = false;
  String? failure;
  @override
  void initState() {
    super.initState();
    host.text = widget.discovered?['host'] as String? ?? '';
    port.text = '${widget.discovered?['port'] ?? ''}';
  }

  @override
  void dispose() {
    host.dispose();
    port.dispose();
    pin.dispose();
    code.dispose();
    super.dispose();
  }

  Future<void> pair() async {
    setState(() {
      busy = true;
      failure = null;
    });
    try {
      final target = PairingInput.parse(
        host.text,
        port.text,
        pin.text,
        code.text,
      );
      await api.rpc(target, 'pair', {
        'code': code.text.trim(),
        'name': 'PairDroid Parent',
      });
      if (mounted) {
        notice(context, 'Pairing approved. Child identity saved.');
        Navigator.pop(context);
      }
    } catch (e) {
      if (mounted) setState(() => failure = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Pair a child device')),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 700),
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const BannerCard(
              icon: Icons.handshake_outlined,
              title: 'Approve together',
              text:
                  'Keep both devices nearby. Open pairing on the Child app and copy its details here. The child must explicitly approve your fingerprint.',
            ),
            Section(
              title: 'Child connection details',
              children: [
                field(host, 'Child IP address', keyboard: TextInputType.number),
                field(port, 'Child port', keyboard: TextInputType.number),
                field(pin, 'Full child fingerprint', maxLines: 3),
                field(
                  code,
                  'Six-digit pairing code',
                  keyboard: TextInputType.number,
                ),
                const Text(
                  'Read the fingerprint from the Child app. It is never trusted from network discovery.',
                ),
                if (failure != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Text(
                      failure!,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.error,
                      ),
                    ),
                  ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: busy ? null : pair,
                  child: Text(
                    busy
                        ? 'Waiting for child approval (45s)…'
                        : 'Request pairing',
                  ),
                ),
              ],
            ),
            Section(
              title: 'Your parent fingerprint',
              children: [
                const Text(
                  'Compare this exact value on the child before approving.',
                ),
                fingerprint(widget.identity),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}

class DevicePage extends StatefulWidget {
  const DevicePage({super.key, required this.target});
  final Json target;
  @override
  State<DevicePage> createState() => _DevicePageState();
}

class _DevicePageState extends State<DevicePage> {
  Json? status;
  List<dynamic>? apps, days, alerts;
  String? failure;
  int tab = 0, rtt = 0;
  bool polling = false, loading = false, frameBusy = false;
  Uint8List? frame;
  Timer? heartbeat, frames;
  final host = TextEditingController(), port = TextEditingController();
  Json get target => widget.target;
  @override
  void initState() {
    super.initState();
    host.text = target['host'] as String;
    port.text = '${target['port']}';
    refresh();
    heartbeat = Timer.periodic(const Duration(seconds: 5), (_) => refresh());
    frames = Timer.periodic(
      const Duration(milliseconds: 500),
      (_) => fetchFrame(),
    );
  }

  @override
  void dispose() {
    heartbeat?.cancel();
    frames?.cancel();
    host.dispose();
    port.dispose();
    if (status?['sharing'] == true || status?['shareRequested'] == true) {
      unawaited(
        api
            .rpc(target, 'stopSharing')
            .catchError((Object _) => <String, dynamic>{}),
      );
    }
    super.dispose();
  }

  Future<void> refresh() async {
    if (polling) return;
    polling = true;
    try {
      final value = await api.rpc(target, 'status');
      if (mounted) {
        setState(() {
          status = value;
          failure = null;
          if (value['sharing'] != true) frame = null;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          failure = errorText(e);
          frame = null;
        });
      }
      try {
        final local = await api.call('state');
        for (final found in local['discovered'] as List) {
          if (found['hint'] == target['pin']) {
            target['host'] = found['host'];
            target['port'] = found['port'];
          }
        }
      } catch (_) {
        /* The next heartbeat retries the saved, pinned endpoint. */
      }
    } finally {
      polling = false;
    }
  }

  Future<void> fetchFrame() async {
    if (tab != 4 || status?['sharing'] != true || frameBusy) return;
    frameBusy = true;
    final watch = Stopwatch()..start();
    try {
      final data = await api.rpc(target, 'frame');
      if (mounted && tab == 4 && data['jpeg'] != null) {
        setState(() {
          frame = base64Decode(data['jpeg'] as String);
          rtt = watch.elapsedMilliseconds;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          failure = errorText(e);
          frame = null;
        });
      }
    } finally {
      frameBusy = false;
    }
  }

  Future<void> load(int selected) async {
    if (tab == 4 && selected != 4) {
      unawaited(command('stopSharing'));
      frame = null;
    }
    setState(() => tab = selected);
    if (selected == 1 || selected == 3) await requestData('apps');
    if (selected == 2) await requestData('usage');
    if (selected == 0) await requestData('alerts');
  }

  Future<void> requestData(String cmd) async {
    setState(() => loading = true);
    try {
      final data = await api.rpc(target, cmd);
      if (mounted) {
        setState(() {
          if (cmd == 'apps') apps = data['apps'] as List;
          if (cmd == 'usage') days = data['days'] as List;
          if (cmd == 'alerts') alerts = data['alerts'] as List;
        });
      }
    } catch (e) {
      if (mounted) notice(context, errorText(e));
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> command(String cmd, [Json payload = const {}]) async {
    try {
      await api.rpc(target, cmd, payload);
      await refresh();
    } catch (e) {
      if (mounted) notice(context, errorText(e));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(target['name'] as String? ?? 'Child device'),
      actions: [
        IconButton(onPressed: refresh, icon: const Icon(Icons.refresh)),
      ],
    ),
    bottomNavigationBar: NavigationBar(
      selectedIndex: tab,
      onDestinationSelected: load,
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.dashboard_outlined),
          label: 'Overview',
        ),
        NavigationDestination(icon: Icon(Icons.apps), label: 'Apps'),
        NavigationDestination(icon: Icon(Icons.bar_chart), label: 'Usage'),
        NavigationDestination(icon: Icon(Icons.schedule), label: 'Limits'),
        NavigationDestination(
          icon: Icon(Icons.screen_share_outlined),
          label: 'Live view',
        ),
      ],
    ),
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 840),
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            StatusLine(
              ok: status != null && failure == null,
              text: failure == null
                  ? (status == null
                        ? 'Connecting…'
                        : 'Connected • checked ${timeText(status!['at'])}')
                  : 'Offline / unavailable: $failure',
            ),
            if (loading) const LinearProgressIndicator(),
            if (tab == 0) ...overview(),
            if (tab == 1)
              Section(
                title: 'Installed launcher apps',
                children: [
                  const Text(
                    'Android package visibility limits this list to launcher apps.',
                  ),
                  TextButton(
                    onPressed: () => requestData('apps'),
                    child: const Text('Refresh apps'),
                  ),
                  for (final a in apps ?? [])
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: appIcon(a as Json),
                      title: Text(a['label'] as String),
                      subtitle: SelectableText(a['package'] as String),
                    ),
                ],
              ),
            if (tab == 2) ...usageViews(),
            if (tab == 3 && status != null)
              PolicyEditor(
                key: ValueKey(target['pin']),
                apps: apps ?? [],
                policy: status!['policy'] as Json,
                owner: status!['owner'] == true,
                onSave: (policy) async {
                  await api.rpc(target, 'policy', {'policy': policy});
                  await refresh();
                },
              ),
            if (tab == 4) ...shareViews(),
          ],
        ),
      ),
    ),
  );
  List<Widget> overview() => [
    if (status != null)
      Section(
        title: 'Device status',
        children: [
          Text(
            '${status!['name']} • Android ${status!['android']}',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          Text(
            'Battery ${status!['battery']}% ${status!['charging'] == true ? '• Charging' : ''}',
          ),
          StatusLine(
            ok: status!['usageAccess'] == true,
            text: status!['usageAccess'] == true
                ? 'Usage reporting available'
                : 'Child must grant Usage access',
          ),
          Text(
            status!['owner'] == true
                ? 'Managed-device restrictions available'
                : 'Advisory limits on this installation',
          ),
        ],
      ),
    Section(
      title: 'Local alerts & activity',
      children: [
        TextButton(
          onPressed: () => requestData('alerts'),
          child: const Text('Load alerts'),
        ),
        for (final a in alerts ?? [])
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(a['event'] as String),
            subtitle: Text(timeText(a['at'])),
          ),
      ],
    ),
    Section(
      title: 'Connection & pairing settings',
      children: [
        const Text(
          'If discovery is unavailable after an IP change, update the endpoint. The saved certificate remains pinned.',
        ),
        field(host, 'Child IP'),
        field(port, 'Child port', keyboard: TextInputType.number),
        OutlinedButton(
          onPressed: () {
            final p = int.tryParse(port.text);
            if (p == null || p < 1024 || p > 65535) {
              notice(context, 'Invalid port');
              return;
            }
            target['host'] = host.text.trim();
            target['port'] = p;
            refresh();
          },
          child: const Text('Reconnect to this address'),
        ),
        fingerprint(target['pin'] as String),
        OutlinedButton(
          onPressed: () async {
            try {
              await api.rpc(target, 'unpair');
              if (mounted) Navigator.pop(context);
            } catch (e) {
              if (mounted) notice(context, errorText(e));
            }
          },
          child: const Text('Revoke pairing on child'),
        ),
        TextButton(
          onPressed: () async {
            await api.call('forget', {'pin': target['pin']});
            if (mounted) Navigator.pop(context);
          },
          child: const Text('Forget locally (child must revoke separately)'),
        ),
      ],
    ),
  ];
  List<Widget> usageViews() {
    final values = days ?? [];
    final totals = values
        .map<num>(
          (d) => (d['apps'] as List).fold<num>(
            0,
            (sum, a) => sum + (a['ms'] as num),
          ),
        )
        .toList();
    final max = totals.fold<num>(1, (a, b) => a > b ? a : b);
    return [
      Section(
        title: 'Last seven calendar days',
        children: [
          const Text(
            'Foreground activity estimates from Android events, in the child’s timezone. Multi-window use can overlap; missing OS events can affect totals.',
          ),
          TextButton(
            onPressed: () => requestData('usage'),
            child: const Text('Refresh usage'),
          ),
          for (var i = 0; i < values.length; i++)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${values[i]['date']} • ${durationText(totals[i])}'),
                  const SizedBox(height: 5),
                  LinearProgressIndicator(
                    value: (totals[i] / max).toDouble(),
                    minHeight: 9,
                    borderRadius: BorderRadius.circular(8),
                  ),
                ],
              ),
            ),
        ],
      ),
      if (values.isNotEmpty)
        Section(
          title: 'Today by app',
          children: [
            for (final a in values.last['apps'] as List)
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(labelFor(a['package'] as String)),
                trailing: Text(durationText(a['ms'] as num)),
                subtitle: Text('Last active ${timeText(a['last'])}'),
              ),
          ],
        ),
    ];
  }

  String labelFor(String pkg) {
    for (final a in apps ?? []) {
      if (a['package'] == pkg) return a['label'] as String;
    }
    return pkg;
  }

  List<Widget> shareViews() => [
    Section(
      title: 'Live screen view',
      children: [
        const Text(
          'The child reviews each request and grants Android system consent. Stop closes the capture session. Leaving this view also requests a stop.',
        ),
        const SizedBox(height: 16),
        if (frame != null)
          Image.memory(
            frame!,
            gaplessPlayback: true,
            fit: BoxFit.contain,
            height: 480,
          ),
        if (frame != null)
          Text(
            'Last frame request: ${rtt}ms round trip • JPEG preview up to 2.5 fps',
          ),
        if (status?['shareRequested'] == true)
          const Text(
            'Waiting for child approval and Android screen capture consent…',
          ),
        if (status?['sharing'] == true && frame == null)
          const Text('Sharing approved. Waiting for a frame…'),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          children: [
            FilledButton.icon(
              onPressed:
                  failure != null ||
                      status == null ||
                      status?['sharing'] == true ||
                      status?['shareRequested'] == true
                  ? null
                  : () => command('shareRequest'),
              icon: const Icon(Icons.screen_share),
              label: const Text('Request live view'),
            ),
            OutlinedButton(
              onPressed: () {
                setState(() => frame = null);
                command('stopSharing');
              },
              child: const Text('Stop sharing'),
            ),
          ],
        ),
      ],
    ),
  ];
}

class PolicyEditor extends StatefulWidget {
  const PolicyEditor({
    super.key,
    required this.apps,
    required this.policy,
    required this.owner,
    required this.onSave,
  });
  final List<dynamic> apps;
  final Json policy;
  final bool owner;
  final Future<void> Function(Json) onSave;
  @override
  State<PolicyEditor> createState() => _PolicyEditorState();
}

class _PolicyEditorState extends State<PolicyEditor> {
  late Json limits;
  late Set<String> allowed;
  late int start, end;
  bool busy = false;
  @override
  void initState() {
    super.initState();
    limits = Map<String, dynamic>.from(widget.policy['limits'] as Json? ?? {});
    allowed = Set<String>.from(widget.policy['allowed'] as List? ?? []);
    start = widget.policy['start'] as int? ?? 0;
    end = widget.policy['end'] as int? ?? 0;
  }

  String clock(int minute) =>
      '${(minute ~/ 60).toString().padLeft(2, '0')}:${(minute % 60).toString().padLeft(2, '0')}';
  Future<void> choose(bool from) async {
    final minute = from ? start : end;
    final t = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(hour: minute ~/ 60, minute: minute % 60),
    );
    if (t != null) {
      setState(() {
        if (from) {
          start = t.hour * 60 + t.minute;
        } else {
          end = t.hour * 60 + t.minute;
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) => Section(
    title: 'Screen-time policy',
    children: [
      Text(
        widget.owner
            ? 'Managed mode: eligible packages are suspended while their limit or downtime applies.'
            : 'Advisory mode: the child receives visible reminders. This installation cannot block apps.',
      ),
      const SizedBox(height: 8),
      const Text(
        'Policies are stored on the child. Keep its visible service running for evaluation, including offline. Limits reset at child-local midnight. 0 minutes means no daily limit.',
      ),
      const SizedBox(height: 12),
      Text(
        'Daily downtime ${clock(start)} – ${clock(end)} (equal times disables downtime)',
      ),
      Wrap(
        spacing: 8,
        children: [
          OutlinedButton(
            onPressed: () => choose(true),
            child: const Text('Start time'),
          ),
          OutlinedButton(
            onPressed: () => choose(false),
            child: const Text('End time'),
          ),
        ],
      ),
      const Text(
        'Always-allowed apps are exempt from all limits. PairDroid, Settings, the launcher and default dialer are protected automatically.',
      ),
      for (final a in widget.apps)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 10),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(a['label'] as String),
                    Text(
                      a['package'] as String,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              SizedBox(
                width: 90,
                child: TextFormField(
                  key: ValueKey(a['package']),
                  initialValue: '${limits[a['package']] ?? 0}',
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: 'Min/day'),
                  onChanged: (v) =>
                      limits[a['package'] as String] = int.tryParse(v) ?? -1,
                ),
              ),
              Column(
                children: [
                  const Text('Allowed'),
                  Switch(
                    value: allowed.contains(a['package']),
                    onChanged: (v) => setState(() {
                      if (v) {
                        allowed.add(a['package'] as String);
                      } else {
                        allowed.remove(a['package']);
                      }
                    }),
                  ),
                ],
              ),
            ],
          ),
        ),
      FilledButton(
        onPressed: busy
            ? null
            : () async {
                if (limits.values.any((v) => v is! int || v < 0 || v > 1440)) {
                  notice(context, 'Limits must be between 0 and 1440 minutes');
                  return;
                }
                setState(() => busy = true);
                try {
                  await widget.onSave({
                    'limits': limits,
                    'allowed': allowed.toList(),
                    'start': start,
                    'end': end,
                  });
                  if (context.mounted) notice(context, 'Policy saved on child');
                } catch (e) {
                  if (context.mounted) notice(context, errorText(e));
                } finally {
                  if (mounted) setState(() => busy = false);
                }
              },
        child: Text(busy ? 'Saving…' : 'Save policy on child'),
      ),
    ],
  );
}

class Section extends StatelessWidget {
  const Section({super.key, required this.title, required this.children});
  final String title;
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 14),
          ...children,
        ],
      ),
    ),
  );
}

class BannerCard extends StatelessWidget {
  const BannerCard({
    super.key,
    required this.icon,
    required this.title,
    required this.text,
  });
  final IconData icon;
  final String title, text;
  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.only(bottom: 20),
    padding: const EdgeInsets.all(24),
    decoration: BoxDecoration(
      color: pine,
      borderRadius: BorderRadius.circular(24),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, color: Colors.white, size: 36),
        const SizedBox(height: 20),
        Text(
          title,
          style: Theme.of(
            context,
          ).textTheme.headlineSmall?.copyWith(color: Colors.white),
        ),
        const SizedBox(height: 10),
        Text(text, style: const TextStyle(color: Colors.white, height: 1.5)),
      ],
    ),
  );
}

class StatusLine extends StatelessWidget {
  const StatusLine({super.key, required this.ok, required this.text});
  final bool ok;
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(
          ok ? Icons.check_circle_outline : Icons.info_outline,
          color: ok ? pine : Theme.of(context).colorScheme.error,
          size: 20,
        ),
        const SizedBox(width: 8),
        Expanded(child: Text(text)),
      ],
    ),
  );
}

Widget fingerprint(String value) => Padding(
  padding: const EdgeInsets.symmetric(vertical: 12),
  child: SelectableText(
    value,
    style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
  ),
);
Widget field(
  TextEditingController controller,
  String label, {
  TextInputType? keyboard,
  int maxLines = 1,
}) => Padding(
  padding: const EdgeInsets.only(bottom: 16),
  child: TextField(
    controller: controller,
    keyboardType: keyboard,
    maxLines: maxLines,
    autocorrect: false,
    decoration: InputDecoration(labelText: label),
  ),
);
Widget appIcon(Json app) => Image.memory(
  base64Decode(app['icon'] as String),
  width: 40,
  height: 40,
  errorBuilder: (_, error, stack) => const Icon(Icons.apps),
);
void notice(BuildContext context, String message) => ScaffoldMessenger.of(
  context,
).showSnackBar(SnackBar(content: Text(message)));
